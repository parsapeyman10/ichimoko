#!/usr/bin/env python3
"""
Quick runner for Aurum Edge — works from project root on Windows/macOS/Linux
Usage:
  python run.py
  python run.py --port 8001
  python run.py --reload
"""
import sys
from pathlib import Path

# Ensure backend is on path
ROOT = Path(__file__).resolve().parent
BACKEND = ROOT / "backend"
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))

import argparse

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--reload", action="store_true", help="auto-reload on code change")
    args = parser.parse_args()

    try:
        import uvicorn
    except ImportError:
        print("uvicorn not found. Install deps: pip install -r backend/requirements.txt")
        sys.exit(1)

    print(f"\n✅ Starting Aurum Edge API on http://{args.host}:{args.port}")
    print(f"   Docs: http://127.0.0.1:{args.port}/docs")
    print(f"   Health: http://127.0.0.1:{args.port}/api/v1/health")
    print(f"   Trades YTD: http://127.0.0.1:{args.port}/api/v1/backtest/trades-ytd?timeframe=3m\n")

    uvicorn.run("app.main:app", host=args.host, port=args.port, reload=args.reload, app_dir=str(BACKEND))
