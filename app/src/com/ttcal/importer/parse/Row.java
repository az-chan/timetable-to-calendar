package com.ttcal.importer.parse;

/** One timetable row as read from the sheet. */
public final class Row {
    public final int excelRow;
    public final String weekdayRaw;
    public final String timeRaw;
    public final String course;
    public final String teacher;
    public final String weeksRaw;
    public final String locationRaw;
    public final String dateRaw;

    public Row(int excelRow, String weekdayRaw, String timeRaw, String course,
               String teacher, String weeksRaw, String locationRaw, String dateRaw) {
        this.excelRow = excelRow;
        this.weekdayRaw = weekdayRaw;
        this.timeRaw = timeRaw;
        this.course = course;
        this.teacher = teacher;
        this.weeksRaw = weeksRaw;
        this.locationRaw = locationRaw;
        this.dateRaw = dateRaw;
    }
}
