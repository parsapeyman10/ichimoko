from app.config import Settings


def test_secret_whitespace_is_normalized_without_appearing_in_status():
    settings = Settings(
        _env_file=None,
        twelve_data_api_key="  td-secret  ",
        fmp_api_key=" fmp-secret ",
        coingecko_demo_api_key=" cg-secret ",
        gemini_api_key=" gemini-secret ",
        openai_api_key=" openai-secret ",
    )

    assert settings.twelve_data_api_key == "td-secret"
    assert settings.fmp_api_key == "fmp-secret"
    assert settings.coingecko_demo_api_key == "cg-secret"
    assert settings.gemini_api_key == "gemini-secret"
    assert settings.openai_api_key == "openai-secret"
    status_text = repr(settings.integration_status)
    for secret in ("td-secret", "fmp-secret", "cg-secret", "gemini-secret", "openai-secret"):
        assert secret not in status_text


def test_invalid_linked_values_are_reported_without_network_calls():
    settings = Settings(
        _env_file=None,
        fa_news_rss_url="http://news.example.org/feed",
        fa_news_allowed_host="other.example.org",
        openai_api_key="key",
        openai_base_url="https://relay.example.org/v1?apikey=leak",
        ai_news_external_consent=True,
    )

    errors = settings.configuration_errors
    assert any("فید فارسی" in error for error in errors)
    assert any("AURUM_OPENAI_BASE_URL" in error for error in errors)
    assert all("leak" not in error for error in errors)
    assert settings.integration_status["openai"]["configured"] is True
