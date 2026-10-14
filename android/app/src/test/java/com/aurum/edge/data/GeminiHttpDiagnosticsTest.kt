package com.aurum.edge.data

import org.junit.Assert.*
import org.junit.Test

class GeminiHttpDiagnosticsTest {
    @Test fun `Google structured reasons distinguish permission key project region and quota without exposing secrets`() {
        val reasons = mapOf("API_KEY_INVALID" to GeminiHttpDiagnostics.Cause.KEY,
            "API_KEY_HTTP_REFERRER_BLOCKED" to GeminiHttpDiagnostics.Cause.RESTRICTION,
            "API_KEY_ANDROID_APP_BLOCKED" to GeminiHttpDiagnostics.Cause.RESTRICTION,
            "SERVICE_DISABLED" to GeminiHttpDiagnostics.Cause.SERVICE,
            "BILLING_DISABLED" to GeminiHttpDiagnostics.Cause.BILLING)
        reasons.forEach { (reason, expected) ->
            val error = GeminiHttpDiagnostics.failure(403,
                """{"error":{"message":"synthetic-secret-do-not-echo","details":[{"reason":"$reason","metadata":{"key":"synthetic-secret-do-not-echo"}}]}}""")
            assertEquals(expected, error.reason)
            assertFalse(AiConnectionDiagnostics.describe(error).contains("synthetic-secret"))
            assertFalse(error.toString().contains("synthetic-secret"))
        }
        assertEquals(GeminiHttpDiagnostics.Cause.PERMISSION, GeminiHttpDiagnostics.failure(403,"<html>denied</html>").reason)
        assertEquals(GeminiHttpDiagnostics.Cause.REGION, GeminiHttpDiagnostics.failure(400,
            """{"error":{"message":"User location is not supported for the API use."}}""").reason)
        assertEquals(GeminiHttpDiagnostics.Cause.MODEL, GeminiHttpDiagnostics.failure(404,"{}").reason)
        assertEquals(GeminiHttpDiagnostics.Cause.QUOTA, GeminiHttpDiagnostics.failure(429,"{}").reason)
    }
}
