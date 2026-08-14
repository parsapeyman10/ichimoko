# Aurum Edge

A responsive XAU/USD intelligence terminal for **desktop, tablet, and mobile**. The repository includes a polished React PWA, a FastAPI real-time backend starter, deterministic Ichimoku signal logic, async news sentiment adapters, and an Expo WebView chart example.

The visible terminal starts in **paper/demo mode** with deterministic simulated candles. It never represents generated prices or recommendations as live broker data.

## Run the PC + mobile-web terminal

```bash
npm install
npm run dev
```

Open `http://localhost:5173`. Resize the browser or use device emulation to see the compact mobile terminal and bottom navigation. The PWA manifest also allows standalone installation in supported browsers.

Production build:

```bash
npm run build
npm run preview
```

## Run the API

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

API documentation is available at `http://localhost:8000/docs`. It uses a clearly marked synthetic stream unless a licensed provider key is configured and `AURUM_USE_SYNTHETIC_FEED=false`.

## What is implemented

- Responsive desktop/mobile trading terminal with live-updating candlesticks
- TradingView Lightweight Charts, Ichimoku 7/22/44, session VWAP, EMA200, volume, crosshair, zoom, and indicator toggle
- 1m / 5m switching and a realistic paper signal/risk workflow
- Expandable AI news intelligence cards and sentiment meter
- Interactive account-risk and position-size calculator
- FastAPI REST and backpressure-aware WebSocket market hub
- Twelve Data WebSocket adapter and synthetic local adapter
- Event-time 1m/5m candle builder with stale-tick handling
- Async OpenAI classifier, optional local FinBERT path, and deterministic fallback
- Closed-bar Ichimoku/VWAP/EMA/RSI/ATR fusion strategy with hard event/spread/volatility gates
- React Native WebView chart reference in `examples/mobile/`

## Repository map

```text
src/                         React/Vite PC + mobile PWA
  components/TradingChart   Live chart and studies
  lib/market                Demo candles and browser indicators
backend/app/
  main.py                    FastAPI routes, websocket hub, market pipeline
  services/market_feed.py    Provider adapters
  services/candle_builder.py Event-time candle generation
  services/indicators.py     Pure-Python indicators
  services/strategy.py       Signal fusion, SL/TP, exit policy
  services/sentiment.py      OpenAI / FinBERT / fallback NLP
  services/news_feed.py      Licensed API news aggregation
examples/mobile/             Expo + react-native-webview reference

docs/ARCHITECTURE.md         Full architecture, data, strategy, risk, and rollout design
```

## Important

This is an engineering reference and decision-support interface, not financial advice. A 1m/5m mobile system is not true high-frequency trading. Keep ingestion, signal generation, order state, risk limits, and broker reconciliation server-side. Before any live use, obtain market/news redistribution rights, validate broker contract specifications, run cost-aware walk-forward tests, complete security/legal review, and keep a broker-side kill switch.
