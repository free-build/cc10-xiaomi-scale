package com.codex.xiaomiscale;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ScaleDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "xiaomi_scale.db";
    private static final int DB_VERSION = 3;

    public static final class User {
        public final long id;
        public final String name;
        public final float minKg;
        public final float maxKg;
        public final String gender;
        public final int birthYear;
        public final float heightCm;
        public final float targetKg;

        User(long id, String name, float minKg, float maxKg, String gender,
             int birthYear, float heightCm, float targetKg) {
            this.id = id;
            this.name = name;
            this.minKg = minKg;
            this.maxKg = maxKg;
            this.gender = gender;
            this.birthYear = birthYear;
            this.heightCm = heightCm;
            this.targetKg = targetKg;
        }

        public String rangeText() {
            return String.format(Locale.CHINA, "%s    %.1f–%.1f kg", name, minKg, maxKg);
        }
    }

    public static final class Record {
        public final long id;
        public final long userId;
        public final long measuredAt;
        public final float displayWeight;
        public final String unit;
        public final float weightKg;
        public final String source;
        public final String userName;
        public final Float changeKg;

        Record(long id, long userId, long measuredAt, float displayWeight, String unit,
               float weightKg, String source, String userName, Float changeKg) {
            this.id = id;
            this.userId = userId;
            this.measuredAt = measuredAt;
            this.displayWeight = displayWeight;
            this.unit = unit;
            this.weightKg = weightKg;
            this.source = source;
            this.userName = userName;
            this.changeKg = changeKg;
        }

        public String displayLine() {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date(measuredAt));
            String sourceText = "history".equals(source) ? "秤内同步" : "实时测量";
            String change = changeKg == null ? "" : "    " + formatChange(changeKg, unit);
            return String.format(Locale.CHINA, "%s    %s\n%.2f %s%s    %s",
                    time, userName, displayWeight, unit, change, sourceText);
        }
    }

    public static final class TrendPoint {
        public final long measuredAt;
        public final float weightKg;
        public final String unit;

        TrendPoint(long measuredAt, float weightKg, String unit) {
            this.measuredAt = measuredAt;
            this.weightKg = weightKg;
            this.unit = unit;
        }
    }

    public ScaleDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createUsers(db);
        db.execSQL("CREATE TABLE measurements (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "device_address TEXT NOT NULL," +
                "measured_at INTEGER NOT NULL," +
                "weight_kg REAL NOT NULL," +
                "display_weight REAL NOT NULL," +
                "unit TEXT NOT NULL," +
                "raw_hex TEXT NOT NULL," +
                "source TEXT NOT NULL," +
                "unique_key TEXT NOT NULL UNIQUE," +
                "user_id INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_measurements_time ON measurements(measured_at DESC)");
        db.execSQL("CREATE INDEX idx_measurements_user_time ON measurements(user_id, measured_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createUsers(db);
            db.execSQL("ALTER TABLE measurements ADD COLUMN user_id INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_measurements_user_time " +
                    "ON measurements(user_id, measured_at DESC)");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE users ADD COLUMN gender TEXT NOT NULL DEFAULT '未设置'");
            db.execSQL("ALTER TABLE users ADD COLUMN birth_year INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE users ADD COLUMN height_cm REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE users ADD COLUMN target_kg REAL NOT NULL DEFAULT 0");
        }
    }

    private void createUsers(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "min_kg REAL NOT NULL," +
                "max_kg REAL NOT NULL," +
                "gender TEXT NOT NULL DEFAULT '未设置'," +
                "birth_year INTEGER NOT NULL DEFAULT 0," +
                "height_cm REAL NOT NULL DEFAULT 0," +
                "target_kg REAL NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)");
    }

    public boolean insert(String address, MiScaleParser.Result result, String source) {
        return insert(address, result, source, 0);
    }

    public boolean insert(String address, MiScaleParser.Result result, String source,
                          int mergeSeconds) {
        if (result == null || !result.stable || result.removed) return false;
        long measuredAt = result.measurementTimeMillis > 0
                ? result.measurementTimeMillis : System.currentTimeMillis();
        String safeAddress = address == null ? "unknown" : address;
        String uniqueKey = result.measurementTimeMillis > 0
                ? String.format(Locale.US, "%s|%d|%.3f", safeAddress, measuredAt, result.weightKg)
                : safeAddress + "|" + result.rawHex;
        long userId = findUserId(result.weightKg);
        ContentValues values = new ContentValues();
        values.put("device_address", safeAddress);
        values.put("measured_at", measuredAt);
        values.put("weight_kg", result.weightKg);
        values.put("display_weight", result.displayWeight);
        values.put("unit", result.sourceUnit);
        values.put("raw_hex", result.rawHex);
        values.put("source", source);
        values.put("unique_key", uniqueKey);
        values.put("user_id", userId);
        values.put("created_at", System.currentTimeMillis());
        if (mergeSeconds > 0 && "live".equals(source)) {
            Cursor recent = getReadableDatabase().query("measurements", new String[]{"id"},
                    "user_id=? AND measured_at>=?", new String[]{Long.toString(userId),
                            Long.toString(measuredAt - mergeSeconds * 1000L)},
                    null, null, "measured_at DESC", "1");
            try {
                if (recent.moveToFirst()) {
                    values.remove("unique_key");
                    values.remove("created_at");
                    return getWritableDatabase().update("measurements", values, "id=?",
                            new String[]{Long.toString(recent.getLong(0))}) > 0;
                }
            } finally { recent.close(); }
        }
        return getWritableDatabase().insertWithOnConflict("measurements", null, values,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public long addManualRecord(long userId, long measuredAt, float weightKg, String unit) {
        ContentValues values = new ContentValues();
        values.put("device_address", "manual");
        values.put("measured_at", measuredAt);
        values.put("weight_kg", weightKg);
        values.put("display_weight", convertFromKg(weightKg, unit));
        values.put("unit", unit);
        values.put("raw_hex", "");
        values.put("source", "manual");
        values.put("unique_key", "manual|" + measuredAt + "|" + System.nanoTime());
        values.put("user_id", userId);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("measurements", null, values);
    }

    public boolean updateRecord(long id, long userId, long measuredAt, float weightKg,
                                String unit) {
        ContentValues values = new ContentValues();
        values.put("user_id", userId);
        values.put("measured_at", measuredAt);
        values.put("weight_kg", weightKg);
        values.put("display_weight", convertFromKg(weightKg, unit));
        values.put("unit", unit);
        values.put("source", "manual");
        return getWritableDatabase().update("measurements", values, "id=?",
                new String[]{Long.toString(id)}) > 0;
    }

    public boolean deleteRecord(long id) {
        return getWritableDatabase().delete("measurements", "id=?",
                new String[]{Long.toString(id)}) > 0;
    }

    public int count() {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM measurements", null);
        try { return cursor.moveToFirst() ? cursor.getInt(0) : 0; }
        finally { cursor.close(); }
    }

    public long addUser(String name, float minKg, float maxKg) {
        return addUser(name, minKg, maxKg, "未设置", 0, 0, 0);
    }

    public long addUser(String name, float minKg, float maxKg, String gender,
                        int birthYear, float heightCm, float targetKg) {
        ContentValues values = userValues(name, minKg, maxKg, gender,
                birthYear, heightCm, targetKg);
        values.put("created_at", System.currentTimeMillis());
        long id = getWritableDatabase().insert("users", null, values);
        reassignAll();
        return id;
    }

    public void updateUser(long id, String name, float minKg, float maxKg) {
        User current = user(id);
        updateUser(id, name, minKg, maxKg,
                current == null ? "未设置" : current.gender,
                current == null ? 0 : current.birthYear,
                current == null ? 0 : current.heightCm,
                current == null ? 0 : current.targetKg);
    }

    public void updateUser(long id, String name, float minKg, float maxKg, String gender,
                           int birthYear, float heightCm, float targetKg) {
        getWritableDatabase().update("users", userValues(name, minKg, maxKg, gender,
                        birthYear, heightCm, targetKg), "id=?",
                new String[]{Long.toString(id)});
        reassignAll();
    }

    private ContentValues userValues(String name, float minKg, float maxKg, String gender,
                                     int birthYear, float heightCm, float targetKg) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("min_kg", minKg);
        values.put("max_kg", maxKg);
        values.put("gender", gender == null ? "未设置" : gender);
        values.put("birth_year", birthYear);
        values.put("height_cm", heightCm);
        values.put("target_kg", targetKg);
        return values;
    }

    public void deleteUser(long id) {
        ContentValues values = new ContentValues();
        values.put("user_id", 0);
        getWritableDatabase().update("measurements", values, "user_id=?",
                new String[]{Long.toString(id)});
        getWritableDatabase().delete("users", "id=?", new String[]{Long.toString(id)});
        reassignAll();
    }

    public List<User> users() {
        ArrayList<User> result = new ArrayList<User>();
        Cursor cursor = getReadableDatabase().query("users",
                new String[]{"id", "name", "min_kg", "max_kg", "gender",
                        "birth_year", "height_cm", "target_kg"},
                null, null, null, null, "id ASC");
        try {
            while (cursor.moveToNext()) {
                result.add(readUser(cursor));
            }
        } finally { cursor.close(); }
        return result;
    }

    public void reassignAll() {
        SQLiteDatabase db = getWritableDatabase();
        Cursor cursor = db.query("measurements", new String[]{"id", "weight_kg"},
                null, null, null, null, null);
        db.beginTransaction();
        try {
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                values.put("user_id", findUserId(cursor.getFloat(1)));
                db.update("measurements", values, "id=?",
                        new String[]{Long.toString(cursor.getLong(0))});
            }
            db.setTransactionSuccessful();
        } finally {
            cursor.close();
            db.endTransaction();
        }
    }

    private long findUserId(float weightKg) {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id FROM users WHERE ? BETWEEN min_kg AND max_kg " +
                        "ORDER BY ABS(((min_kg + max_kg) / 2.0) - ?) ASC, id ASC LIMIT 1",
                new String[]{Float.toString(weightKg), Float.toString(weightKg)});
        try { return cursor.moveToFirst() ? cursor.getLong(0) : 0L; }
        finally { cursor.close(); }
    }

    public User matchedUser(float weightKg) {
        long id = findUserId(weightKg);
        if (id == 0) return null;
        Cursor cursor = getReadableDatabase().query("users",
                new String[]{"id", "name", "min_kg", "max_kg", "gender",
                        "birth_year", "height_cm", "target_kg"}, "id=?",
                new String[]{Long.toString(id)}, null, null, null);
        try {
            return cursor.moveToFirst() ? readUser(cursor) : null;
        } finally { cursor.close(); }
    }

    public User user(long id) {
        Cursor cursor = getReadableDatabase().query("users",
                new String[]{"id", "name", "min_kg", "max_kg", "gender",
                        "birth_year", "height_cm", "target_kg"}, "id=?",
                new String[]{Long.toString(id)}, null, null, null);
        try { return cursor.moveToFirst() ? readUser(cursor) : null; }
        finally { cursor.close(); }
    }

    private User readUser(Cursor cursor) {
        return new User(cursor.getLong(0), cursor.getString(1), cursor.getFloat(2),
                cursor.getFloat(3), cursor.getString(4), cursor.getInt(5),
                cursor.getFloat(6), cursor.getFloat(7));
    }

    public List<Record> latest(int limit) {
        return latestForUser(-1L, limit);
    }

    public List<Record> latestForUser(long userId, int limit) {
        ArrayList<Record> records = new ArrayList<Record>();
        String sql = "SELECT m.id,m.user_id,m.measured_at,m.display_weight,m.unit,m.weight_kg,m.source," +
                "COALESCE(u.name,'未识别')," +
                "m.weight_kg-(SELECT p.weight_kg FROM measurements p " +
                "WHERE p.user_id=m.user_id AND p.measured_at<m.measured_at " +
                "ORDER BY p.measured_at DESC LIMIT 1) " +
                "FROM measurements m LEFT JOIN users u ON u.id=m.user_id " +
                (userId >= 0 ? "WHERE m.user_id=? " : "") +
                "ORDER BY m.measured_at DESC LIMIT ?";
        String[] args = userId >= 0
                ? new String[]{Long.toString(userId), Integer.toString(limit)}
                : new String[]{Integer.toString(limit)};
        Cursor cursor = getReadableDatabase().rawQuery(sql, args);
        try {
            while (cursor.moveToNext()) {
                Float change = cursor.isNull(8) ? null : cursor.getFloat(8);
                records.add(new Record(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2),
                        cursor.getFloat(3), cursor.getString(4), cursor.getFloat(5),
                        cursor.getString(6), cursor.getString(7), change));
            }
        } finally { cursor.close(); }
        return records;
    }

    public List<TrendPoint> trend(long userId, long sinceMillis) {
        ArrayList<TrendPoint> points = new ArrayList<TrendPoint>();
        String where = "measured_at>=?";
        ArrayList<String> args = new ArrayList<String>();
        args.add(Long.toString(sinceMillis));
        if (userId >= 0) {
            where += " AND user_id=?";
            args.add(Long.toString(userId));
        }
        Cursor cursor = getReadableDatabase().query("measurements",
                new String[]{"measured_at", "weight_kg", "unit"}, where,
                args.toArray(new String[args.size()]), null, null, "measured_at ASC", "1000");
        try {
            while (cursor.moveToNext()) {
                points.add(new TrendPoint(cursor.getLong(0), cursor.getFloat(1), cursor.getString(2)));
            }
        } finally { cursor.close(); }
        return points;
    }

    public Float latestChangeKg(long userId) {
        String where = userId >= 0 ? "user_id=?" : null;
        String[] args = userId >= 0 ? new String[]{Long.toString(userId)} : null;
        Cursor cursor = getReadableDatabase().query("measurements", new String[]{"weight_kg"},
                where, args, null, null, "measured_at DESC", "2");
        try {
            if (!cursor.moveToFirst()) return null;
            float latest = cursor.getFloat(0);
            if (!cursor.moveToNext()) return null;
            return latest - cursor.getFloat(0);
        } finally { cursor.close(); }
    }

    public static float convertFromKg(float kg, String unit) {
        if ("斤".equals(unit)) return kg * 2.0f;
        if ("lb".equals(unit)) return kg / 0.45359237f;
        return kg;
    }

    public static String formatChange(float changeKg, String unit) {
        float value = convertFromKg(changeKg, unit);
        return String.format(Locale.CHINA, "较上次 %+.2f %s", value, unit);
    }
}
