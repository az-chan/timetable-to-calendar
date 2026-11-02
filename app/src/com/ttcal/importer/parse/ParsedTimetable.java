package com.ttcal.importer.parse;

import java.time.LocalDate;
import java.util.List;

/** Result of parsing one timetable sheet. */
public final class ParsedTimetable {

    public final List<Occurrence> occurrences;
    public final List<String> warnings;
    public final int rowsTotal;
    public final int rowsSkipped;
    public final int conflictSlots;
    public final int courseCount;
    public final LocalDate firstDate;
    public final LocalDate lastDate;
    public final String sheetName;

    public ParsedTimetable(List<Occurrence> occurrences, List<String> warnings, int rowsTotal,
                           int rowsSkipped, int conflictSlots, int courseCount,
                           LocalDate firstDate, LocalDate lastDate, String sheetName) {
        this.occurrences = occurrences;
        this.warnings = warnings;
        this.rowsTotal = rowsTotal;
        this.rowsSkipped = rowsSkipped;
        this.conflictSlots = conflictSlots;
        this.courseCount = courseCount;
        this.firstDate = firstDate;
        this.lastDate = lastDate;
        this.sheetName = sheetName;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("工作表：").append(sheetName)
                .append("\n课程 ").append(courseCount).append(" 门 / 课次 ").append(occurrences.size())
                .append(" 个\n数据行 ").append(rowsTotal).append(" 行");
        if (rowsSkipped > 0) {
            sb.append("（跳过 ").append(rowsSkipped).append(" 行）");
        }
        if (firstDate != null && lastDate != null) {
            sb.append("\n时间范围：").append(firstDate).append(" ~ ").append(lastDate);
        }
        if (conflictSlots > 0) {
            sb.append("\n提示：有 ").append(conflictSlots).append(" 处同/重叠时段，两门课都会写入");
        }
        return sb.toString();
    }
}
