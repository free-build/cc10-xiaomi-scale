package com.codex.xiaomiscale;

import java.util.Locale;
import java.util.Calendar;

public final class MiScaleParser {
    private MiScaleParser() {}

    public static final class Result {
        public final float weightKg;
        public final float displayWeight;
        public final boolean stable;
        public final boolean removed;
        public final String sourceUnit;
        public final String rawHex;
        public final String measurementTime;
        public final long measurementTimeMillis;

        Result(float weightKg, float displayWeight, boolean stable, boolean removed, String sourceUnit,
               String rawHex, String measurementTime, long measurementTimeMillis) {
            this.weightKg = weightKg;
            this.displayWeight = displayWeight;
            this.stable = stable;
            this.removed = removed;
            this.sourceUnit = sourceUnit;
            this.rawHex = rawHex;
            this.measurementTime = measurementTime;
            this.measurementTimeMillis = measurementTimeMillis;
        }
    }

    public static Result parse(byte[] data) {
        if (data == null || data.length != 10) return null;
        int status = data[0] & 0xff;
        int raw = (data[1] & 0xff) | ((data[2] & 0xff) << 8);
        boolean stable = (status & 0x20) != 0;
        boolean removed = (status & 0x80) != 0;

        float kg;
        float displayWeight;
        String unit;
        if ((status & 0x01) != 0) {
            unit = "lb";
            displayWeight = raw / 100.0f;
            kg = displayWeight * 0.45359237f;
        } else if ((status & 0x10) != 0) {
            unit = "斤";
            displayWeight = raw / 100.0f;
            kg = displayWeight * 0.5f;
        } else {
            unit = "kg";
            displayWeight = raw / 200.0f;
            kg = displayWeight;
        }

        String time = "";
        long timeMillis = 0L;
        if (stable && !removed) {
            int year = (data[3] & 0xff) | ((data[4] & 0xff) << 8);
            int month = data[5] & 0xff;
            int day = data[6] & 0xff;
            int hour = data[7] & 0xff;
            int minute = data[8] & 0xff;
            int second = data[9] & 0xff;
            if (year >= 2010 && year <= 2099 && month >= 1 && month <= 12 &&
                    day >= 1 && day <= 31 && hour <= 23 && minute <= 59 && second <= 59) {
                time = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                        year, month, day, hour, minute, second);
                Calendar calendar = Calendar.getInstance();
                calendar.setLenient(false);
                calendar.set(year, month - 1, day, hour, minute, second);
                calendar.set(Calendar.MILLISECOND, 0);
                try { timeMillis = calendar.getTimeInMillis(); }
                catch (IllegalArgumentException ignored) { time = ""; }
            }
        }
        return new Result(kg, displayWeight, stable, removed, unit, toHex(data), time, timeMillis);
    }

    private static String toHex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", data[i] & 0xff));
        }
        return out.toString();
    }
}
