from uuid import uuid4

from fastapi.testclient import TestClient

from app.main import app
from app.services.execution_gate import OrderIntent, preflight


INTENT = {
    "venue": "NOBITEX", "symbol": "BTC/IRT", "side": "BUY", "quantity": 0.1,
    "stop_loss": 100.0, "idempotency_key": str(uuid4()),
}


def test_live_order_is_impossible_even_with_a_well_formed_intent():
    client = TestClient(app)
    status = client.get("/api/v1/execution/status").json()
    assert status["kill_switch"] and not status["can_submit"]
    assert not status["venues"]["NOBITEX"]["connected"]
    assert not status["venues"]["MT5_BRIDGE"]["connected"]

    check = client.post("/api/v1/execution/preflight", json=INTENT).json()
    assert check["allowed"] is False
    assert any("خبر" in reason for reason in check["blockers"])
    assert client.post("/api/v1/execution/orders", json=INTENT).status_code == 503


def test_api_rejects_secrets_invalid_size_and_client_approved_flags():
    client = TestClient(app)
    assert client.post("/api/v1/execution/orders", json={**INTENT, "api_key": "never-send-to-server"}).status_code == 422
    assert client.post("/api/v1/execution/orders", json={**INTENT, "safe_to_trade": True}).status_code == 422
    assert client.post("/api/v1/execution/orders", json={**INTENT, "quantity": -1}).status_code == 422
    assert client.post("/api/v1/execution/orders", json={**INTENT, "idempotency_key": "retry"}).status_code == 422
    assert preflight(OrderIntent(**{**INTENT, "venue": "MT5_BRIDGE"}))["allowed"] is False
