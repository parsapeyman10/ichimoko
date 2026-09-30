#!/usr/bin/env python3
"""One-click local runner for the Aurum Edge web terminal.

It starts the FastAPI backend and Vite frontend as separate child processes. Browser requests
use Vite's relative /api and /ws proxies, so the terminal also works when the backend port is
changed for a local development session.

Usage:
  python run.py                    # backend (:8000) + frontend (:5173)
  python run.py --reload            # same, with FastAPI autoreload
  python run.py --no-browser        # do not open local browser tabs
  python run.py --backend-only      # only the FastAPI API
  python run.py --frontend-only     # only the Vite terminal (expects an API on --port)
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BACKEND = ROOT / "backend"
FRONTEND_PORT = 5173


def command_exists(command: str) -> bool:
    return shutil.which(command) is not None


def ensure_frontend_deps() -> None:
    if not command_exists("npm"):
        raise RuntimeError("npm پیدا نشد. Node.js 18 یا جدیدتر را نصب کن و دوباره اجرا کن.")
    if not (ROOT / "node_modules").exists():
        command = ["npm", "ci"] if (ROOT / "package-lock.json").exists() else ["npm", "install"]
        print(f"📦 node_modules پیدا نشد → {' '.join(command)}…")
        subprocess.run(command, cwd=ROOT, check=True)


def ensure_backend_deps() -> None:
    try:
        import uvicorn  # noqa: F401
    except ImportError:
        print("📦 وابستگی‌های بک‌اند پیدا نشد → نصب requirements…")
        subprocess.run(
            [sys.executable, "-m", "pip", "install", "-r", str(BACKEND / "requirements.txt")],
            cwd=ROOT,
            check=True,
        )


def start_backend(args: argparse.Namespace) -> subprocess.Popen:
    command = [
        sys.executable,
        "-m",
        "uvicorn",
        "app.main:app",
        "--app-dir",
        str(BACKEND),
        "--host",
        args.host,
        "--port",
        str(args.port),
    ]
    if args.reload:
        command.append("--reload")
    print(f"✅ Backend → http://127.0.0.1:{args.port}/docs")
    return subprocess.Popen(command, cwd=ROOT)


def start_frontend(args: argparse.Namespace) -> subprocess.Popen:
    ensure_frontend_deps()
    environment = os.environ.copy()
    # This is read by vite.config.ts only; it is never bundled into browser code.
    environment["AURUM_BACKEND_PROXY"] = f"http://127.0.0.1:{args.port}"
    print(f"✅ Frontend → http://127.0.0.1:{FRONTEND_PORT}")
    return subprocess.Popen(["npm", "run", "dev"], cwd=ROOT, env=environment)


def stop(process: subprocess.Popen | None) -> None:
    if process is None or process.poll() is not None:
        return
    try:
        process.terminate()
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Aurum Edge one-click local runner")
    parser.add_argument("--host", default="0.0.0.0", help="bind host for the backend (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8000, help="backend port, 1–65535 (default: 8000)")
    parser.add_argument("--no-browser", action="store_true", help="do not open browser tabs")
    parser.add_argument("--reload", action="store_true", help="enable FastAPI autoreload")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--backend-only", action="store_true", help="start only the FastAPI backend")
    mode.add_argument("--frontend-only", action="store_true", help="start only the Vite frontend")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port باید عددی بین 1 و 65535 باشد")
    return args


def main() -> int:
    args = parse_args()
    backend_process: subprocess.Popen | None = None
    frontend_process: subprocess.Popen | None = None

    try:
        if not args.frontend_only:
            ensure_backend_deps()
            backend_process = start_backend(args)
            time.sleep(1.5)

        if not args.backend_only:
            frontend_process = start_frontend(args)

        if not args.no_browser:
            time.sleep(2)
            print("\n🌐 Opening browser…")
            if frontend_process:
                webbrowser.open(f"http://127.0.0.1:{FRONTEND_PORT}")
            if backend_process:
                webbrowser.open(f"http://127.0.0.1:{args.port}/docs")

        print("\n" + "=" * 60)
        print("✅ سرویس‌ها اجرا شدند — برای توقف Ctrl+C بزن")
        if frontend_process:
            print(f"   ترمینال: http://127.0.0.1:{FRONTEND_PORT}")
        if backend_process:
            print(f"   API:      http://127.0.0.1:{args.port}/docs")
        print("=" * 60 + "\n")

        while True:
            time.sleep(1)
            if backend_process and backend_process.poll() is not None:
                print("❌ Backend unexpectedly stopped.")
                return backend_process.returncode or 1
            if frontend_process and frontend_process.poll() is not None:
                print("❌ Frontend unexpectedly stopped.")
                return frontend_process.returncode or 1
    except KeyboardInterrupt:
        print("\n🛑 Stopping services…")
        return 0
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"❌ Startup failed: {error}", file=sys.stderr)
        return 1
    finally:
        stop(frontend_process)
        stop(backend_process)


if __name__ == "__main__":
    raise SystemExit(main())
