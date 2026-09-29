package com.example.testcrmnetchecking;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * Persists per-day Wi-Fi / mobile usage totals.
 *
 * All public methods swallow SQLiteException internally and log it —
 * a failed DB write should never crash the foreground service. Callers
 * get a best-effort result (0 on read failure, silent no-op on write failure).
 */
public class UsageDbHelper extends SQLiteOpenHelper {

    public UsageDbHelper(Context context) {
        super(context.getApplicationContext(), Constants.DB_NAME, null, Constants.DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + Constants.TABLE_DAILY_USAGE + " (" +
                Constants.COL_DATE + " TEXT PRIMARY KEY, " +
                Constants.COL_WIFI_BYTES + " INTEGER NOT NULL DEFAULT 0, " +
                Constants.COL_MOBILE_BYTES + " INTEGER NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Add ALTER TABLE migrations here as the schema evolves.
        // Never DROP+recreate in production — that destroys user data.
        // Example for a future version 2 adding a column:
        // if (oldVersion < 2) {
        //     db.execSQL("ALTER TABLE " + Constants.TABLE_DAILY_USAGE +
        //             " ADD COLUMN some_new_col INTEGER NOT NULL DEFAULT 0");
        // }
        Log.w(Constants.LOG_TAG, "onUpgrade called but no migration defined for "
                + oldVersion + " -> " + newVersion);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(false);
        try {
            db.enableWriteAheadLogging(); // better concurrent read/write behavior
        } catch (Exception e) {
            Log.w(Constants.LOG_TAG, "WAL mode not enabled: " + e.getMessage());
        }
    }

    /**
     * Adds (increments) usage for the given date. Creates the row if needed.
     * Never throws — logs and returns silently on failure so the caller
     * (foreground service) is never at risk of crashing on a DB error.
     */
    public synchronized void addUsage(String date, long wifiDeltaBytes, long mobileDeltaBytes) {

        if (date == null) {
            Log.e(Constants.LOG_TAG, "addUsage called with null date, ignoring");
            return;
        }

        if (wifiDeltaBytes <= 0 && mobileDeltaBytes <= 0) {
            return;
        }

        SQLiteDatabase db;
        try {
            db = getWritableDatabase();
        } catch (SQLiteException e) {
            Log.e(Constants.LOG_TAG, "addUsage: failed to open writable DB", e);
            return;
        }

        try {
            db.beginTransaction();

            ContentValues initial = new ContentValues();
            initial.put(Constants.COL_DATE, date);
            initial.put(Constants.COL_WIFI_BYTES, 0);
            initial.put(Constants.COL_MOBILE_BYTES, 0);

            db.insertWithOnConflict(
                    Constants.TABLE_DAILY_USAGE,
                    null,
                    initial,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            db.execSQL(
                    "UPDATE " + Constants.TABLE_DAILY_USAGE + " SET " +
                            Constants.COL_WIFI_BYTES + " = " + Constants.COL_WIFI_BYTES + " + ?, " +
                            Constants.COL_MOBILE_BYTES + " = " + Constants.COL_MOBILE_BYTES + " + ? " +
                            "WHERE " + Constants.COL_DATE + " = ?",
                    new Object[]{wifiDeltaBytes, mobileDeltaBytes, date}
            );

            db.setTransactionSuccessful();

        } catch (SQLiteException e) {
            Log.e(Constants.LOG_TAG, "addUsage: DB write failed for date=" + date, e);
        } finally {
            try {
                db.endTransaction();
            } catch (Exception e) {
                Log.e(Constants.LOG_TAG, "addUsage: endTransaction failed", e);
            }
        }
    }

    /**
     * Returns {wifiBytes, mobileBytes} for the given date, or {0, 0}
     * if nothing recorded yet or a DB error occurs.
     */
    public synchronized long[] getUsage(String date) {

        long[] result = new long[]{0L, 0L};

        if (date == null) {
            return result;
        }

        SQLiteDatabase db;
        try {
            db = getReadableDatabase();
        } catch (SQLiteException e) {
            Log.e(Constants.LOG_TAG, "getUsage: failed to open readable DB", e);
            return result;
        }

        Cursor cursor = null;
        try {
            cursor = db.query(
                    Constants.TABLE_DAILY_USAGE,
                    new String[]{Constants.COL_WIFI_BYTES, Constants.COL_MOBILE_BYTES},
                    Constants.COL_DATE + " = ?",
                    new String[]{date},
                    null, null, null
            );

            if (cursor != null && cursor.moveToFirst()) {
                result[0] = cursor.getLong(0);
                result[1] = cursor.getLong(1);
            }

        } catch (SQLiteException e) {
            Log.e(Constants.LOG_TAG, "getUsage: DB read failed for date=" + date, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return result;
    }

    /**
     * Deletes usage rows older than the given date (exclusive), to keep
     * the DB from growing unbounded. Call this occasionally (e.g. once
     * per service start), not on every tick.
     */
    public synchronized void pruneOlderThan(String cutoffDateExclusive) {

        if (cutoffDateExclusive == null) {
            return;
        }

        SQLiteDatabase db;
        try {
            db = getWritableDatabase();
        } catch (SQLiteException e) {
            Log.e(Constants.LOG_TAG, "pruneOlderThan: failed to open writable DB", e);
            return;
        }

        try {
            int deleted = db.delete(
                    Constants.TABLE_DAILY_USAGE,
                    Constants.COL_DATE + " < ?",
                    new String[]{cutoffDateExclusive}
            );
            if (deleted > 0) {
                Log.i(Constants.LOG_TAG, "Pruned " + deleted + " old usage rows");
            }
        } catch (SQLiteException e) {
            Log.e(Constants.LOG_TAG, "pruneOlderThan: delete failed", e);
        }
    }
}