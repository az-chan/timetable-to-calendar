package com.ttcal.importer.parse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns sheet rows into dated class occurrences. */
public final class TimetableParser {

    private TimetableParser() {
    }

    /** Parses a whole sheet: locates the header row, then expands every course. */
    public static ParsedTimetable fromSheet(List<List<String>> table, LocalDate semesterMonday,
                                            String sheetName) {
        int headerRow = -1;
        Map<String, Integer> cols = null;
        int limit = Math.min(table.size(), 12);
        for (int i = 0; i < limit; i++) {
            Map<String, Integer> map = headerMap(table.get(i));
            if (map.containsKey("time") && map.containsKey("course")
                    && (map.containsKey("weekday") || map.containsKey("date"))) {
                headerRow = i;
                cols = map;
                break;
            }
        }
        if (headerRow < 0 || cols == null) {
            throw new IllegalArgumentException(
                    "没有找到表头（需要包含 星期/时间/课程 等列），请检查表格格式");
        }
        List<Row> rows = new ArrayList<Row>();
        for (int i = headerRow + 1; i < table.size(); i++) {
            List<String> cells = table.get(i);
            if (cells == null || cells.isEmpty()) {
                continue;
            }
            String weekday = cell(cells, cols.get("weekday"));
            String time = cell(cells, cols.get("time"));
            String course = cell(cells, cols.get("course"));
            String teacher = cell(cells, cols.get("teacher"));
            String weeks = cell(cells, cols.get("weeks"));
            String location = cell(cells, cols.get("location"));
            String date = cell(cells, cols.get("date"));
            if (weekday.isEmpty() && time.isEmpty() && course.isEmpty()) {
                continue;
            }
            rows.add(new Row(i + 1, weekday, time, course, teacher, weeks, location, date));
        }
        return parse(rows, semesterMonday, sheetName);
    }

    public static ParsedTimetable parse(List<Row> rows, LocalDate semesterMonday, String sheetName) {
        List<Occurrence> occurrences = new ArrayList<Occurrence>();
        List<String> warnings = new ArrayList<String>();
        Set<String> courses = new HashSet<String>();
        int skipped = 0;
        LocalDate min = null;
        LocalDate max = null;

        for (Row row : rows) {
            TimeRange time = TimeRange.parse(row.timeRaw);
            if (time == null) {
                skipped++;
                warnings.add("第" + row.excelRow + "行：时间无法解析（" + safe(row.timeRaw) + "）");
                continue;
            }
            if (row.course == null || row.course.trim().isEmpty()) {
                skipped++;
                warnings.add("第" + row.excelRow + "行：课程为空");
                continue;
            }
            LocalDate rowDate = parseDate(row.dateRaw);
            int weekday = Weekday.parse(row.weekdayRaw);
            if (weekday < 0 && rowDate != null) {
                weekday = rowDate.getDayOfWeek().getValue();
            }

            List<Integer> weeks = Weeks.parse(row.weeksRaw);
            if (weeks.isEmpty() && rowDate != null && semesterMonday != null) {
                int w = weekOf(rowDate, semesterMonday);
                if (w >= 1 && w <= Weeks.MAX_WEEK) {
                    weeks = new ArrayList<Integer>();
                    weeks.add(w);
                }
            }
            if (weeks.isEmpty() && rowDate == null) {
                skipped++;
                warnings.add("第" + row.excelRow + "行：周次缺失（" + safe(row.weeksRaw) + "）");
                continue;
            }
            if (weekday < 0) {
                skipped++;
                warnings.add("第" + row.excelRow + "行：星期无法解析（" + safe(row.weekdayRaw) + "）");
                continue;
            }
            if (rowDate == null && semesterMonday == null) {
                skipped++;
                warnings.add("第" + row.excelRow + "行：未设置学期第一周周一，无法定位日期");
                continue;
            }

            List<LocationCell.Seg> segs = LocationCell.parse(row.locationRaw);
            List<Integer> effectiveWeeks = weeks;
            if (effectiveWeeks.isEmpty() && rowDate != null) {
                effectiveWeeks = new ArrayList<Integer>();
                effectiveWeeks.add(0);
            }

            for (Integer week : effectiveWeeks) {
                LocalDate date = rowDate != null
                        ? rowDate
                        : semesterMonday.plusWeeks(week - 1).plusDays(weekday - 1);
                LocationCell.Seg seg = pickSegment(segs, week);
                String place = seg.place;
                String note = seg.note;
                occurrences.add(new Occurrence(date, time.startMinutes, time.endMinutes,
                        row.course.trim(), safe(row.teacher), place, note, week, weekday));
                courses.add(row.course.trim());
                if (min == null || date.isBefore(min)) {
                    min = date;
                }
                if (max == null || date.isAfter(max)) {
                    max = date;
                }
            }
        }

        int conflicts = countConflicts(occurrences);
        return new ParsedTimetable(occurrences, warnings, rows.size(), skipped, conflicts,
                courses.size(), min, max, sheetName);
    }

