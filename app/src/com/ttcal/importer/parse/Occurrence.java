package com.ttcal.importer.parse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** One class occurrence: a single dated event. */
public final class Occurrence {

    /** Legacy, visible marker used by v1.2 and earlier; kept only for clean-up. */
    public static final String MARKER = "【课表导入App】";
    public static final String APP_PACKAGE = "com.ttcal.importer";

    public final LocalDate date;
    public final int startMinutes;
    public final int endMinutes;
    public final String course;
    public final String teacher;
    public final String location;
    public final String note;
    public final int week;
    public final int weekday;

    public Occurrence(LocalDate date, int startMinutes, int endMinutes, String course,
                      String teacher, String location, String note, int week, int weekday) {
        this.date = date;
        this.startMinutes = startMinutes;
        this.endMinutes = endMinutes;
        this.course = course;
        this.teacher = teacher;
        this.location = location;
        this.note = note;
        this.week = week;
        this.weekday = weekday;
    }

    /** Keeps occurrences on or after {@code from} (system date of the phone). */
    public static List<Occurrence> filterFromDate(List<Occurrence> all, LocalDate from) {
        List<Occurrence> out = new ArrayList<Occurrence>();
        if (all == null || from == null) {
            return out;
        }
        for (Occurrence occurrence : all) {
            if (!occurrence.date.isBefore(from)) {
                out.add(occurrence);
            }
        }
        return out;
    }

    public String title() {
        return course;
    }

    public String timeLabel() {
        return pad(startMinutes / 60) + ":" + pad(startMinutes % 60)
                + "-" + pad(endMinutes / 60) + ":" + pad(endMinutes % 60);
    }

    public String key() {
        return date.toString() + "|" + timeLabel() + "|" + course + "|" + location;
    }

    /**
     * Per-occurrence identity stored in Events.CUSTOM_APP_URI. It is metadata the
     * calendar provider keeps out of the visible event text, unlike a description
     * marker, so the user only sees 教师/周次/备注.
     */
    public String appUri() {
        return "content://" + APP_PACKAGE + "/occ/" + sha1(key());
    }

    private static String sha1(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    public String description() {
        StringBuilder sb = new StringBuilder();
        if (teacher != null && !teacher.isEmpty()) {
            sb.append("教师：").append(teacher).append('\n');
        }
        sb.append("周次：第").append(week).append("周 ").append(Weekday.label(weekday)).append('\n');
        if (note != null && !note.isEmpty()) {
            sb.append("备注：").append(note).append('\n');
        }
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last != (char) 10 && last != ' ') {
                break;
            }
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    public String previewLine() {
        String md = date.getMonthValue() + "-" + date.getDayOfMonth();
        return md + " " + Weekday.label(weekday) + " " + timeLabel() + "  " + course
                + (location.isEmpty() ? "" : " @" + location);
    }

    private static String pad(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
