#!/usr/bin/env python3
"""
One-Click Runner — Aurum Edge
یک دکمه همه چیز بالا میاد: بک‌اند (8000) + فرانت (5173)

Usage:
  python run.py              # یک کلیک — هر دو روشن + مرورگر باز
  python run.py --no-browser # بدون باز کردن مرورگر
  python run.py --backend-only  # فقط بک‌اند
  python run.py --frontend-only # فقط فرانت
"""
import sys
import time
import subprocess
import webbrowser
from pathlib import Path
import argparse
import os

ROOT = Path(__file__).resolve().parent
BACKEND = ROOT / "backend"

if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))

parser = argparse.ArgumentParser(description="Aurum Edge One-Click")
parser.add_argument("--host", default="0.0.0.0")
parser.add_argument("--port", type=int, default=8000)
parser.add_argument("--no-browser", action="store_true")
parser.add_argument("--backend-only", action="store_true")
parser.add_argument("--frontend-only", action="store_true")
parser.add_argument("--reload", action="store_true", default=True)
args = parser.parse_args()

def ensure_frontend_deps():
    if not (ROOT / "node_modules").exists():
        print("📦 node_modules not found → npm install...")
        subprocess.run(["npm", "install"], cwd=ROOT, shell=True)

def start_backend():
    print(f"\n✅ Backend → http://127.0.0.1:{args.port}/docs")
    cmd = [sys.executable, "-m", "uvicorn", "app.main:app", "--app-dir", str(BACKEND), "--host", args.host, "--port", str(args.port)]
    if args.reload:
        cmd.append("--reload")
    return subprocess.Popen(cmd)

def start_frontend():
    print("✅ Frontend → http://127.0.0.1:5173")
    ensure_frontend_deps()
    return subprocess.Popen(["npm", "run", "dev"], cwd=ROOT, shell=True)

if __name__ == "__main__":
    try:
        import uvicorn  # noqa
    except ImportError:
        print("Installing backend deps...")
        subprocess.run([sys.executable, "-m", "pip", "install", "-r", str(BACKEND / "requirements.txt")], shell=True)

    backend_proc = None
    frontend_proc = None

    try:
        if not args.frontend_only:
            backend_proc = start_backend()
            time.sleep(1.5)

        if not args.backend_only:
            frontend_proc = start_frontend()

        if not args.no_browser:
            time.sleep(4)
            print("\n🌐 Opening browser...")
            try:
                webbrowser.open("http://127.0.0.1:5173")
                time.sleep(1)
                webbrowser.open(f"http://127.0.0.1:{args.port}/docs")
            except: pass

        print("\n" + "="*60)
        print("✅ هر دو روشن شد! نبند این پنجره رو")
        print("   فرانت:  http://127.0.0.1:5173")
        print(f"   بک‌اند: http://127.0.0.1:{args.port}/docs")
        print("   برای خاموش کردن: Ctrl+C")
        print("="*60 + "\n")

        while True:
            time.sleep(1)
            if backend_proc and backend_proc.poll() is not None:
                print("❌ Backend stopped unexpectedly!")
                break
            if frontend_proc and frontend_proc.poll() is not None:
                print("❌ Frontend stopped unexpectedly!")
                break

    except KeyboardInterrupt:
        print("\n🛑 Shutting down...")

    finally:
        print("Cleaning up...")
        for p in [backend_proc, frontend_proc]:
            if p and p.poll() is None:
                try:
                    p.terminate()
                    p.wait(timeout=5)
                except:
                    try: p.kill()
                    except: pass
        print("Bye!")
