package com.ttcal.importer.cal;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import com.ttcal.importer.parse.Occurrence;

import java.util.ArrayList;
import java.util.List;

/** Thin wrapper around the system calendar provider. */
public final class CalendarRepo {

    private CalendarRepo() {
    }

    public static List<CalendarOption> writable(ContentResolver cr) {
        List<CalendarOption> list = new ArrayList<CalendarOption>();
        String[] projection = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE
        };
        Cursor c = null;
        try {
            c = cr.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null);
            if (c == null) {
                return list;
            }
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                String account = c.getString(2);
                String type = c.getString(3);
                int access = c.getInt(4);
                int visible = c.getInt(5);
                if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR && visible == 1) {
                    list.add(new CalendarOption(id, name == null ? "日历" : name, account, type));
                }
            }
        } catch (SecurityException e) {
            // no permission yet - caller handles the empty list
        } finally {
            if (c != null) {
                c.close();
            }
        }
        // local calendars first
        List<CalendarOption> ordered = new ArrayList<CalendarOption>();
        for (CalendarOption o : list) {
            if (o.local) {
                ordered.add(o);
            }
        }
        for (CalendarOption o : list) {
            if (!o.local) {
                ordered.add(o);
            }
        }
        return ordered;
    }

    /**
     * Events created by this app. New events are identified by CUSTOM_APP_PACKAGE
     * (invisible metadata); older v1.2 events are still matched by their description
     * marker so they can be cleaned up as well.
     */
    private static final String MARKED_SELECTION =
            "(" + CalendarContract.Events.CUSTOM_APP_PACKAGE + "=? OR "
                    + CalendarContract.Events.DESCRIPTION + " LIKE ?)";

    private static String[] markedArgs() {
        return new String[]{Occurrence.APP_PACKAGE, "%" + Occurrence.MARKER + "%"};
    }

    public static int countMarked(ContentResolver cr) {
        Cursor c = null;
        try {
            c = cr.query(CalendarContract.Events.CONTENT_URI,
                    new String[]{CalendarContract.Events._ID},
                    MARKED_SELECTION, markedArgs(), null);
            return c == null ? 0 : c.getCount();
        } catch (SecurityException e) {
            return 0;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    public static int deleteMarked(ContentResolver cr) {
        try {
            return cr.delete(CalendarContract.Events.CONTENT_URI, MARKED_SELECTION, markedArgs());
        } catch (SecurityException e) {
            return -1;
        }
    }

    public static Uri eventUri(long id) {
        return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
    }
}
