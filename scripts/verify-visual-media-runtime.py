#!/usr/bin/env python3
"""Run Canvas against the full image fixture tree inside Android's app_process."""
from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "android-sdk")))
ANDROID = SDK / "platforms/android-36/android.jar"
BUILD_TOOLS = SDK / "build-tools/36.0.0"
APP = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
OUT = ROOT / "build/visual-media-runtime"
CLASSES = OUT / "classes"
DEX = OUT / "dex"
APP_DEX = OUT / "app-dex"
NATIVE = OUT / "native"
FIXTURES = ROOT / "filesystem-test-images/images"
KOTLIN_CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.20"


def one_jar(directory: Path) -> Path:
    matches = sorted(directory.glob("*/*.jar"))
    if not matches:
        raise SystemExit(f"Missing runtime dependency jar under {directory}")
    return matches[-1]


def device_abi() -> str:
    result = subprocess.run(["getprop", "ro.product.cpu.abi"], capture_output=True, text=True)
    abi = result.stdout.strip()
    if not abi:
        raise SystemExit("Could not determine Android device ABI with getprop")
    return abi


def extract_runtime_payload(abi: str) -> list[Path]:
    APP_DEX.mkdir(parents=True, exist_ok=True)
    native_dir = NATIVE / abi
    native_dir.mkdir(parents=True, exist_ok=True)
    for old_library in native_dir.glob("*.so"):
        old_library.unlink()
    dex_paths: list[tuple[int, Path]] = []
    dex_pattern = re.compile(r"classes(\d*)\.dex$")
    with zipfile.ZipFile(APP) as archive:
        for name in archive.namelist():
            match = dex_pattern.fullmatch(Path(name).name)
            if match:
                index = int(match.group(1) or "1")
                target = APP_DEX / f"classes-{index}.jar"
                if target.exists():
                    target.chmod(0o644)
                with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as dex_jar:
                    dex_jar.writestr("classes.dex", archive.read(name))
                target.chmod(0o444)
                dex_paths.append((index, target))
            elif name.startswith(f"lib/{abi}/") and name.endswith(".so"):
                target = native_dir / Path(name).name
                target.write_bytes(archive.read(name))
    if not dex_paths:
        raise SystemExit(f"No classes DEX files found in {APP}")
    if not list(native_dir.glob("*.so")):
        raise SystemExit(f"No native libraries for device ABI {abi} in {APP}")
    return [path for _, path in sorted(dex_paths)]


def main() -> int:
    for path in (ANDROID, BUILD_TOOLS / "d8", APP, FIXTURES):
        if not path.exists():
            raise SystemExit(f"Missing {path}; build :app:assembleDebug first")
    kotlin = one_jar(KOTLIN_CACHE)
    stream = ROOT / "libraries/libinfinistream/stream-core/build/libs/stream-core-0.1.0.jar"
    canvas = ROOT / "libraries/libinfinicanvas/canvas-android/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar"
    for path in (stream, canvas):
        if not path.exists():
            raise SystemExit(f"Missing {path}; build :app:assembleDebug first")

    OUT.mkdir(parents=True, exist_ok=True)
    CLASSES.mkdir(parents=True, exist_ok=True)
    DEX.mkdir(exist_ok=True)
    for path in CLASSES.glob("VisualMediaRuntimeCheck*.class"):
        path.unlink()
    result_dex = DEX / "classes.dex"
    if result_dex.exists():
        result_dex.chmod(0o644)
        result_dex.unlink()

    classpath = [ANDROID, kotlin, stream, canvas]
    subprocess.run(
        ["javac", "-classpath", os.pathsep.join(map(str, classpath)), "-d", str(CLASSES),
         str(ROOT / "scripts/runtime/VisualMediaRuntimeCheck.java")],
        check=True,
    )
    subprocess.run(
        [str(BUILD_TOOLS / "d8"), "--min-api", "26", "--lib", str(ANDROID), "--output", str(DEX)] +
        [item for dependency in classpath[1:] for item in ("--classpath", str(dependency))] +
        [str(path) for path in sorted(CLASSES.glob("VisualMediaRuntimeCheck*.class"))],
        check=True,
    )
    result_dex.chmod(0o444)

    abi = device_abi()
    app_dex = extract_runtime_payload(abi)
    runtime_apk = OUT / "visual-media-runtime.apk"
    if runtime_apk.exists():
        runtime_apk.chmod(0o644)
    shutil.copyfile(APP, runtime_apk)
    runtime_apk.chmod(0o444)
    env = os.environ.copy()
    env.pop("LD_PRELOAD", None)
    env["LD_LIBRARY_PATH"] = str(NATIVE / abi)
    env["CLASSPATH"] = os.pathsep.join(
        [str(result_dex), *(str(path) for path in app_dex), str(runtime_apk)]
    )
    log = OUT / "visual-media-runtime.log"
    runner = Path("/system/bin/app_process64")
    if not runner.exists():
        raise SystemExit(f"Android app_process not found at {runner}; run this on the Android device")
    with log.open("w") as output:
        run = subprocess.run(
            [str(runner), f"-Djava.library.path={NATIVE / abi}", "/system/bin",
             "VisualMediaRuntimeCheck", str(FIXTURES)],
            env=env,
            stdout=output,
            stderr=subprocess.STDOUT,
            timeout=240,
        )
    print(log.read_text(errors="replace"), end="")
    with APP.open("rb") as source:
        print("APK SHA-256: " + hashlib.file_digest(source, "sha256").hexdigest())
    print(f"Loaded APK DEX files and native libraries for {abi}")
    return run.returncode


if __name__ == "__main__":
    raise SystemExit(main())
