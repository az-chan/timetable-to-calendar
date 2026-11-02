#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Fallback path: turn the timetable xlsx into an RFC 5545 .ics file.

Useful when the APK cannot be sideloaded: import the .ics in the phone's
calendar app instead. One VEVENT per class occurrence, Asia/Shanghai, with a
10 minute display alarm, and stable UIDs so re-importing does not duplicate
everything on calendar apps that de-duplicate by UID.

usage: python tools/xlsx2ics.py [file.xlsx] [out.ics] [yyyy-MM-dd semesterMonday]
                               [remindMinutes] [--from=yyyy-MM-dd] [--remind=N]

remindMinutes: 0 = no alarm, otherwise "that many minutes before the class" (default 10).
"""
import datetime
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_parse import expand, hhmm  # noqa: E402  (shared reference implementation)

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(BASE)

TZ_BLOCK = "\r\n".join([
    "BEGIN:VTIMEZONE",
    "TZID:Asia/Shanghai",
    "X-LIC-LOCATION:Asia/Shanghai",
    "BEGIN:STANDARD",
    "DTSTART:19700101T000000",
    "TZOFFSETFROM:+0800",
    "TZOFFSETTO:+0800",
    "TZNAME:CST",
    "END:STANDARD",
    "END:VTIMEZONE",
])


def esc(text):
    if text is None:
        return ""
    return (str(text).replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n"))


def fold(line):
    """RFC 5545 line folding on 75 UTF-8 octets."""
    data = line.encode("utf-8")
    if len(data) <= 73:
        return line
    out, chunk = [], b""
    for ch in line:
        encoded = ch.encode("utf-8")
        if len(chunk) + len(encoded) > 72:
            out.append(chunk.decode("utf-8"))
            chunk = b" "
        chunk += encoded
    out.append(chunk.decode("utf-8"))
    return "\r\n".join(out)


def stamp(dt, minutes):
    return "%04d%02d%02dT%02d%02d00" % (dt.year, dt.month, dt.day,
                                        minutes // 60, minutes % 60)


def build(rows, calendar_name, remind_minutes=10):
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    lines = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//ttcal//Timetable Importer//CN",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
        "X-WR-CALNAME:" + esc(calendar_name),
        "X-WR-TIMEZONE:Asia/Shanghai",
        *TZ_BLOCK.splitlines(),
    ]
    for row in rows:
        key = "|".join([row["date"].isoformat(), hhmm(row["start"]), row["course"],
                        row["location"]])
        uid = hashlib.sha1(key.encode("utf-8")).hexdigest()[:24] + "@ttcal"
        desc = []
        if row.get("teacher"):
            desc.append("教师：" + row["teacher"])
        desc.append("周次：第%d周" % row["week"])
        if row.get("note"):
            desc.append("备注：" + row["note"])
        lines.extend([
            "BEGIN:VEVENT",
            "UID:" + uid,
            "DTSTAMP:" + now,
            "DTSTART;TZID=Asia/Shanghai:" + stamp(row["date"], row["start"]),
            "DTEND;TZID=Asia/Shanghai:" + stamp(row["date"], row["end"]),
            "SUMMARY:" + esc(row["course"]),
            "LOCATION:" + esc(row["location"]),
            "DESCRIPTION:" + esc("\n".join(desc)),
            "STATUS:CONFIRMED",
            "TRANSP:OPAQUE",
        ])
        if remind_minutes > 0:
            lines.extend([
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "TRIGGER:-PT%dM" % remind_minutes,
                "DESCRIPTION:" + esc(row["course"] + " 上课提醒"),
                "END:VALARM",
            ])
        lines.append("END:VEVENT")
    lines.append("END:VCALENDAR")
    return "\r\n".join(fold(line) for line in lines) + "\r\n"


def main():
    if len(sys.argv) < 2:
        print("usage: python tools/xlsx2ics.py <课表.xlsx> [out.ics] [yyyy-MM-dd 学期首周一] [提醒分钟数]")
        return 2
    src = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(BASE, "dist", "课程表.ics")
    if len(sys.argv) > 3 and not sys.argv[3].startswith("--"):
        monday = datetime.date.fromisoformat(sys.argv[3])
    else:  # 默认取「本周周一」，避免写死某个学期
        today = datetime.date.today()
        monday = today - datetime.timedelta(days=today.weekday())
    remind = int(sys.argv[4]) if len(sys.argv) > 4 and not sys.argv[4].startswith("--") else 10
    from_date = None
    for arg in sys.argv[1:]:
        if arg.startswith("--from="):
            from_date = datetime.date.fromisoformat(arg.split("=", 1)[1])
        elif arg.startswith("--remind="):
            remind = int(arg.split("=", 1)[1])
    ref = expand(src, monday)
    if from_date is not None:
        before = len(ref["occurrences"])
        ref["occurrences"] = [o for o in ref["occurrences"] if o["date"] >= from_date]
        print("  filter --from=%s: %d -> %d" % (from_date, before, len(ref["occurrences"])))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="") as fh:
        fh.write(build(ref["occurrences"], "课表 2026-2027-1", remind))
    print("wrote %s (%d VEVENTs, %d alarms, sheet=%s)"
          % (out, len(ref["occurrences"]), len(ref["occurrences"]) if remind > 0 else 0,
             ref["sheet"]))
    return out


if __name__ == "__main__":
    main()
