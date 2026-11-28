package com.aurum.edge.data

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GithubBackupTransportTest {
    private val repo = "https://api.github.com/repos/owner/repo"
    private val branch = "arena/test-branch"
    private val path = "daily-backups/12345678-1234-1234-1234-123456789abc/2026-11-28.json"
    private val bytes = "encrypted bytes".toByteArray()
    private val expected = GithubBackupTransport.blobSha(bytes)

    @Test fun `hash matches git blob`() {
        assertEquals("b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0", GithubBackupTransport.blobSha("hello".toByteArray()))
    }
    @Test fun `new upload verifies destination and contains only ciphertext`() {
        var fileExists = false
        val result = GithubBackupTransport.upload(repo, branch, path, bytes, "2026-11-28") { method, url, body ->
            when {
                url.contains("/branches/") -> { assertTrue(url.contains("arena%2Ftest-branch")); 200 to "{}" }
                method == "PUT" -> {
                    val json = JSONObject(body!!)
                    assertEquals(branch, json.getString("branch"))
                    assertFalse(json.has("sha"))
                    assertArrayEquals(bytes, Base64.getDecoder().decode(json.getString("content")))
                    fileExists = true
                    201 to """{"content":{"sha":"$expected"}}"""
                }
                fileExists -> 200 to """{"sha":"$expected"}"""
                else -> 404 to "{}"
            }
        }
        assertEquals(expected, result)
    }
    @Test fun `conflicting update refetches current sha then verifies`() {
        var puts = 0
        var reads = 0
        val result = GithubBackupTransport.upload(repo, branch, path, bytes, "2026-11-28") { method, url, body ->
            when {
                url.contains("/branches/") -> 200 to "{}"
                method == "PUT" -> {
                    puts++
                    if (puts == 1) { assertEquals("old", JSONObject(body!!).getString("sha")); 409 to "{}" }
                    else { assertEquals("new", JSONObject(body!!).getString("sha")); 200 to """{"content":{"sha":"$expected"}}""" }
                }
                else -> { reads++; 200 to """{"sha":"${if (reads == 1) "old" else if (reads == 2) "new" else expected}"}""" }
            }
        }
        assertEquals(expected, result)
        assertEquals(2, puts)
    }
    @Test fun `missing branch or failed verification never reports success`() {
        var puts = 0
        assertTrue(runCatching { GithubBackupTransport.upload(repo, branch, path, bytes, "2026-11-28") { method, _, _ ->
            if (method == "PUT") puts++
            404 to "{}"
        } }.isFailure)
        assertEquals(0, puts)
        var read = 0
        assertTrue(runCatching { GithubBackupTransport.upload(repo, branch, path, bytes, "2026-11-28") { method, url, _ ->
            when {
                url.contains("/branches/") -> 200 to "{}"
                method == "PUT" -> 201 to """{"content":{"sha":"$expected"}}"""
                ++read == 1 -> 404 to "{}"
                else -> 200 to """{"sha":"other"}"""
            }
        } }.isFailure)
    }
}
