#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Fixture test: builds a synthetic xlsx with awkward rows and asserts that the
Java parser (used by the APK) and the Python reference agree.

Covers: title row above the header, 单双/跳周, 学时 annotation, week-scoped
locations, 不用场地/网络授课, and a deliberately broken row.

usage: python tools/test_fixtures.py
"""
import datetime
import os
import sys

import openpyxl

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_parse import expand, check  # noqa: E402

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(BASE, "build", "fixture.xlsx")

ROWS = [
    # weekday, time, course, teacher, weeks, location
    ("周二", "09:55-11:30", "大学物理", "张老师", "1-16", "1F-101"),
    ("周三", "13:30-15:05", "化学原理", "李老师", "2,4,6,8", "2A-202（4周）；2A-301（2,6,8周）"),
    ("周四", "18:00-19:35", "体育", "王老师", "3（4学时）", "塑胶田径场1"),
    ("周五", "08:00-09:35", "网络课", "赵老师", "1-5,7-10", "网络授课"),
    ("周三", "15:25-17:00", "单周实验", "孙老师", "1,3,5", "综合楼A321"),
    ("周八", "25:99-30:00", "坏数据", "无", "1", "无"),
    ("周一", "08:00-09:35", "双周课", "周老师", "1（8学时）", "2A-109"),
]

EXPECTED = {
    "occurrences": 16 + 4 + 1 + 9 + 3 + 0 + 1,
    "skipped": 1,
    "locations": {
        ("2026-09-16", "化学原理"): "2A-301",   # week 2 of that row
        ("2026-09-30", "化学原理"): "2A-202",   # week 4
        ("2026-09-11", "网络课"): "",
        ("2026-09-24", "体育"): "塑胶田径场1",   # week 3, Thursday
    },
}


def build():
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "怪样本"
    ws.append(["第 1 周（4 节课）"])
    ws.append(["星期", "时间", "课程", "教师", "周次", "上课地点"])
    for row in ROWS:
        ws.append(list(row))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    wb.save(OUT)
    return OUT


def main():
    path = build()
    print("fixture:", path)
    ok = check(path, datetime.date(2026, 9, 7), "怪样本")
    ref = expand(path, datetime.date(2026, 9, 7), "怪样本")
    got = len(ref["occurrences"])
    if got != EXPECTED["occurrences"]:
        ok = False
        print("  FAIL occurrences: got %d want %d" % (got, EXPECTED["occurrences"]))
    if ref["skipped"] != EXPECTED["skipped"]:
        ok = False
        print("  FAIL skipped: got %d want %d" % (ref["skipped"], EXPECTED["skipped"]))
    for (day, course), place in EXPECTED["locations"].items():
        match = [o for o in ref["occurrences"]
                 if o["date"].isoformat() == day and o["course"] == course]
        if not match:
            ok = False
            print("  FAIL missing occurrence %s %s" % (day, course))
        elif match[0]["location"] != place:
            ok = False
            print("  FAIL location %s %s: got %r want %r"
                  % (day, course, match[0]["location"], place))
    print("FIXTURE RESULT: %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
