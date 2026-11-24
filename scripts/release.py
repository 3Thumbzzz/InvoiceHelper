"""Release build for Invoice Helper.

Sets versionName from RELEASE_VERSION (e.g. 1.4.0), bumps versionCode by one,
builds the signed release APK and copies it to dist/InvoiceHelper.apk.
Run from anywhere: py -3 scripts/release.py
"""
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"
APK_OUT = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
DIST = ROOT / "dist"


def fail(msg):
    print(f"release: {msg}", file=sys.stderr)
    sys.exit(1)


def set_version(version):
    text = GRADLE_FILE.read_text(encoding="utf-8")
    m = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not m or not re.search(r'versionName\s*=\s*"[^"]*"', text):
        fail("versionCode/versionName not found in app/build.gradle.kts")
    code = int(m.group(1)) + 1
    text = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {code}", text, count=1)
    text = re.sub(r'versionName\s*=\s*"[^"]*"', f'versionName = "{version}"', text, count=1)
    GRADLE_FILE.write_text(text, encoding="utf-8")
    print(f"release: versionName {version}, versionCode {code}")


def find_java_home():
    if os.environ.get("JAVA_HOME") and Path(os.environ["JAVA_HOME"]).is_dir():
        return os.environ["JAVA_HOME"]
    jdk_root = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "jdk"
    candidates = sorted(jdk_root.glob("jdk-17*")) if jdk_root.is_dir() else []
    if not candidates:
        fail("no JDK found (set JAVA_HOME or install one under %LOCALAPPDATA%\\Android\\jdk)")
    return str(candidates[-1])


def main():
    version = os.environ.get("RELEASE_VERSION", "").strip().lstrip("v")
    if not re.fullmatch(r"\d+(\.\d+){1,3}", version):
        fail(f"RELEASE_VERSION must look like 1.2.0 (got {version!r})")

    # Google sign-in only works with the app key; a debug-signed APK would be useless.
    if not (ROOT / "keystore" / "keystore.properties").exists():
        fail("keystore/keystore.properties missing; refusing to build a debug-signed APK")

    set_version(version)

    env = dict(os.environ, JAVA_HOME=find_java_home())
    gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    result = subprocess.run([str(gradlew), "-p", str(ROOT), "assembleRelease"], cwd=ROOT, env=env)
    if result.returncode != 0:
        fail(f"gradle build failed (exit {result.returncode})")
    if not APK_OUT.exists():
        fail(f"expected APK not found: {APK_OUT}")

    DIST.mkdir(exist_ok=True)
    target = DIST / "InvoiceHelper.apk"
    shutil.copy2(APK_OUT, target)
    print(f"release: built {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
