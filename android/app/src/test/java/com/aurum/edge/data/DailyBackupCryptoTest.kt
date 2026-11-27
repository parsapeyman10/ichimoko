package com.aurum.edge.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DailyBackupCryptoTest {
    @Test fun roundTripWrongPasswordAndTamper() {
        val raw = "private trades and neural state".toByteArray()
        val password = "correct horse 123".toCharArray()
        val first = DailyBackupCrypto.encrypt(raw, password)
        val second = DailyBackupCrypto.encrypt(raw, password)
        assertFalse(first.contentEquals(second))
        assertFalse(String(first).contains("private trades"))
        assertArrayEquals(raw, DailyBackupCrypto.decrypt(first, password))
        assertTrue(runCatching { DailyBackupCrypto.decrypt(first, "wrong password".toCharArray()) }.isFailure)
        val envelope = JSONObject(String(first))
        val bytes = Base64.getDecoder().decode(envelope.getString("ciphertext"))
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        envelope.put("ciphertext", Base64.getEncoder().encodeToString(bytes))
        assertTrue(runCatching { DailyBackupCrypto.decrypt(envelope.toString().toByteArray(), password) }.isFailure)
    }
    @Test fun serviceKeysNeverExported() {
        assertFalse(DailyBackupSnapshot.allowedSettingKey("td_api_key"))
        assertFalse(DailyBackupSnapshot.allowedSettingKey("td_symbol_keys_json"))
        assertFalse(DailyBackupSnapshot.allowedSettingKey("news_ai_client_key"))
        assertTrue(DailyBackupSnapshot.allowedSettingKey("btc_base_strategy_config_json"))
        assertTrue(DailyBackupSnapshot.allowedSettingKey("btc_custom_strategies_json"))
    }
    @Test fun decryptsPythonEnvelopeWithUnicodePassphrase() {
        val envelope = """{"format":"AurumDailyBackup","version":1,"iterations":310000,"salt":"AAECAwQFBgcICQoLDA0ODw==","nonce":"AAECAwQFBgcICQoL","ciphertext":"A4HzWRcqb3NLbJyrkEDlYcJ/zqwSwjeBxidHmJ8cmWzOWaw="}"""
        assertEquals("cross-language-test", String(DailyBackupCrypto.decrypt(envelope.toByteArray(), "رمز عبور آزمایشی".toCharArray())))
    }

}
