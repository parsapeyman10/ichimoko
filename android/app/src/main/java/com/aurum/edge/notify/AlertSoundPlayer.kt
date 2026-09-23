package com.aurum.edge.notify

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns

/**
 * One brief notification-usage clip from a persistable SAF document. Playback is done by the
 * foreground app/service, never by SystemUI (which cannot read a private SAF grant). DND and
 * notification volume are controlled by Android; this cannot override muted device settings.
 */
object AlertSoundPlayer {
    private const val MAX_FILE_BYTES = 20_000_000L
    private const val MAX_PLAY_MS = 10_000L
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var active: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    fun select(context: Context, uri: Uri): String {
        require(uri.scheme == "content") { "فقط فایل صوتی انتخاب‌شده از فایل‌های گوشی پذیرفته می‌شود" }
        require(context.contentResolver.getType(uri)?.startsWith("audio/") == true) {
            "نوع فایل باید صوتی باشد"
        }
        val nameAndSize = context.contentResolver.query(uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor: Cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                (if (nameIndex >= 0) cursor.getString(nameIndex) else null) to size
            }
        } ?: throw IllegalArgumentException("اندازه و نام فایل قابل بررسی نیست")
        require(nameAndSize.second != null && nameAndSize.second!! in 1L..MAX_FILE_BYTES) {
            "فایل صوتی محلی باید قابل خواندن و حداکثر ۲۰ مگابایت باشد"
        }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        require(canOpen(context, uri.toString())) { "دسترسی به فایل صوتی برقرار نشد" }
        return nameAndSize.first?.take(70)?.ifBlank { "فایل صوتی انتخابی" } ?: "فایل صوتی انتخابی"
    }

    fun canOpen(context: Context, uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content" || context.contentResolver.getType(uri)?.startsWith("audio/") != true) return false
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /** Returns whether playback was queued; MediaPlayer may still fail asynchronously. */
    fun play(context: Context, uriString: String): Boolean {
        if (!canOpen(context, uriString)) return false
        val appContext = context.applicationContext
        val uri = Uri.parse(uriString)
        main.post {
            releaseActive()
            runCatching {
                val manager = appContext.getSystemService(AudioManager::class.java) ?: return@runCatching
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes).build()
                if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return@runCatching
                audioManager = manager
                focus = request
                val player = MediaPlayer()
                active = player
                player.setAudioAttributes(attributes)
                player.setDataSource(appContext, uri)
                player.setOnPreparedListener { ready ->
                    if (active === ready) {
                        ready.start()
                        main.postDelayed({ if (active === ready) releaseActive() }, MAX_PLAY_MS)
                    }
                }
                player.setOnCompletionListener { if (active === it) releaseActive() }
                player.setOnErrorListener { failed, _, _ ->
                    if (active === failed) releaseActive()
                    true
                }
                player.prepareAsync()
            }.onFailure { releaseActive() }
        }
        return true
    }

    fun stop() { main.post { releaseActive() } }

    private fun releaseActive() {
        active?.runCatching { release() }
        active = null
        val request = focus
        if (request != null) audioManager?.abandonAudioFocusRequest(request)
        focus = null
        audioManager = null
    }
}
