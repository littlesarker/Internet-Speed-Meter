package com.example.testcrmnetchecking;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_NOTIFICATION = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> startSpeedService());
        btnStop.setOnClickListener(v -> stopSpeedService());

        requestNotificationPermission();
    }

    private void startSpeedService() {

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {

                    Toast.makeText(this, "Please allow notification permission", Toast.LENGTH_LONG).show();
                    requestNotificationPermission();
                    return;
                }
            }

            // Mark intent to run, in case the process/device restarts later.
            SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                    .putBoolean(Constants.KEY_USER_STOPPED, false)
                    .putBoolean(Constants.KEY_SERVICE_SHOULD_RUN, true)
                    .apply();

            Intent intent = new Intent(this, SpeedService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent);
            } else {
                startService(intent);
            }

            Toast.makeText(this, "Speed Meter Started", Toast.LENGTH_SHORT).show();

            // Ask the user to exempt the app from battery optimization —
            // this is the single biggest cause of the service being silently
            // killed by OEM battery managers (MIUI, ColorOS, FuntouchOS, etc).
            requestIgnoreBatteryOptimizations();

        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "startSpeedService failed", e);
            Toast.makeText(this, "Could not start Speed Meter", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopSpeedService() {

        try {
            SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                    .putBoolean(Constants.KEY_USER_STOPPED, true)
                    .putBoolean(Constants.KEY_SERVICE_SHOULD_RUN, false)
                    .apply();

            Intent intent = new Intent(this, SpeedService.class);
            stopService(intent);

            Toast.makeText(this, "Speed Meter Stopped", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(Constants.LOG_TAG, "stopSpeedService failed", e);
        }
    }

    /**
     * Prompts the user with a system dialog to disable battery optimization
     * for this app. Without this, many OEM skins will silently kill the
     * foreground service regardless of what the code does correctly.
     */
    private void requestIgnoreBatteryOptimizations() {
        try {
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivity(intent);
            }
        } catch (Exception e) {
            // Some OEMs restrict this intent or don't support it — not
            // fatal, the app still works, just more likely to be killed.
            Log.w(Constants.LOG_TAG, "requestIgnoreBatteryOptimizations failed", e);
        }
    }

    private void requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_NOTIFICATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Notification permission is required", Toast.LENGTH_LONG).show();
            }
        }
    }
}