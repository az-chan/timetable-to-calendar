package com.ttcal.importer.parse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a "HH:mm-HH:mm" class period. */
public final class TimeRange {

    private static final Pattern PATTERN = Pattern.compile(
            "(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*[-—–~～至]\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})");

    public final int startMinutes;
    public final int endMinutes;

    private TimeRange(int startMinutes, int endMinutes) {
        this.startMinutes = startMinutes;
        this.endMinutes = endMinutes;
    }

    /** @return null when the cell is not a usable time range. */
    public static TimeRange parse(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = PATTERN.matcher(raw.replace(" ", ""));
        if (!m.find()) {
            return null;
        }
        int sh = Integer.parseInt(m.group(1));
        int sm = Integer.parseInt(m.group(2));
        int eh = Integer.parseInt(m.group(3));
        int em = Integer.parseInt(m.group(4));
        if (sh > 23 || eh > 24 || sm > 59 || em > 59) {
            return null;
        }
        int start = sh * 60 + sm;
        int end = eh * 60 + em;
        if (end <= start) {
            return null;
        }
        return new TimeRange(start, end);
    }

    public String label() {
        return pad(startMinutes / 60) + ":" + pad(startMinutes % 60)
                + "-" + pad(endMinutes / 60) + ":" + pad(endMinutes % 60);
    }

    private static String pad(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
