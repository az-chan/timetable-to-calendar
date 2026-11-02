package com.ttcal.importer.cal;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import com.ttcal.importer.parse.Occurrence;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/** Writes parsed occurrences into the system calendar. */
public final class EventWriter {

    public static final String TZ = "Asia/Shanghai";
    private static final int BATCH = 80;

    public interface ProgressSink {
        void onProgress(int written, int total, String message);
    }

    public static final class Result {
        public int inserted;
        public int skipped;
        public int failed;
        public final List<String> errors = new ArrayList<String>();

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("已写入 ").append(inserted).append(" 条");
            if (skipped > 0) {
                sb.append("，跳过重复 ").append(skipped).append(" 条");
            }
            if (failed > 0) {
                sb.append("，失败 ").append(failed).append(" 条");
            }
            for (int i = 0; i < errors.size() && i < 5; i++) {
                sb.append("\n· ").append(errors.get(i));
            }
            return sb.toString();
        }
    }

    private EventWriter() {
    }

    public static long millis(java.time.LocalDate date, int minutesOfDay) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(TZ));
        cal.clear();
        cal.set(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth(),
                minutesOfDay / 60, minutesOfDay % 60, 0);
        return cal.getTimeInMillis();
    }

    /**
     * @param remindMinutes minutes before the class to fire a reminder; 0 disables alarms
     */
    public static Result write(ContentResolver cr, long calendarId, List<Occurrence> occurrences,
                               int remindMinutes, boolean skipExisting, ProgressSink sink) {
        Result result = new Result();
        if (occurrences.isEmpty()) {
            return result;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Occurrence o : occurrences) {
            long s = millis(o.date, o.startMinutes);
            if (s < min) {
                min = s;
            }
            if (s > max) {
                max = s;
            }
        }
        Set<String> existing = skipExisting ? existingKeys(cr, calendarId, min, max) : new HashSet<String>();

        List<ContentValues> batch = new ArrayList<ContentValues>();
        List<Occurrence> batchOcc = new ArrayList<Occurrence>();
        int total = occurrences.size();
        int processed = 0;

        for (Occurrence o : occurrences) {
            long start = millis(o.date, o.startMinutes);
            long end = millis(o.date, o.endMinutes);
            if (skipExisting && existing.contains(o.title() + "|" + start)) {
                result.skipped++;
                processed++;
                continue;
            }
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, calendarId);
            v.put(CalendarContract.Events.TITLE, o.title());
            v.put(CalendarContract.Events.DESCRIPTION, o.description());
            // identity lives in metadata, not in the visible text
            v.put(CalendarContract.Events.CUSTOM_APP_URI, o.appUri());
            v.put(CalendarContract.Events.CUSTOM_APP_PACKAGE, Occurrence.APP_PACKAGE);
            v.put(CalendarContract.Events.EVENT_LOCATION, o.location);
            v.put(CalendarContract.Events.DTSTART, start);
            v.put(CalendarContract.Events.DTEND, end);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TZ);
            v.put(CalendarContract.Events.EVENT_END_TIMEZONE, TZ);
            v.put(CalendarContract.Events.ALL_DAY, 0);
            v.put(CalendarContract.Events.HAS_ALARM, remindMinutes > 0 ? 1 : 0);
            v.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY);
            batch.add(v);
            batchOcc.add(o);
            processed++;
            if (batch.size() >= BATCH) {
                flush(cr, batch, batchOcc, remindMinutes, result, sink, processed, total);
                batch.clear();
                batchOcc.clear();
            }
        }
        if (!batch.isEmpty()) {
            flush(cr, batch, batchOcc, remindMinutes, result, sink, processed, total);
        }
        return result;
    }

    private static void flush(ContentResolver cr, List<ContentValues> batch, List<Occurrence> occ,
                              int remindMinutes, Result result, ProgressSink sink,
                              int processed, int total) {
        List<ContentValues> alarms = new ArrayList<ContentValues>();
        for (int i = 0; i < batch.size(); i++) {
            Uri created = null;
            try {
                created = cr.insert(CalendarContract.Events.CONTENT_URI, batch.get(i));
            } catch (Exception e) {
                if (result.errors.size() < 20) {
                    result.errors.add(occ.get(i).previewLine() + " → " + e.getClass().getSimpleName());
                }
            }
            if (created == null) {
                result.failed++;
                continue;
            }
            result.inserted++;
            if (remindMinutes > 0) {
                long id = ContentUris.parseId(created);
                ContentValues r = new ContentValues();
                r.put(CalendarContract.Reminders.EVENT_ID, id);
                r.put(CalendarContract.Reminders.MINUTES, remindMinutes);
                r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
                alarms.add(r);
            }
        }
        if (!alarms.isEmpty()) {
            try {
                cr.bulkInsert(CalendarContract.Reminders.CONTENT_URI,
                        alarms.toArray(new ContentValues[0]));
            } catch (Exception e) {
                if (result.errors.size() < 20) {
                    result.errors.add("提醒写入失败：" + e.getClass().getSimpleName());
                }
            }
        }
        if (sink != null) {
            sink.onProgress(processed, total, "写入中 " + processed + "/" + total);
        }
    }

    private static Set<String> existingKeys(ContentResolver cr, long calendarId, long min, long max) {
        Set<String> keys = new HashSet<String>();
        Cursor c = null;
        try {
            c = cr.query(CalendarContract.Events.CONTENT_URI,
                    new String[]{CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART},
                    CalendarContract.Events.CALENDAR_ID + "=? AND "
                            + CalendarContract.Events.DTSTART + ">=? AND "
                            + CalendarContract.Events.DTSTART + "<=?",
                    new String[]{String.valueOf(calendarId), String.valueOf(min), String.valueOf(max)},
                    null);
            if (c != null) {
                while (c.moveToNext()) {
                    keys.add(c.getString(0) + "|" + c.getLong(1));
                }
            }
        } catch (SecurityException ignored) {
            // treated as "nothing exists yet"
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return keys;
    }
}
