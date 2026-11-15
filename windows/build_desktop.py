#!/usr/bin/env python3
"""Build the Aurum Edge desktop application on the current operating system.

PyInstaller packages only for its *current* host. Run this script separately on
Windows, macOS and Linux to produce the native artifact for each platform.
"""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
import venv
from pathlib import Path

APP_DIR = Path(__file__).resolve().parent
APP_SOURCE = APP_DIR / "aurum_edge.py"
CORE_SOURCE = APP_DIR / "aurum_core.py"
TEST_SOURCE = APP_DIR / "test_aurum_core.py"
VENV_DIR = APP_DIR / ".build-venv"


class BuildError(RuntimeError):
    pass


def host_key() -> str:
    system = platform.system().lower()
    if system == "windows":
        return "windows"
    if system == "darwin":
        return "macos"
    if system == "linux":
        return "linux"
    raise BuildError(f"Unsupported desktop platform: {platform.system()}")


def executable_in(environment: Path) -> Path:
    return environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def run(command: list[str], *, cwd: Path = APP_DIR) -> None:
    printable = subprocess.list2cmdline(command) if os.name == "nt" else " ".join(command)
    print(f"\n> {printable}")
    completed = subprocess.run(command, cwd=cwd, check=False)
    if completed.returncode:
        raise BuildError(f"Command failed with exit code {completed.returncode}: {command[0]}")


def validate_source() -> None:
    if sys.version_info < (3, 11):
        raise BuildError("Python 3.11 or newer is required.")
    for required in (APP_SOURCE, CORE_SOURCE, TEST_SOURCE):
        if not required.is_file():
            raise BuildError(f"Required source file is missing: {required.name}")
    banned_suffixes = {".cpp", ".cc", ".cxx", ".h", ".hpp"}
    native_sources = [path.name for path in APP_DIR.rglob("*") if path.is_file() and (path.suffix.lower() in banned_suffixes or path.name == "CMakeLists.txt")]
    if native_sources:
        raise BuildError("The Python desktop source must not contain native build files: " + ", ".join(native_sources))


def run_core_checks(python: Path) -> None:
    run([str(python), "-m", "py_compile", APP_SOURCE.name, CORE_SOURCE.name, TEST_SOURCE.name])
    run([str(python), TEST_SOURCE.name])


def assert_tkinter(python: Path, label: str) -> None:
    command = [str(python), "-c", "import tkinter; print('Tkinter is available for the desktop build.')"]
    printable = subprocess.list2cmdline(command) if os.name == "nt" else " ".join(command)
    print(f"\n> {printable}")
    completed = subprocess.run(command, cwd=APP_DIR, check=False)
    if not completed.returncode:
        return
    access_violation = completed.returncode in {3221225477, -1073741819}
    if access_violation:
        raise BuildError(
            f"Tkinter crashed in {label} (Windows access violation 0xC0000005). "
            "If this is the local build environment, it can be recreated automatically. "
            "If it is the host Python, repair or reinstall the official 64-bit Python with Tcl/Tk enabled, "
            "then rerun the build."
        )
    raise BuildError(f"Tkinter is unavailable in {label} (exit code {completed.returncode}). Install Python with Tcl/Tk enabled.")


def prepare_environment(*, recreate: bool = False) -> Path:
    if recreate and VENV_DIR.exists():
        print("=== Recreating the local build virtual environment ===")
        shutil.rmtree(VENV_DIR)
    python = executable_in(VENV_DIR)
    if not python.is_file():
        print("=== Creating local build virtual environment ===")
        venv.EnvBuilder(with_pip=True, clear=False).create(VENV_DIR)
    python = executable_in(VENV_DIR)
    if not python.is_file():
        raise BuildError("The local virtual environment did not provide a Python executable.")
    return python


def prepare_tkinter_environment(*, recreate: bool = False) -> Path:
    """Recreate a stale venv once when host Python's Tkinter itself is healthy."""
    python = prepare_environment(recreate=recreate)
    try:
        assert_tkinter(python, "the local build environment")
        return python
    except BuildError as first_error:
        print(f"Local build environment Tkinter check failed: {first_error}", file=sys.stderr)
        python = prepare_environment(recreate=True)
        assert_tkinter(python, "the recreated local build environment")
        return python


def package(python: Path, host: str) -> Path:
    names = {"windows": "Aurum_Edge_Windows", "macos": "Aurum_Edge_macOS", "linux": "Aurum_Edge_Linux"}
    name = names[host]
    output_root = APP_DIR / "dist-local" / host
    work_root = APP_DIR / "build-local" / host
    spec_root = APP_DIR / "spec-local" / host
    output_root.mkdir(parents=True, exist_ok=True)
    print(f"=== Packaging a native {host} desktop artifact ===")
    run([
        str(python), "-m", "PyInstaller", "--noconfirm", "--clean", "--onefile", "--windowed",
        "--name", name, "--paths", ".", "--distpath", str(output_root),
        "--workpath", str(work_root), "--specpath", str(spec_root), APP_SOURCE.name,
    ])
    return output_root / (f"{name}.exe" if host == "windows" else name)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build/check the cross-platform Aurum Edge desktop app.")
    parser.add_argument("--check", action="store_true", help="Run portable source checks only; do not create a venv or package an app.")
    parser.add_argument("--recreate-venv", action="store_true", help="Discard the generated local build venv before packaging.")
    arguments = parser.parse_args()
    try:
        validate_source()
        print("=== Aurum Edge Python desktop ===")
        print(f"Host: {platform.system()} ({platform.machine() or 'unknown architecture'})")
        print(f"Source: {APP_DIR}")
        if arguments.check:
            print("=== Running portable deterministic checks ===")
            run_core_checks(Path(sys.executable))
            print("=== CHECK PASSED ===")
            return 0
        host = host_key()
        print("=== Checking Tkinter in the host Python ===")
        assert_tkinter(Path(sys.executable), "the host Python")
        python = prepare_tkinter_environment(recreate=arguments.recreate_venv)
        print("=== Running deterministic Python core tests ===")
        run_core_checks(python)
        print("=== Installing the local packaging dependency ===")
        run([str(python), "-m", "pip", "install", "--upgrade", "pip"])
        run([str(python), "-m", "pip", "install", "--upgrade", "pyinstaller>=6.0,<7.0"])
        artifact = package(python, host)
        print("\n=== SUCCESS ===")
        print(f"Native {host} artifact: {artifact}")
        print("This app is historical analysis and paper journaling only; it never sends live orders.")
        return 0
    except BuildError as error:
        print(f"\n=== BUILD FAILED ===\n{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
