package com.example.testcrmnetchecking;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Restarts SpeedService after a device reboot, but only if the user had
 * it running (not explicitly stopped) before the reboot happened.
 *
 * Requires <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 * and the corresponding <receiver> entry in AndroidManifest.xml (see below).
 */
public class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent == null || intent.getAction() == null) {
            return;
        }

        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
            boolean shouldRun = prefs.getBoolean(Constants.KEY_SERVICE_SHOULD_RUN, false);
            boolean userStopped = prefs.getBoolean(Constants.KEY_USER_STOPPED, false);

            if (shouldRun && !userStopped) {
                Intent serviceIntent = new Intent(context, SpeedService.class);
                ContextCompat.startForegroundService(context, serviceIntent);
                Log.i(Constants.LOG_TAG, "Restarted SpeedService after boot");
            }
        } catch (Exception e) {
            // A broadcast receiver must never throw — the system has no
            // good way to surface that failure to the user anyway.
            Log.e(Constants.LOG_TAG, "BootCompletedReceiver failed", e);
        }
    }
}