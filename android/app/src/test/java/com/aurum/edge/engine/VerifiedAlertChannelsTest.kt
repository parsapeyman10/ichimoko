package com.aurum.edge.engine

import android.app.NotificationManager
import com.aurum.edge.notify.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A SAF file must never be handed to SystemUI as a notification-channel sound. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VerifiedAlertChannelsTest {
    @Test fun defaultAndFileChannelsAreDistinctAndFileChannelHasNoSystemSound() {
        val context = RuntimeEnvironment.getApplication()
        Notifier.ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)!!
        val system = manager.getNotificationChannel(Notifier.CHANNEL_VERIFIED_DEFAULT)
        val file = manager.getNotificationChannel(Notifier.CHANNEL_VERIFIED_FILE)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, system.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, file.importance)
        assertNotNull(system.sound)
        assertNull(file.sound)
    }
}
