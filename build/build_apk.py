#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Build a signed APK with the plain command-line toolchain (no Gradle).

steps: icons -> aapt2 compile/link -> javac -> d8 -> zip -> zipalign -> apksigner -> verify

Important details enforced here (they decide whether Android 11+ will install it):
  * resources.arsc must be STORED (uncompressed) and 4-byte aligned
  * the APK must carry a v2+ signature (we also add v1 for old installers)

usage: python build/build_apk.py
"""
import json
import os
import shutil
import subprocess
import sys
import time
import zipfile

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(BASE, "app")
SRC = os.path.join(APP, "src")
RES = os.path.join(APP, "res")
MANIFEST = os.path.join(APP, "AndroidManifest.xml")
BUILD = os.path.join(BASE, "build")
DIST = os.path.join(BASE, "dist")
TC = os.path.join(BASE, ".toolchain")

VERSION = "1.7"
VERSION_CODE = 8
MIN_SDK = "26"
TARGET_SDK = "36"
APK_NAME = "timetable-importer-v%s.apk" % VERSION
KEYSTORE = os.path.join(BUILD, "keystore.jks")
KS_PASS = "ttcal-2026"
KS_ALIAS = "ttcal"

DENSITIES = [
    ("mdpi", 48, 108),
    ("hdpi", 72, 162),
    ("xhdpi", 96, 216),
    ("xxhdpi", 144, 324),
    ("xxxhdpi", 192, 432),
]

# entries Android insists on storing uncompressed
STORE_ENTRIES = {"resources.arsc"}


def load_toolchain():
    path = os.path.join(TC, "toolchain.json")
    if not os.path.exists(path):
        raise SystemExit("toolchain missing, run: python tools/fetch_toolchain.py")
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def run(cmd, cwd=None):
    printable = " ".join(cmd) if isinstance(cmd, list) else cmd
    print("  $ %s" % printable, flush=True)
    result = subprocess.call(cmd, cwd=cwd)
    if result != 0:
        raise SystemExit("command failed (%d): %s" % (result, printable))


def capture(cmd, log_path):
    with open(log_path, "wb") as fh:
        code = subprocess.call(cmd, stdout=fh, stderr=subprocess.STDOUT)
    with open(log_path, "rb") as fh:
        return code, fh.read().decode("utf-8", "replace")


# --------------------------------------------------------------------- icons
def ensure_icons():
    from PIL import Image, ImageDraw

    def rounded(size, radius, color, box=None):
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        b = box or (0, 0, size - 1, size - 1)
        d.rounded_rectangle(b, radius=radius, fill=color)
        return img

    def draw_legacy(size):
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        img.alpha_composite(rounded(size, int(size * 0.22), (21, 101, 192, 255)))
        d = ImageDraw.Draw(img)
        pad = size * 0.22
        body = (pad, pad * 1.15, size - pad, size - pad * 0.85)
        d.rounded_rectangle(body, radius=size * 0.06, fill=(255, 255, 255, 255))
        step = (body[3] - body[1]) / 5.0
        y = body[1] + step * 1.5
        for _ in range(3):
            d.rounded_rectangle((pad + size * 0.06, y, size - pad - size * 0.22, y + step * 0.42),
                                radius=size * 0.02, fill=(144, 202, 249, 255))
            y += step
        r = size * 0.20
        cx, cy = size - pad * 0.75, size - pad * 0.75
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(67, 160, 71, 255))
        d.line([(cx - r * 0.45, cy), (cx - r * 0.1, cy + r * 0.38), (cx + r * 0.5, cy - r * 0.38)],
               fill=(255, 255, 255, 255), width=max(2, int(size * 0.045)), joint="curve")
        return img

    def draw_foreground(size):
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        inner = size * 0.30
        body = (inner, inner * 1.08, size - inner, size - inner * 0.88)
        white = (255, 255, 255, 255)
        d.rounded_rectangle(body, radius=size * 0.05, fill=white)
        d.rounded_rectangle((inner, inner * 1.08, size - inner, inner * 1.45), radius=size * 0.03,
                            fill=(13, 71, 161, 255))
        step = (body[3] - body[1]) / 5.0
        y = body[1] + step * 1.55
        for _ in range(3):
            d.rounded_rectangle((inner + size * 0.05, y, size - inner - size * 0.18, y + step * 0.40),
                                radius=size * 0.015, fill=(144, 202, 249, 255))
            y += step
        return img

    for density, legacy, adaptive in DENSITIES:
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        draw_legacy(legacy).save(os.path.join(folder, "ic_launcher.png"))
        draw_foreground(adaptive).save(os.path.join(folder, "ic_launcher_foreground.png"))
    print("  icons: %d densities" % len(DENSITIES), flush=True)


# --------------------------------------------------------------------- build
def package_apk(base_apk, dex_file, out_apk):
    """Assemble the APK, keeping resources.arsc uncompressed."""
    with zipfile.ZipFile(base_apk) as src:
        with zipfile.ZipFile(out_apk, "w", zipfile.ZIP_DEFLATED) as dst:
            for item in src.infolist():
                data = src.read(item.filename)
                info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                info.external_attr = item.external_attr
                if item.filename in STORE_ENTRIES:
                    info.compress_type = zipfile.ZIP_STORED
                else:
                    info.compress_type = zipfile.ZIP_DEFLATED
                dst.writestr(info, data)
            info = zipfile.ZipInfo("classes.dex", date_time=time.localtime()[:6])
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(dex_file, "rb") as fh:
                dst.writestr(info, fh.read())


def verify_apk(apk, tc):
    """Hard checks that Android's package installer actually enforces."""
    java = tc["java"]
    bt = tc["build_tools"]
    aapt2 = os.path.join(bt, "aapt2.exe")
    zipalign = os.path.join(bt, "zipalign.exe")
    apksigner = os.path.join(bt, "lib", "apksigner.jar")
    problems = []

    with zipfile.ZipFile(apk) as zf:
        names = {i.filename for i in zf.infolist()}
        arsc = zf.getinfo("resources.arsc") if "resources.arsc" in names else None
        if arsc is None:
            problems.append("缺少 resources.arsc")
        elif arsc.compress_type != zipfile.ZIP_STORED:
            problems.append("resources.arsc 被压缩了（method=%d），Android 11+ 会拒绝安装"
                            % arsc.compress_type)
        if "classes.dex" not in names:
            problems.append("缺少 classes.dex")
        if "AndroidManifest.xml" not in names:
            problems.append("缺少 AndroidManifest.xml")

    align_code, _align_out = capture([zipalign, "-c", "-p", "4", apk],
                                     os.path.join(BUILD, "verify-zipalign.log"))
    if align_code != 0:
        problems.append("zipalign 校验失败（未 4 字节对齐）")

    sign_code, sign_out = capture([java, "-jar", apksigner, "verify", "-v", apk],
                                  os.path.join(BUILD, "verify-sign.log"))
    if sign_code != 0:
        problems.append("签名校验失败")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in sign_out:
        problems.append("缺少 v2 签名")

    badge_code, badging = capture([aapt2, "dump", "badging", apk],
                                  os.path.join(BUILD, "verify-badging.log"))
    if badge_code != 0:
        problems.append("aapt2 dump badging 失败")
    if ("targetSdkVersion:'%s'" % TARGET_SDK) not in badging:
        problems.append("targetSdkVersion 不是 %s" % TARGET_SDK)
    if (("sdkVersion:'%s'" % MIN_SDK) not in badging
            and ("minSdkVersion:'%s'" % MIN_SDK) not in badging):
        problems.append("minSdkVersion 不是 %s" % MIN_SDK)
    for perm in ("READ_CALENDAR", "WRITE_CALENDAR"):
        if perm not in badging:
            problems.append("缺少权限 %s" % perm)

    print("\n[verify]")
    print("  resources.arsc : %s" % ("STORED ✓" if arsc and arsc.compress_type == 0 else "BROKEN"))
    print("  zipalign -c -p 4 : %s" % ("ok ✓" if align_code == 0 else "FAIL"))
    for line in sign_out.splitlines():
        if "scheme" in line:
            print("  " + line.strip())
    print("  badging targetSdk: %s" % ("%s ✓" % TARGET_SDK
                                      if ("targetSdkVersion:'%s'" % TARGET_SDK) in badging else "FAIL"))

    if problems:
        print("\nPROBLEMS:")
        for p in problems:
            print("  - " + p)
        raise SystemExit("APK verification failed")
    print("  all checks passed ✓")


