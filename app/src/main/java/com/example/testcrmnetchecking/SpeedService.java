package com.example.testcrmnetchecking;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Foreground service that:
 *  - Measures live download/upload speed via TrafficStats.
 *  - Splits usage into Wi-Fi vs Mobile per tick using the OS's reported
 *    active network transport (see NETWORK SPLIT note below).
 *  - Accumulates and persists a running "today" total to SQLite.
 *  - Renders a text-based status bar icon showing short-form speed.
 *
 * NETWORK SPLIT — WHY THIS CHANGED:
 * The previous approach derived mobileDelta from
 * (TrafficStats.getMobileRxBytes()/getMobileTxBytes() deltas) and then set
 * wifiDelta = totalDelta - mobileDelta. That silently broke whenever the
 * mobile counters couldn't be validated for a tick (e.g. radio sleep/wake
 * batching causing the mobile counter to lag or reset independently of the
 * total counter) — in that case mobileDelta fell back to 0, so ALL traffic
 * that tick — including real cellular traffic — was counted as Wi-Fi. That
 * is the "shows Wi-Fi usage while Wi-Fi is off" bug.
 *
 * Fix: ask ConnectivityManager which transport is actually active THIS
 * tick (TRANSPORT_WIFI vs TRANSPORT_CELLULAR) and attribute the whole
 * tick's totalDelta to that transport. No subtraction, no dependency on
 * two counters staying in sync with each other.
 *
 * Residual limitation: if the active network changes mid-tick (e.g. the
 * device hands off from Wi-Fi to cellular in the middle of a 1-second
 * window), that tick's bytes are attributed to whichever transport is
 * active at the moment we check — a small, unavoidable imprecision of
 * any polling-based approach. If neither Wi-Fi nor cellular is the active
 * transport (e.g. Ethernet, or a VPN with no underlying transport we can
 * read), that tick's bytes are not attributed to either bucket, rather
 * than being incorrectly guessed.
 *
 * Production notes:
 *  - Every external call (TrafficStats, ConnectivityManager, DB, Bitmap
 *    ops, notify()) is wrapped so a single bad reading/OEM quirk can never
 *    crash the whole service.
 *  - DB writes happen on a background executor, never the main thread.
 *  - Survives process death (START_STICKY) but respects explicit user-stop.
 *  - Handles the Android 15+ dataSync foreground-service timeout by
 *    restarting itself rather than silently disappearing.
 */
public class SpeedService extends Service {

    private Handler handler;
    private Runnable speedRunnable;

    private long lastRxBytes;
    private long lastTxBytes;
    private long lastTime;
    private long lastFlushTime;

    private long todayWifiBytes;
    private long todayMobileBytes;

    private long unflushedWifiBytes;
    private long unflushedMobileBytes;

    private String currentDate;

    private NotificationManager notificationManager;
    private ConnectivityManager connectivityManager;
    private UsageDbHelper usageDbHelper;
    private ExecutorService dbExecutor;

    private String lastStatusBarText = null;

