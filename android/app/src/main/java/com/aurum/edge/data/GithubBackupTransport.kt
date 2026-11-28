package com.aurum.edge.data

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** GitHub Contents API writes are verified by Git blob SHA. The caller supplies ciphertext only. */
internal object GithubBackupTransport {
    internal fun blobSha(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-1")
        hash.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        return hash.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** The GET for the branch prevents treating a missing branch or missing access as a new file. */
    internal fun upload(
        repoUrl: String, branch: String, path: String, ciphertext: ByteArray, date: String,
        request: (String, String, String?) -> Pair<Int, String>,
    ): String {
        require(path.matches(Regex("daily-backups/[a-f0-9-]{36}/[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")))
        require(ciphertext.isNotEmpty())
        val base = repoUrl.toHttpUrl()
        val branchUrl = base.newBuilder().addPathSegment("branches").addPathSegment(branch).build().toString()
        val branchResult = request("GET", branchUrl, null)
        if (branchResult.first != 200) throw IOException("مخزن یا شاخه در دسترس نیست (HTTP ${branchResult.first})؛ نصب GitHub App و نام شاخه را بررسی کنید")
        val fileUrl = base.newBuilder().addPathSegment("contents").apply { path.split('/').forEach { addPathSegment(it) } }.build()
        val readUrl = fileUrl.newBuilder().addQueryParameter("ref", branch).build().toString()
        val expected = blobSha(ciphertext)
        repeat(3) { attempt ->
            val (getCode, getBody) = request("GET", readUrl, null)
            val oldSha = when (getCode) {
                200 -> JSONObject(getBody).getString("sha")
                404 -> null
                else -> throw IOException("خواندن مقصد پشتیبان ناموفق: HTTP $getCode")
            }
            val body = JSONObject().put("message", "Encrypted daily backup $date")
                .put("branch", branch).put("content", Base64.getEncoder().encodeToString(ciphertext))
            if (oldSha != null) body.put("sha", oldSha)
            val (putCode, putBody) = request("PUT", fileUrl.toString(), body.toString())
            if (putCode == 409 && attempt < 2) return@repeat
            if (putCode !in 200..299) throw IOException("ارسال پشتیبان ناموفق: HTTP $putCode؛ دسترسی نوشتن و تداخل را بررسی کنید")
            val written = JSONObject(putBody).optJSONObject("content")?.optString("sha")
            if (written != expected) throw IOException("هش فایل تأیید نشد؛ موفقیت ارسال نامعلوم است")
            val (verifyCode, verifyBody) = request("GET", readUrl, null)
            if (verifyCode != 200 || JSONObject(verifyBody).optString("sha") != expected)
                throw IOException("بازخوانی فایل مقصد ناموفق؛ موفقیت ارسال نامعلوم است")
            return expected
        }
        throw IOException("تداخل تغییر فایل پس از چند تلاش")
    }
}
