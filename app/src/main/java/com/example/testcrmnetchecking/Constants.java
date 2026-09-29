package com.example.testcrmnetchecking;

public final class Constants {

    private Constants() {
        // no instances
    }

    // Notification
    public static final String CHANNEL_ID = "SpeedMeterChannel";
    public static final int NOTIFICATION_ID = 101;

    // Timing
    public static final long UPDATE_INTERVAL_MS = 1000L;
    public static final long DB_FLUSH_INTERVAL_MS = 10_000L;
    public static final long TIMEOUT_RESTART_DELAY_MS = 2000L;

    // Sanity limits
    public static final long MAX_PLAUSIBLE_BYTES_PER_TICK = 100L * 1024 * 1024; // 100MB/tick ceiling

    // SharedPreferences
    public static final String PREFS_NAME = "speed_service_prefs";
    public static final String KEY_USER_STOPPED = "user_stopped";
    public static final String KEY_SERVICE_SHOULD_RUN = "service_should_run"; // for boot restart

    // Database
    public static final String DB_NAME = "usage_stats.db";
    public static final int DB_VERSION = 1;
    public static final String TABLE_DAILY_USAGE = "daily_usage";
    public static final String COL_DATE = "date";
    public static final String COL_WIFI_BYTES = "wifi_bytes";
    public static final String COL_MOBILE_BYTES = "mobile_bytes";

    public static final String LOG_TAG = "SpeedMeter";
}