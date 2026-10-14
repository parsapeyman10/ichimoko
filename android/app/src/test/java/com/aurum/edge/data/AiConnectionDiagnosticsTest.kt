package com.aurum.edge.data

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConnectionDiagnosticsTest {
    @Test fun `unavailable system network is not reported as rejected API key and HTTP phase is visible`() {
        val unavailable = AiConnectionDiagnostics.describe(NetworkUnavailable())
        assertTrue(unavailable.contains("هنوز پاسخی"))
        val failure = GeminiHttpDiagnostics.failure(403, "secret response", GeminiHttpDiagnostics.Stage.MODELS)
        val description = AiConnectionDiagnostics.describe(failure)
        assertTrue(description.contains("فهرست مدل"))
        assertTrue(description.contains("403"))
        assertFalse(description.contains("secret"))
    }

    @Test fun `network errors are actionable without leaking URLs or secrets`() {
        val secret = "sk-secret-do-not-log"
        val message = AiConnectionDiagnostics.describe(UnknownHostException("https://bad.example/$secret"))
        assertTrue(message.contains("DNS"))
        assertFalse(message.contains(secret))
        assertTrue(AiConnectionDiagnostics.describe(SocketTimeoutException()).contains("مهلت"))
    }

    @Test fun `http errors identify likely cause without response body`() {
        assertTrue(AiConnectionDiagnostics.describe(IllegalStateException("HTTP 404 sk-secret"))
            .contains("مدل"))
        assertTrue(AiConnectionDiagnostics.describe(IllegalStateException("HTTP 402 sk-secret"))
            .contains("GLM"))
        assertFalse(AiConnectionDiagnostics.describe(IllegalStateException("HTTP 402 sk-secret"))
            .contains("sk-secret"))
        assertTrue(AiConnectionDiagnostics.describe(IllegalStateException("HTTP 429 quota"))
            .contains("سهمیه"))
        assertFalse(AiConnectionDiagnostics.describe(IllegalStateException("HTTP 500 sk-secret"))
            .contains("sk-secret"))
    }
}
