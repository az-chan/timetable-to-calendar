package com.ttcal.importer.cli;

import com.ttcal.importer.parse.Occurrence;
import com.ttcal.importer.parse.ParsedTimetable;
import com.ttcal.importer.parse.SheetPicker;
import com.ttcal.importer.parse.TimetableParser;
import com.ttcal.importer.xlsx.XlsxReader;

import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Desktop harness: same parser code as the Android app, so the verification
 * script can diff it against an independent Python implementation.
 *
 * usage: CliMain <file.xlsx> [yyyy-MM-dd semesterMonday] [sheetName]
 */
public final class CliMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: CliMain <file.xlsx> [yyyy-MM-dd semesterMonday] [sheetName]");
            System.exit(2);
        }
        LocalDate monday = args.length > 1 ? LocalDate.parse(args[1]) : LocalDate.of(2026, 9, 7);
        LocalDate from = null;
        for (String arg : args) {
            if (arg != null && arg.startsWith("--from=")) {
                from = LocalDate.parse(arg.substring("--from=".length()));
            }
        }
        InputStream in = new FileInputStream(args[0]);
        XlsxReader book;
        try {
            book = XlsxReader.open(in);
        } finally {
            in.close();
        }
        String sheetName = null;
        for (int i = 2; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                sheetName = args[i];
                break;
            }
        }
        XlsxReader.Sheet sheet = sheetName != null ? book.sheet(sheetName) : SheetPicker.pick(book);
        ParsedTimetable parsed = TimetableParser.fromSheet(sheet.rows, monday, sheet.name);

        List<Occurrence> occ = new ArrayList<Occurrence>(parsed.occurrences);
        Collections.sort(occ, new Comparator<Occurrence>() {
            @Override
            public int compare(Occurrence a, Occurrence b) {
                int c = a.date.compareTo(b.date);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(a.startMinutes, b.startMinutes);
                if (c != 0) {
                    return c;
                }
                c = a.course.compareTo(b.course);
                return c != 0 ? c : a.location.compareTo(b.location);
            }
        });

        List<Occurrence> shown = from == null
                ? occ : Occurrence.filterFromDate(occ, from);
        System.out.println("SHEET=" + parsed.sheetName);
        if (from != null) {
            System.out.println("FILTERED from=" + from + " kept=" + shown.size()
                    + " dropped=" + (occ.size() - shown.size()));
        }
        System.out.println("SUMMARY rows=" + parsed.rowsTotal
                + " skipped=" + parsed.rowsSkipped
                + " courses=" + parsed.courseCount
                + " occ=" + parsed.occurrences.size()
                + " conflicts=" + parsed.conflictSlots
                + " first=" + (parsed.firstDate == null ? "-" : parsed.firstDate)
                + " last=" + (parsed.lastDate == null ? "-" : parsed.lastDate));
        for (String warning : parsed.warnings) {
            System.out.println("WARN " + warning);
        }
        for (Occurrence o : shown) {
            System.out.println("OCC|" + o.date + "|" + o.timeLabel() + "|" + o.course
                    + "|" + o.location + "|" + o.teacher + "|" + o.week);
        }
    }
}
