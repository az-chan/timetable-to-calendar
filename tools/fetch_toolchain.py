#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Download and unpack the Android build toolchain from CN mirrors.

Produces  vivo-kebiao/.toolchain/{jdk,sdk/platforms/android-34,sdk/build-tools/34.0.0}
and writes .toolchain/toolchain.json for build_apk.py.

No Gradle, no sdkmanager: dl.google.com is not reachable here.
"""
import json
import os
import ssl
import sys
import time
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TC = os.path.join(ROOT, ".toolchain")
DL = os.path.join(TC, "downloads")
STAGE = os.path.join(TC, "_stage")

MIRROR_TSINGHUA = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/"
MIRROR_TENCENT = "https://mirrors.cloud.tencent.com/AndroidSDK/"

PACKAGES = [
    {
        "key": "jdk",
        "url": MIRROR_TSINGHUA + "OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip",
        "target": os.path.join(TC, "jdk"),
        "expect": "bin/javac.exe",
    },
    {
        "key": "build-tools",
        "url": MIRROR_TENCENT + "build-tools_r34-windows.zip",
        "target": os.path.join(TC, "sdk", "build-tools", "34.0.0"),
        "expect": "aapt2.exe",
    },
    {
        "key": "platform",
        "url": MIRROR_TENCENT + "platform-34-ext7_r03.zip",
        "target": os.path.join(TC, "sdk", "platforms", "android-34"),
        "expect": "android.jar",
    },
    {
        "key": "platform36",
        "url": MIRROR_TENCENT + "platform-36_r02.zip",
        "target": os.path.join(TC, "sdk", "platforms", "android-36"),
        "expect": "android.jar",
        "optional": True,
    },
    {
        "key": "buildtools36",
        "url": MIRROR_TENCENT + "build-tools_r36_windows.zip",
        "target": os.path.join(TC, "sdk", "build-tools", "36.0.0"),
        "expect": "aapt2.exe",
        "optional": True,
    },
]


def log(msg):
    sys.stdout.write("[toolchain] %s\n" % msg)
    sys.stdout.flush()


def ssl_ctx(verify):
    ctx = ssl.create_default_context()
    if not verify:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    return ctx


def download(url, dest, attempts=3):
    tmp = dest + ".part"
    for attempt in range(1, attempts + 1):
        have = os.path.getsize(tmp) if os.path.exists(tmp) else 0
        try:
            req = urllib.request.Request(url)
            if have:
                req.add_header("Range", "bytes=%d-" % have)
            ctx = ssl_ctx(False)
            with urllib.request.urlopen(req, timeout=60, context=ctx) as resp:
                mode = "ab" if (have and resp.status == 206) else "wb"
                if mode == "wb":
                    have = 0
                total = int(resp.headers.get("Content-Length") or 0) + have
                done = have
                last = time.time()
                with open(tmp, mode) as fh:
                    while True:
                        chunk = resp.read(262144)
                        if not chunk:
                            break
                        fh.write(chunk)
                        done += len(chunk)
                        if time.time() - last > 3:
                            last = time.time()
                            pct = ("%.0f%%" % (done * 100.0 / total)) if total else "?"
                            log("  %s %s / %s" % (pct, done // 1024 // 1024, total // 1024 // 1024))
            os.replace(tmp, dest)
            return dest
        except Exception as exc:  # noqa: BLE001
            log("  download attempt %d failed: %s" % (attempt, exc))
            time.sleep(2)
    raise SystemExit("download failed: %s" % url)


def extract(zip_path, dest):
    """Extract a zip whose entries share one top-level directory."""
    with zipfile.ZipFile(zip_path) as zf:
        names = [n for n in zf.namelist() if n.strip("/")]
        tops = sorted({n.split("/")[0] for n in names})
        zf.extractall(STAGE)
    if len(tops) == 1 and all("/" in n or n == tops[0] for n in names):
        src = os.path.join(STAGE, tops[0])
    else:
        src = STAGE
    if os.path.isdir(dest):
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    os.replace(src, dest)
    return dest


def main():
    os.makedirs(DL, exist_ok=True)
    os.makedirs(STAGE, exist_ok=True)
    result = {}
    for pkg in PACKAGES:
        expect = os.path.join(pkg["target"], pkg["expect"].replace("/", os.sep))
        if os.path.exists(expect):
            log("%s already present" % pkg["key"])
            result[pkg["key"]] = pkg["target"]
            continue
        name = pkg["url"].rsplit("/", 1)[-1]
        zip_path = os.path.join(DL, name)
        if not os.path.exists(zip_path) or not zipfile.is_zipfile(zip_path):
            log("downloading %s" % name)
            if os.path.exists(zip_path):
                os.remove(zip_path)
            download(pkg["url"], zip_path)
        log("unpacking %s -> %s" % (name, pkg["target"]))
        extract(zip_path, pkg["target"])
        if not os.path.exists(expect):
            if pkg.get("optional"):
                log("%s layout unexpected, skipping (optional)" % pkg["key"])
                continue
            raise SystemExit("unexpected archive layout for %s (missing %s)" % (pkg["key"], expect))
        result[pkg["key"]] = pkg["target"]

    def pick(new_key, old_key):
        return result.get(new_key) or result[old_key]

    build_tools = pick("buildtools36", "build-tools")
    android_jar = os.path.join(pick("platform36", "platform"), "android.jar")
    payload = {
        "jdk": result["jdk"],
        "java": os.path.join(result["jdk"], "bin", "java.exe"),
        "javac": os.path.join(result["jdk"], "bin", "javac.exe"),
        "keytool": os.path.join(result["jdk"], "bin", "keytool.exe"),
        "build_tools": build_tools,
        "android_jar": android_jar,
    }
    with open(os.path.join(TC, "toolchain.json"), "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, ensure_ascii=False)
    log("ok -> %s" % os.path.join(TC, "toolchain.json"))
    print(json.dumps(payload, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
