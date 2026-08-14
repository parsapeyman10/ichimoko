# Aurum Edge API

FastAPI starter for normalized XAU/USD ticks, event-time 1m/5m candles, news classification, and deterministic strategy evaluation.

## Environment

All variables use the `AURUM_` prefix. See `.env.example`. Synthetic mode is the safe default.

## Local development

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
pytest
```

The in-process `MarketHub` is suitable for a local preview. In production, put normalized events on NATS JetStream/Redpanda and make socket gateways stateless consumers. Store completed bars and decisions in TimescaleDB; Redis is only hot/reconstructible state.
