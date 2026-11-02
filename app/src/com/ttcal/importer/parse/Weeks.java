package com.ttcal.importer.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Chinese "week" expressions such as 1-16, 1,3,7,9,11,13,15, 1-5,7-10, 1（8学时）. */
public final class Weeks {

    public static final int MAX_WEEK = 30;

    private static final Pattern RANGE = Pattern.compile("^(\\d{1,2})\\s*-\\s*(\\d{1,2})$");
    private static final Pattern SINGLE = Pattern.compile("^(\\d{1,2})$");
    private static final Pattern BRACKET = Pattern.compile("[（(][^（()）]*[）)]");

    private Weeks() {
    }

    public static List<Integer> parse(String raw) {
        List<Integer> weeks = new ArrayList<Integer>();
        if (raw == null) {
            return weeks;
        }
        String s = raw;
        // drop annotations such as （8学时）, （智慧黑板）, （1,3周）
        String prev;
        do {
            prev = s;
            s = BRACKET.matcher(s).replaceAll(" ");
        } while (!prev.equals(s));

        s = s.replace('，', ',')
                .replace('、', ',')
                .replace('；', ';')
                .replace('；', ';')
                .replace('–', '-')
                .replace('—', '-')
                .replace('~', '-')
                .replace('～', '-')
                .replace("至", "-")
                .replace("周", " ")
                .replace("星期", " ")
                .replaceAll("[\\s;,]+", ";");

        for (String token : s.split(";")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            Matcher range = RANGE.matcher(t);
            if (range.matches()) {
                int lo = Integer.parseInt(range.group(1));
                int hi = Integer.parseInt(range.group(2));
                if (lo > hi) {
                    int tmp = lo;
                    lo = hi;
                    hi = tmp;
                }
                for (int w = Math.max(lo, 1); w <= Math.min(hi, MAX_WEEK); w++) {
                    add(weeks, w);
                }
                continue;
            }
            Matcher single = SINGLE.matcher(t);
            if (single.matches()) {
                int w = Integer.parseInt(single.group(1));
                if (w >= 1 && w <= MAX_WEEK) {
                    add(weeks, w);
                }
            }
        }
        Collections.sort(weeks);
        return weeks;
    }

    private static void add(List<Integer> weeks, int w) {
        if (!weeks.contains(w)) {
            weeks.add(w);
        }
    }

    public static String format(List<Integer> weeks) {
        if (weeks == null || weeks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < weeks.size()) {
            int start = weeks.get(i);
            int end = start;
            while (i + 1 < weeks.size() && weeks.get(i + 1) == end + 1) {
                i++;
                end = weeks.get(i);
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            if (start == end) {
                sb.append(start);
            } else {
                sb.append(start).append('-').append(end);
            }
            i++;
        }
        return sb.toString();
    }
}