    private static SimpleDateFormat newDateFormat() {
        // New instance per call — SimpleDateFormat is not thread-safe and
        // this service touches dates from both the main-thread tick loop
        // and background DB tasks; avoid sharing one instance across them.
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            usageDbHelper = new UsageDbHelper(this);
            dbExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "speed-service-db");
                t.setPriority(Thread.MIN_PRIORITY + 1);
                return t;
            });

            createNotificationChannel();

            currentDate = safeFormatDate(new Date());

            long[] storedToday = usageDbHelper.getUsage(currentDate);
            todayWifiBytes = storedToday[0];
            todayMobileBytes = storedToday[1];

            // Best-effort housekeeping: drop usage rows older than 90 days.
            schedulePrune();

            Notification notification = buildNotificationSafe("0B", "0 B/s", "0 B/s");

            startForegroundCompat(notification);

            startSpeedMonitoring();

        } catch (Exception e) {
            // If setup itself fails, we cannot run as a foreground service
            // safely. Log and stop rather than leaving a half-initialized
            // service running (which risks an ANR/crash loop).
            Log.e(Constants.LOG_TAG, "onCreate failed, stopping service", e);
            stopSelf();
        }
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                        this,
                        Constants.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } else {
                startForeground(Constants.NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // On some OEMs/API levels startForeground can throw
            // (e.g. ForegroundServiceStartNotAllowedException on Android 12+
            // if launched illegally from the background). Nothing useful to
            // recover here — log and stop.
            Log.e(Constants.LOG_TAG, "startForeground failed", e);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);

        try {
            if (intent == null) {
                // System-initiated restart after process death.
                boolean userStopped = prefs.getBoolean(Constants.KEY_USER_STOPPED, false);
                if (userStopped) {
                    Log.i(Constants.LOG_TAG, "Restarted after death but user had stopped it; self-stopping");
                    stopSelf();
                    return START_NOT_STICKY;
                }
                Log.i(Constants.LOG_TAG, "Restarted by system after process death; resuming");
            } else {
                prefs.edit()
                        .putBoolean(Constants.KEY_USER_STOPPED, false)
                        .putBoolean(Constants.KEY_SERVICE_SHOULD_RUN, true)
                        .apply();
            }
        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "onStartCommand: prefs access failed", e);
        }

        return START_STICKY;
    }

    /**
     * Android 15+ (API 35) dataSync foreground services get a cumulative
     * ~6h execution budget while the app is backgrounded. This callback
     * fires instead of a silent kill. We stop cleanly and schedule a
     * restart shortly after so a fresh session begins.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.i(Constants.LOG_TAG, "onTimeout: foreground service time limit reached, restarting");

        flushUsageToDbSafe();
        stopForegroundSafe();
        stopSelf();

        try {
            SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
            boolean shouldRun = prefs.getBoolean(Constants.KEY_SERVICE_SHOULD_RUN, false);
            if (!shouldRun) {
                return; // user had already stopped it; don't resurrect
            }
        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "onTimeout: prefs read failed, skipping restart", e);
            return;
        }

        final Intent restartIntent = new Intent(getApplicationContext(), SpeedService.class);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                ContextCompat.startForegroundService(getApplicationContext(), restartIntent);
            } catch (Exception e) {
                Log.e(Constants.LOG_TAG, "onTimeout: restart failed", e);
            }
        }, Constants.TIMEOUT_RESTART_DELAY_MS);
    }

    @Override
    public void onDestroy() {
        Log.i(Constants.LOG_TAG, "onDestroy");

        if (handler != null && speedRunnable != null) {
            try {
                handler.removeCallbacks(speedRunnable);
            } catch (Exception e) {
                Log.e(Constants.LOG_TAG, "onDestroy: removeCallbacks failed", e);
            }
        }

        flushUsageToDbSafe();

        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            try {
                dbExecutor.shutdown();
            } catch (Exception e) {
                Log.e(Constants.LOG_TAG, "onDestroy: executor shutdown failed", e);
            }
        }

        handler = null;
        speedRunnable = null;

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------
    // Speed monitoring
    // ---------------------------------------------------------------

    private void startSpeedMonitoring() {

        try {
            lastRxBytes = TrafficStats.getTotalRxBytes();
            lastTxBytes = TrafficStats.getTotalTxBytes();
        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "startSpeedMonitoring: initial TrafficStats read failed", e);
            lastRxBytes = 0;
            lastTxBytes = 0;
        }

        lastTime = System.currentTimeMillis();
        lastFlushTime = lastTime;

        handler = new Handler(Looper.getMainLooper());

        speedRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    measureSpeed();
                } catch (Exception e) {
                    // A single failed tick must never kill the polling loop.
                    Log.e(Constants.LOG_TAG, "measureSpeed tick failed, continuing", e);
                } finally {
                    if (handler != null) {
                        handler.postDelayed(this, Constants.UPDATE_INTERVAL_MS);
                    }
                }
            }
        };

        handler.post(speedRunnable);
    }

    private void measureSpeed() {

        long currentRxBytes;
        long currentTxBytes;

        try {
            currentRxBytes = TrafficStats.getTotalRxBytes();
            currentTxBytes = TrafficStats.getTotalTxBytes();
        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "measureSpeed: TrafficStats read failed", e);
            updateNotificationSafe("0B", "Unavailable", "Unavailable");
            return;
        }

        long currentTime = System.currentTimeMillis();

        if (currentRxBytes == TrafficStats.UNSUPPORTED || currentTxBytes == TrafficStats.UNSUPPORTED) {
            updateNotificationSafe("0B", "Unavailable", "Unavailable");
            resetCounters(currentRxBytes, currentTxBytes, currentTime);
            return;
        }

        if (currentRxBytes < lastRxBytes || currentTxBytes < lastTxBytes) {
            // Counter reset (reboot, or a 32-bit rollover on old devices).
            resetCounters(currentRxBytes, currentTxBytes, currentTime);
            return;
        }

        long elapsed = currentTime - lastTime;
        if (elapsed <= 0) {
            return;
        }

        long rxBytes = currentRxBytes - lastRxBytes;
        long txBytes = currentTxBytes - lastTxBytes;

        double downloadBytesPerSecond = (rxBytes * 1000.0) / elapsed;
        double uploadBytesPerSecond = (txBytes * 1000.0) / elapsed;

        long totalDelta = rxBytes + txBytes;

        // --- Wi-Fi vs Mobile split: ask the OS which transport is active ---
        int transport = getActiveTransportSafe();

        long wifiDelta = 0;
        long mobileDelta = 0;

        if (transport == TRANSPORT_WIFI) {
            wifiDelta = totalDelta;
        } else if (transport == TRANSPORT_CELLULAR) {
            mobileDelta = totalDelta;
        }
        // transport == TRANSPORT_UNKNOWN (Ethernet, VPN with no readable
        // underlying transport, or no active network): this tick's bytes
        // are not attributed to either bucket rather than guessed.

        lastRxBytes = currentRxBytes;
        lastTxBytes = currentTxBytes;
        lastTime = currentTime;

        String today = safeFormatDate(new Date());
        if (today != null && !today.equals(currentDate)) {
            flushUsageToDbSafe();
            currentDate = today;
            todayWifiBytes = 0;
            todayMobileBytes = 0;
        }

        todayWifiBytes += wifiDelta;
        todayMobileBytes += mobileDelta;
        unflushedWifiBytes += wifiDelta;
        unflushedMobileBytes += mobileDelta;

        if (currentTime - lastFlushTime >= Constants.DB_FLUSH_INTERVAL_MS) {
            flushUsageToDbSafe();
            lastFlushTime = currentTime;
        }

        String download = formatSpeed(downloadBytesPerSecond);
        String upload = formatSpeed(uploadBytesPerSecond);
        String shortDownload = formatShortSpeed(downloadBytesPerSecond);

        updateNotificationSafe(shortDownload, download, upload);
    }

    private void resetCounters(long rx, long tx, long time) {
        lastRxBytes = rx;
        lastTxBytes = tx;
        lastTime = time;
    }

    // ---------------------------------------------------------------
    // Active-network transport detection
    // ---------------------------------------------------------------

    private static final int TRANSPORT_WIFI = 1;
    private static final int TRANSPORT_CELLULAR = 2;
    private static final int TRANSPORT_UNKNOWN = 0;

    /**
     * Returns which transport is carrying the device's active default
     * network right now: TRANSPORT_WIFI, TRANSPORT_CELLULAR, or
     * TRANSPORT_UNKNOWN if neither (or unreadable). Never throws.
     */
    private int getActiveTransportSafe() {
        try {
            if (connectivityManager == null) {
                return TRANSPORT_UNKNOWN;
            }

            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return TRANSPORT_UNKNOWN;
            }

            NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(activeNetwork);
            if (capabilities == null) {
                return TRANSPORT_UNKNOWN;
            }

            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return TRANSPORT_WIFI;
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return TRANSPORT_CELLULAR;
            }

            return TRANSPORT_UNKNOWN;

        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "getActiveTransportSafe failed", e);
            return TRANSPORT_UNKNOWN;
        }
    }

    // ---------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------

    private void flushUsageToDbSafe() {

        if (unflushedWifiBytes <= 0 && unflushedMobileBytes <= 0) {
            return;
        }
        if (usageDbHelper == null || dbExecutor == null) {
            return;
        }

        final long wifiDelta = unflushedWifiBytes;
        final long mobileDelta = unflushedMobileBytes;
        final String date = currentDate;

        unflushedWifiBytes = 0;
        unflushedMobileBytes = 0;

        try {
            dbExecutor.execute(() -> {
                try {
                    usageDbHelper.addUsage(date, wifiDelta, mobileDelta);
                } catch (Exception e) {
                    Log.e(Constants.LOG_TAG, "Background DB write failed", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // Executor already shut down (service tearing down) — data for
            // this small window is lost, which is an acceptable tradeoff
            // versus blocking shutdown or crashing.
            Log.w(Constants.LOG_TAG, "DB flush rejected, executor shut down", e);
        }
    }

    private void schedulePrune() {
        if (usageDbHelper == null || dbExecutor == null) {
            return;
        }
        try {
            dbExecutor.execute(() -> {
                try {
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.DAY_OF_YEAR, -90);
                    String cutoff = safeFormatDate(cal.getTime());
                    usageDbHelper.pruneOlderThan(cutoff);
                } catch (Exception e) {
                    Log.e(Constants.LOG_TAG, "Prune task failed", e);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Non-critical housekeeping; safe to skip silently.
        }
    }

    private String safeFormatDate(Date date) {
        try {
            return newDateFormat().format(date);
        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "Date formatting failed", e);
            return currentDate; // fall back to last known good value
        }
    }

    // ---------------------------------------------------------------
    // Formatting
    // ---------------------------------------------------------------

    private String formatSpeed(double bytesPerSecond) {
        try {
            if (bytesPerSecond < 1024) {
                return String.format(Locale.US, "%.0f B/s", bytesPerSecond);
            } else if (bytesPerSecond < 1024 * 1024) {
                return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0);
            } else if (bytesPerSecond < 1024 * 1024 * 1024) {
                return String.format(Locale.US, "%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0));
            } else {
                return String.format(Locale.US, "%.2f GB/s", bytesPerSecond / (1024.0 * 1024.0 * 1024.0));
            }
        } catch (Exception e) {
            return "0 B/s";
        }
    }

    private String formatShortSpeed(double bytesPerSecond) {
        try {
            if (bytesPerSecond < 1024) {
                return String.format(Locale.US, "%.0fB", bytesPerSecond);
            }
            double kb = bytesPerSecond / 1024.0;
            if (kb < 1000) {
                return String.format(Locale.US, "%.0fK", kb);
            }
            double mb = kb / 1024.0;
            if (mb < 1000) {
                return String.format(Locale.US, "%.0fM", mb);
            }
            double gb = mb / 1024.0;
            return String.format(Locale.US, "%.0fG", gb);
        } catch (Exception e) {
            return "0B";
        }
    }

    private String formatDataSize(long bytes) {
        try {
            double kb = bytes / 1024.0;
            if (kb < 1024) {
                return String.format(Locale.US, "%.0f KB", kb);
            }
            double mb = kb / 1024.0;
            if (mb < 1024) {
                return String.format(Locale.US, "%.1f MB", mb);
            }
            double gb = mb / 1024.0;
            return String.format(Locale.US, "%.2f GB", gb);
        } catch (Exception e) {
            return "0 KB";
        }
    }

    // ---------------------------------------------------------------
    // Notification building
    // ---------------------------------------------------------------

    private Notification buildNotificationSafe(String statusBarText, String download, String upload) {
        try {
            return buildNotification(statusBarText, download, upload);
        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "buildNotification failed, using fallback", e);
            return buildFallbackNotification();
        }
    }

    private Notification buildNotification(String statusBarText, String download, String upload) {

        Bitmap bitmap = createStatusBarTextBitmap(statusBarText);
        IconCompat textIcon = IconCompat.createWithBitmap(bitmap);

        String contentText = "\u2193 " + download + "    \u2191 " + upload;

        String todayWifiStr = formatDataSize(todayWifiBytes);
        String todayMobileStr = formatDataSize(todayMobileBytes);
        String todayTotalStr = formatDataSize(todayWifiBytes + todayMobileBytes);

        String bigText =
                "Download: " + download + "\nUpload: " + upload +
                        "\n\nToday's usage:" +
                        "\nWi-Fi: " + todayWifiStr +
                        "\nMobile: " + todayMobileStr +
                        "\nTotal: " + todayTotalStr;

        return new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setSmallIcon(textIcon)
                .setContentTitle("Internet Speed")
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * Minimal notification using a built-in system icon, used only if
     * the custom text-bitmap icon generation throws for any reason.
     * A foreground service MUST have a notification, so this must not fail.
     */
    private Notification buildFallbackNotification() {
        return new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Internet Speed")
                .setContentText("Monitoring network usage")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private Bitmap createStatusBarTextBitmap(String text) {

        final int bitmapSize = 108;

        Bitmap bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);

        float maxWidth = bitmapSize * 0.92f;

        float textSize = bitmapSize * 0.8f;
        paint.setTextSize(textSize);

        String worstCase = "999K";
        while (paint.measureText(worstCase) > maxWidth && textSize > 20f) {
            textSize -= 1f;
            paint.setTextSize(textSize);
        }

        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float x = bitmapSize / 2f;
        float y = bitmapSize / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f;

        String safeText = (text == null) ? "0B" : text;
        canvas.drawText(safeText, x, y, paint);
        return bitmap;
    }

    private void updateNotificationSafe(String statusBarText, String download, String upload) {

        if (statusBarText != null && statusBarText.equals(lastStatusBarText)) {
            return;
        }

        try {
            Notification notification = buildNotificationSafe(statusBarText, download, upload);
            if (notificationManager != null) {
                notificationManager.notify(Constants.NOTIFICATION_ID, notification);
                lastStatusBarText = statusBarText;
            }
        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "notify() failed", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                        Constants.CHANNEL_ID,
                        "Internet Speed Meter",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Displays current internet speed and today's data usage");
                channel.setSound(null, null);
                channel.setShowBadge(false);

                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                Log.e(Constants.LOG_TAG, "createNotificationChannel failed", e);
            }
        }
    }

    private void stopForegroundSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "stopForeground failed", e);
        }
    }
}