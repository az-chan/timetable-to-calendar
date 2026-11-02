package com.ttcal.importer.parse;

import com.ttcal.importer.xlsx.XlsxReader;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Chooses the worksheet that actually carries a timetable. */
public final class SheetPicker {

    private SheetPicker() {
    }

    public static XlsxReader.Sheet pick(XlsxReader book) throws IOException {
        List<String> names = book.sheetNames();
        if (names.isEmpty()) {
            throw new IOException("xlsx 中没有工作表");
        }
        XlsxReader.Sheet preferred = null;
        XlsxReader.Sheet fallback = null;
        for (String name : names) {
            XlsxReader.Sheet sheet;
            try {
                sheet = book.sheet(name);
            } catch (IOException e) {
                continue;
            }
            if (!looksLikeTimetable(sheet)) {
                continue;
            }
            if (fallback == null) {
                fallback = sheet;
            }
            if ("Sheet1".equalsIgnoreCase(name.trim())) {
                preferred = sheet;
                break;
            }
        }
        if (preferred != null) {
            return preferred;
        }
        if (fallback != null) {
            return fallback;
        }
        return book.firstSheet();
    }

    public static boolean looksLikeTimetable(XlsxReader.Sheet sheet) {
        int limit = Math.min(sheet.rows.size(), 12);
        for (int i = 0; i < limit; i++) {
            Map<String, Integer> map = TimetableParser.headerMap(sheet.rows.get(i));
            if (map.containsKey("time") && map.containsKey("course")
                    && (map.containsKey("weekday") || map.containsKey("date"))) {
                return true;
            }
        }
        return false;
    }
}