def main():
    tc = load_toolchain()
    java = tc["java"]
    javac = tc["javac"]
    bt = tc["build_tools"]
    android_jar = tc["android_jar"]
    aapt2 = os.path.join(bt, "aapt2.exe")
    zipalign = os.path.join(bt, "zipalign.exe")
    d8_jar = os.path.join(bt, "lib", "d8.jar")
    apksigner_jar = os.path.join(bt, "lib", "apksigner.jar")

    for path in (aapt2, zipalign, d8_jar, apksigner_jar, android_jar, javac):
        if not os.path.exists(path):
            raise SystemExit("missing tool: %s" % path)

    for folder in (os.path.join(BUILD, "gen"), os.path.join(BUILD, "classes"),
                   os.path.join(BUILD, "dex")):
        if os.path.isdir(folder):
            shutil.rmtree(folder)
        os.makedirs(folder, exist_ok=True)
    os.makedirs(DIST, exist_ok=True)

    print("[1/8] icons", flush=True)
    ensure_icons()

    print("[2/8] aapt2 compile", flush=True)
    compiled = os.path.join(BUILD, "res.zip")
    run([aapt2, "compile", "--dir", RES, "-o", compiled])

    print("[3/8] aapt2 link (targetSdk %s)" % TARGET_SDK, flush=True)
    base_apk = os.path.join(BUILD, "base.apk")
    run([aapt2, "link", "-o", base_apk, "-I", android_jar, "--manifest", MANIFEST,
         "--java", os.path.join(BUILD, "gen"),
         "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
         "--version-code", str(VERSION_CODE), "--version-name", VERSION, compiled])

    print("[4/8] javac", flush=True)
    sources = []
    # app sources + the R.java generated by aapt2 link
    for base in (SRC, os.path.join(BUILD, "gen")):
        for root, _dirs, files in os.walk(base):
            for name in files:
                if name.endswith(".java"):
                    sources.append(os.path.join(root, name))
    classes = os.path.join(BUILD, "classes")
    arg_file = os.path.join(BUILD, "sources.txt")
    with open(arg_file, "w", encoding="utf-8") as fh:
        fh.write("\n".join('"%s"' % s.replace("\\", "\\\\") for s in sources))
    run([javac, "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-bootclasspath", android_jar, "-classpath", android_jar,
         "-nowarn", "-d", classes, "@" + arg_file])

    print("[5/8] d8", flush=True)
    class_files = []
    for root, _dirs, files in os.walk(classes):
        for name in files:
            if name.endswith(".class"):
                class_files.append(os.path.join(root, name))
    if not class_files:
        raise SystemExit("no .class files were produced")
    dex_out = os.path.join(BUILD, "dex")
    run([java, "-cp", d8_jar, "com.android.tools.r8.D8", "--lib", android_jar,
         "--min-api", MIN_SDK, "--output", dex_out] + class_files)

    print("[6/8] package", flush=True)
    unaligned = os.path.join(BUILD, "unaligned.apk")
    package_apk(base_apk, os.path.join(dex_out, "classes.dex"), unaligned)

    aligned = os.path.join(BUILD, "aligned.apk")
    run([zipalign, "-f", "-p", "4", unaligned, aligned])

    print("[7/8] sign", flush=True)
    if not os.path.exists(KEYSTORE):
        run([tc["keytool"], "-genkeypair", "-v", "-keystore", KEYSTORE,
             "-alias", KS_ALIAS, "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-storepass", KS_PASS, "-keypass", KS_PASS,
             "-dname", "CN=Timetable Importer, OU=DSH, O=Local, L=Local, ST=Local, C=CN"])
    apk_path = os.path.join(DIST, APK_NAME)
    if os.path.exists(apk_path):
        os.remove(apk_path)
    run([java, "-jar", apksigner_jar, "sign", "--ks", KEYSTORE, "--ks-pass", "pass:" + KS_PASS,
         "--key-pass", "pass:" + KS_PASS, "--ks-key-alias", KS_ALIAS,
         "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
         "--min-sdk-version", MIN_SDK,
         "--out", apk_path, aligned])

    print("[8/8] verify", flush=True)
    verify_apk(apk_path, tc)

    size = os.path.getsize(apk_path)
    print("\nAPK: %s (%.1f KB)" % (apk_path, size / 1024.0))
    return apk_path


if __name__ == "__main__":
    main()
