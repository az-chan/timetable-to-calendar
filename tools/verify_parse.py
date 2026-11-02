#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Independent Python reference implementation + diff against the Java parser.

The Java parser shipping inside the APK is the one that matters; this script
re-implements the same rules from scratch (openpyxl + plain Python) and asserts
both produce exactly the same set of class occurrences.

usage: python tools/verify_parse.py [file.xlsx ...]
"""
import datetime
import os
import re
import subprocess
import sys

import glob

import openpyxl

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(BASE)
JAVA = os.path.join(BASE, ".toolchain", "jdk", "bin", "java.exe")
CLI_CP = os.path.join(BASE, "build", "cli")
_TODAY = datetime.date.today()
DEFAULT_MONDAY = _TODAY - datetime.timedelta(days=_TODAY.weekday())

# ---------------------------------------------------------------- reference impl
WEEKDAY = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "日": 7, "天": 7}


def weeks_of(raw):
    """Expand 1-16 / 1,3,7 / 1-5,7-10 / 1（8学时） into a sorted week list."""
    if not raw:
        return []
    text = str(raw)
    while True:
        stripped = re.sub(r"[（(][^（()）]*[）)]", " ", text)
        if stripped == text:
            break
        text = stripped
    text = (text.replace("，", ",").replace("、", ",").replace("；", ";")
                .replace("–", "-").replace("—", "-").replace("~", "-")
                .replace("～", "-").replace("至", "-").replace("周", " "))
    out = []
    for token in re.split(r"[\s;,]+", text):
        token = token.strip()
        if not token:
            continue
        m = re.fullmatch(r"(\d{1,2})-(\d{1,2})", token)
        if m:
            lo, hi = int(m.group(1)), int(m.group(2))
            if lo > hi:
                lo, hi = hi, lo
            out.extend(range(max(lo, 1), min(hi, 30) + 1))
            continue
        if re.fullmatch(r"\d{1,2}", token):
            value = int(token)
            if 1 <= value <= 30:
                out.append(value)
    return sorted(set(out))


def weekday_of(raw):
    if not raw:
        return -1
    text = str(raw).strip()
    for ch in ("星期", "礼拜", "周", "曜日"):
        text = text.replace(ch, "")
    text = re.sub(r"\s+", "", text)
    if not text:
        return -1
    if "日" in text or "天" in text:
        return 7
    for ch, num in WEEKDAY.items():
        if ch in text and num < 7:
            return num
    if len(text) == 1 and text.isdigit() and 1 <= int(text) <= 7:
        return int(text)
    return -1


def time_of(raw):
    if not raw:
        return None
    m = re.search(r"(\d{1,2}):(\d{2})\s*[-—–~～至]\s*(\d{1,2}):(\d{2})",
                  str(raw).replace(" ", ""))
    if not m:
        return None
    sh, sm, eh, em = (int(x) for x in m.groups())
    start, end = sh * 60 + sm, eh * 60 + em
    if end <= start:
        return None
    return start, end


NO_VENUE = {"不用场地", "无场地", "网络授课", "线上", "网课", "无"}


def locations_of(raw):
    """Return [(place, note, weeks-or-None)] honouring week-scoped variants."""
    if not raw or not str(raw).strip():
        return [("", "", None)]
    segs = []
    for part in re.split(r"[；;]", str(raw)):
        piece = part.strip()
        if not piece:
            continue
        weeks, notes = [], []
        for inner in re.findall(r"[（(]([^（()）]*)[）)]", piece):
            inner = inner.strip()
            if "周" in inner:
                weeks.extend(w for w in weeks_of(inner) if w not in weeks)
            elif inner:
                notes.append(inner)
        place = re.sub(r"[（(][^（()）]*[）)]", "", piece).strip()
        if place in NO_VENUE:
            notes.append(place)
            place = ""
        segs.append((place, " ".join(notes), weeks or None))
    return segs or [("", "", None)]


HEADER_RULES = [
    ("weeks", ("周次", "周数")),
    ("weekday", ("星期", "周几", "工作日")),
    ("date", ("日期", "年月日")),
    ("time", ("时间", "节次", "时段")),
    ("course", ("课程", "科目")),
    ("teacher", ("教师", "老师", "任课")),
    ("location", ("地点", "教室", "场地")),
]


def header_map(cells):
    mapping = {}
    for idx, cell in enumerate(cells or []):
        text = re.sub(r"\s+", "", str(cell or ""))
        if not text:
            continue
        key = None
        for name, needles in HEADER_RULES:
            if any(n in text for n in needles):
                key = name
                break
        if key and key not in mapping:
            mapping[key] = idx
    return mapping


def looks_like_timetable(rows):
    for cells in rows[:12]:
        m = header_map(cells)
        if "time" in m and "course" in m and ("weekday" in m or "date" in m):
            return True
    return False


def cell(cells, idx):
    if idx is None or cells is None or idx >= len(cells) or cells[idx] is None:
        return ""
    return str(cells[idx]).strip()


def iso_date(raw):
    if not raw:
        return None
    text = str(raw).strip()
    if re.fullmatch(r"\d{4,6}", text):
        serial = int(text)
        if 20000 < serial < 80000:
            return datetime.date(1899, 12, 30) + datetime.timedelta(days=serial)
        return None
    text = (text.replace("年", "-").replace("月", "-").replace("日", "")
                .replace("/", "-").replace(".", "-"))
    parts = text.split("-")
    if len(parts) < 3:
        return None
    try:
        return datetime.date(int(parts[0]), int(parts[1]), int(parts[2]))
    except ValueError:
        return None


def expand(path, monday=DEFAULT_MONDAY, sheet=None):
    wb = openpyxl.load_workbook(path, data_only=True)
    chosen = None
    for name in ([sheet] if sheet else wb.sheetnames):
        rows = [list(r) for r in wb[name].iter_rows(values_only=True)]
        if looks_like_timetable(rows):
            if name.strip().lower() == "sheet1":
                chosen = (name, rows)
                break
            if chosen is None:
                chosen = (name, rows)
    if chosen is None:
        raise SystemExit("no timetable sheet in %s" % path)
    name, rows = chosen

    header_row, cols = None, None
    for i, cells in enumerate(rows[:12]):
        m = header_map(cells)
        if "time" in m and "course" in m and ("weekday" in m or "date" in m):
            header_row, cols = i, m
            break

    out, skipped = [], 0
    for cells in rows[header_row + 1:]:
        if not cells:
            continue
        weekday_raw = cell(cells, cols.get("weekday"))
        time_raw = cell(cells, cols.get("time"))
        course = cell(cells, cols.get("course"))
        if not weekday_raw and not time_raw and not course:
            continue
        teacher = cell(cells, cols.get("teacher"))
        weeks_raw = cell(cells, cols.get("weeks"))
        location_raw = cell(cells, cols.get("location"))
        date_raw = cell(cells, cols.get("date"))

        tm = time_of(time_raw)
        if tm is None or not course:
            skipped += 1
            continue
        row_date = iso_date(date_raw)
        wd = weekday_of(weekday_raw)
        if wd < 0 and row_date:
            wd = row_date.isoweekday()
        weeks = weeks_of(weeks_raw)
        if not weeks and row_date and monday:
            w = (row_date - monday).days // 7 + 1
            if 1 <= w <= 30:
                weeks = [w]
        if (not weeks and row_date is None) or wd < 0:
            skipped += 1
            continue
        if row_date is None and monday is None:
            skipped += 1
            continue

        segs = locations_of(location_raw)
        for w in (weeks or [0]):
            date = row_date or (monday + datetime.timedelta(weeks=w - 1, days=wd - 1))
            seg = next((s for s in segs if s[2] is None or w in s[2]), segs[0])
            out.append({
                "date": date,
                "start": tm[0],
                "end": tm[1],
                "course": course,
                "location": seg[0],
                "teacher": teacher,
                "week": w,
                "note": seg[1],
            })
    conflicts = count_conflicts(out)
    return {
        "sheet": name,
        "rows": len(rows) - header_row - 1,
        "skipped": skipped,
        "occurrences": out,
        "conflicts": conflicts,
    }


def count_conflicts(occ):
    pairs = set()
    for i, a in enumerate(occ):
        for b in occ[i + 1:]:
            if a["date"] != b["date"] or a["course"] == b["course"]:
                continue
            if a["start"] < b["end"] and b["start"] < a["end"]:
                pairs.add(tuple(sorted([(a["date"], a["start"], a["course"]),
                                        (b["date"], b["start"], b["course"])])))
    return len(pairs)


def hhmm(minutes):
    return "%02d:%02d" % (minutes // 60, minutes % 60)



def guess_monday(path, sheet=None):
    """用表里最早的日期推出「学期第 1 周周一」，避免把某个学期写死。"""
    wb = openpyxl.load_workbook(path, data_only=True, read_only=True)
    try:
        names = [sheet] if sheet else list(wb.sheetnames)
        best = None
        for name in names:
            for cells in wb[name].iter_rows(values_only=True):
                for cell in cells or ():
                    if cell is None:
                        continue
                    text = str(cell).strip()
                    if not re.match(r"^\d{4}[-/年]", text):
                        continue
                    day = iso_date(text)
                    if day and (best is None or day < best):
                        best = day
        if best is not None:
            return best - datetime.timedelta(days=best.weekday())
    finally:
        wb.close()
    return DEFAULT_MONDAY


def run_java(path, monday, sheet=None):
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-cp", CLI_CP,
           "com.ttcal.importer.cli.CliMain", path, monday.isoformat()]
    if sheet:
        cmd.append(sheet)
    out_path = os.path.join(BASE, "build", "java-out.txt")
    err_path = os.path.join(BASE, "build", "java-err.txt")
    with open(out_path, "wb") as out, open(err_path, "wb") as err:
        code = subprocess.call(cmd, stdout=out, stderr=err)
    if code != 0:
        with open(err_path, "rb") as err:
            raise SystemExit("java failed: %s\n%s" % (code, err.read().decode("utf-8", "replace")))
    with open(out_path, "rb") as out:
        return out.read().decode("utf-8", "replace")


def parse_java(stdout):
    summary, occ = {}, []
    for line in stdout.splitlines():
        if line.startswith("SHEET="):
            summary["sheet"] = line[len("SHEET="):].strip()
        elif line.startswith("SUMMARY "):
            for token in line[len("SUMMARY "):].split():
                key, _, value = token.partition("=")
                summary[key] = value
        elif line.startswith("OCC|"):
            _, date, span, course, location, teacher, week = line.split("|")
            start, end = span.split("-")
            occ.append({
                "date": datetime.date.fromisoformat(date),
                "start": int(start[:2]) * 60 + int(start[3:]),
                "end": int(end[:2]) * 60 + int(end[3:]),
                "course": course,
                "location": location,
                "teacher": teacher,
                "week": int(week),
            })
    return summary, occ


def key(row):
    return (row["date"].isoformat(), hhmm(row["start"]), hhmm(row["end"]),
            row["course"], row["location"], row["teacher"], row["week"])


def check(path, monday=DEFAULT_MONDAY, sheet=None):
    ref = expand(path, monday, sheet)
    summary, java_occ = parse_java(run_java(path, monday, sheet))
    ref_keys = sorted(key(r) for r in ref["occurrences"])
    java_keys = sorted(key(r) for r in java_occ)
    only_ref = [k for k in ref_keys if k not in set(java_keys)]
    only_java = [k for k in java_keys if k not in set(ref_keys)]

    ok = (not only_ref and not only_java)
    print("=" * 72)
    print(os.path.basename(path) + (" [sheet=%s]" % sheet if sheet else ""))
    print("  python : sheet=%s occ=%d conflicts=%d skipped=%d"
          % (ref["sheet"], len(ref_keys), ref["conflicts"], ref["skipped"]))
    print("  java   : sheet=%s occ=%d conflicts=%s skipped=%s"
          % (summary.get("sheet", "-"), len(java_keys), summary.get("conflicts"),
             summary.get("skipped")))
    print("  summary: %s" % summary)
    if not ok:
        print("  MISMATCH only_python=%d only_java=%d" % (len(only_ref), len(only_java)))
        for k in only_ref[:10]:
            print("    -java  ", k)
        for k in only_java[:10]:
            print("    -python", k)
    else:
        print("  MATCH  %d occurrences identical" % len(ref_keys))
    return ok


def unit_tests():
    cases = {
        "1-16": list(range(1, 17)),
        "1,3,7,9,11,13,15": [1, 3, 7, 9, 11, 13, 15],
        "1-5,7-10": [1, 2, 3, 4, 5, 7, 8, 9, 10],
        "1（8学时）": [1],
        "10-11": [10, 11],
        "9-12,14-16周": [9, 10, 11, 12, 14, 15, 16],
    }
    ok = True
    for raw, want in cases.items():
        got = weeks_of(raw)
        if got != want:
            ok = False
            print("  FAIL weeks %r -> %r want %r" % (raw, got, want))
    time_cases = {"08:00-09:35": (480, 575), "08:00—17:00": (480, 1020),
                  "09:35-08:00": None, "abc": None}
    for raw, want in time_cases.items():
        got = time_of(raw)
        if got != want:
            ok = False
            print("  FAIL time %r -> %r want %r" % (raw, got, want))
    wd_cases = {"周一": 1, "星期日": 7, "周天": 7, "3": 3, "周五": 5}
    for raw, want in wd_cases.items():
        got = weekday_of(raw)
        if got != want:
            ok = False
            print("  FAIL weekday %r -> %r want %r" % (raw, got, want))
    print("unit tests: %s" % ("ok" if ok else "FAILED"))
    return ok


def main():
    files = sys.argv[1:]
    if not files:
        files = sorted(glob.glob(os.path.join(ROOT, "*.xlsx")))
    if not files:
        print("usage: python tools/verify_parse.py <课表.xlsx> [更多.xlsx ...]")
        print("（把要校验的 xlsx 放在仓库上一级目录，或直接传路径；仓库本身不含课表文件）")
        return 2
    ok = unit_tests()
    for path in files:
        ok = check(path, guess_monday(path)) and ok
        # 若工作簿自带「按周课表」展开页，再校验一次日期列路径
        try:
            wb = openpyxl.load_workbook(path, read_only=True)
            if "按周课表" in wb.sheetnames:
                ok = check(path, guess_monday(path, "按周课表"), "按周课表") and ok
            wb.close()
        except Exception:
            pass
    print("=" * 72)
    print("RESULT: %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