    private static LocationCell.Seg pickSegment(List<LocationCell.Seg> segs, int week) {
        for (LocationCell.Seg seg : segs) {
            if (seg.appliesTo(week)) {
                return seg;
            }
        }
        return segs.get(0);
    }

    private static int countConflicts(List<Occurrence> occurrences) {
        Map<String, List<Occurrence>> byDate = new HashMap<String, List<Occurrence>>();
        for (Occurrence o : occurrences) {
            String k = o.date.toString();
            List<Occurrence> list = byDate.get(k);
            if (list == null) {
                list = new ArrayList<Occurrence>();
                byDate.put(k, list);
            }
            list.add(o);
        }
        Set<String> pairs = new HashSet<String>();
        for (List<Occurrence> list : byDate.values()) {
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    Occurrence a = list.get(i);
                    Occurrence b = list.get(j);
                    if (a.course.equals(b.course)) {
                        continue;
                    }
                    if (a.startMinutes < b.endMinutes && b.startMinutes < a.endMinutes) {
                        String key = a.key() + "#" + b.key();
                        pairs.add(key);
                    }
                }
            }
        }
        return pairs.size();
    }

    public static Map<String, Integer> headerMap(List<String> header) {
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        if (header == null) {
            return map;
        }
        for (int i = 0; i < header.size(); i++) {
            String h = norm(header.get(i));
            String key = classify(h);
            if (key != null && !map.containsKey(key)) {
                map.put(key, i);
            }
        }
        return map;
    }

    private static String classify(String h) {
        if (h.isEmpty()) {
            return null;
        }
        if (h.contains("周次") || h.contains("周数") || h.equals("周")) {
            return "weeks";
        }
        if (h.contains("星期") || h.contains("周几") || h.contains("工作日")) {
            return "weekday";
        }
        if (h.contains("日期") || h.contains("年月日")) {
            return "date";
        }
        if (h.contains("时间") || h.contains("节次") || h.contains("时段")) {
            return "time";
        }
        if (h.contains("课程") || h.contains("科目")) {
            return "course";
        }
        if (h.contains("教师") || h.contains("老师") || h.contains("任课")) {
            return "teacher";
        }
        if (h.contains("地点") || h.contains("教室") || h.contains("场地")) {
            return "location";
        }
        return null;
    }

    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", "").replace("（", "(").replace("）", ")").trim();
    }

    private static String cell(List<String> cells, Integer index) {
        if (index == null || cells == null || index >= cells.size()) {
            return "";
        }
        String v = cells.get(index);
        return v == null ? "" : v.trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    public static LocalDate parseDate(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.matches("\\d{4,6}")) {
            try {
                long serial = Long.parseLong(s);
                if (serial > 20000 && serial < 80000) {
                    return LocalDate.of(1899, 12, 30).plusDays(serial);
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        String n = s.replace('年', '-').replace('月', '-').replace("日", "")
                .replace('/', '-').replace('.', '-');
        String[] parts = n.split("-");
        if (parts.length >= 3) {
            try {
                return LocalDate.of(Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static int weekOf(LocalDate date, LocalDate semesterMonday) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(semesterMonday, date);
        return (int) (days / 7) + 1;
    }
}
