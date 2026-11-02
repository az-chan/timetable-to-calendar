package com.ttcal.importer.parse;

/** Maps Chinese weekday labels to ISO numbers (1 = Monday .. 7 = Sunday). */
public final class Weekday {

    private static final String CHINESE = "一二三四五六日";

    private Weekday() {
    }

    /** @return 1..7, or -1 when unrecognised. */
    public static int parse(String raw) {
        if (raw == null) {
            return -1;
        }
        String s = raw.trim()
                .replace("星期", "")
                .replace("礼拜", "")
                .replace("周", "")
                .replace("曜日", "")
                .replaceAll("\\s+", "");
        if (s.isEmpty()) {
            return -1;
        }
        if (s.indexOf('日') >= 0 || s.indexOf('天') >= 0 || s.equals("7") || s.equals("07")) {
            return 7;
        }
        for (int i = 0; i < CHINESE.length(); i++) {
            if (s.indexOf(CHINESE.charAt(i)) >= 0 && i < 6) {
                return i + 1;
            }
        }
        if (s.length() == 1 && s.charAt(0) >= '1' && s.charAt(0) <= '7') {
            return s.charAt(0) - '0';
        }
        if (s.startsWith("mon", 0) || s.equalsIgnoreCase("mon")) {
            return 1;
        }
        return -1;
    }

    public static String label(int isoWeekday) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return (isoWeekday >= 1 && isoWeekday <= 7) ? names[isoWeekday - 1] : "?";
    }
}
