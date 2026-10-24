package com.aurum.edge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater's install gates, exercised without Android. Each case is a way an in-app update can
 * stop, and the assertion is the message the user would read on the update screen.
 */
class UpdateVerificationTest {

    private val pin = "137f3f98f6c85fdd8671d1d2099b095ed82f3f91594ac9e7b1ba22c264ed4a04"
    private val other = "a".repeat(64)

    @Test fun `a signer that matches the install and the pin may be installed`() {
        assertNull(UpdateVerification.signerProblem(setOf(pin), setOf(pin), enforcePin = true, pinned = pin))
    }

    @Test fun `an unreadable or different signer stops the install`() {
        assertEquals(UpdateVerification.NO_INSTALLED_SIGNER,
            UpdateVerification.signerProblem(emptySet(), setOf(pin), true, pin))
        assertNotNull(UpdateVerification.signerProblem(setOf(pin), emptySet(), true, pin))
        val mismatch = UpdateVerification.signerProblem(setOf(pin), setOf(other), true, pin)
        assertNotNull(mismatch)
        assertTrue("the message must name both fingerprints",
            mismatch!!.contains(pin.take(8)) && mismatch.contains(other.take(8)))
    }

    @Test fun `an extra signer fails closed`() {
        assertNotNull(UpdateVerification.signerProblem(setOf(pin), setOf(pin, other), true, pin))
    }

    @Test fun `an APK outside the pinned release certificate is refused`() {
        assertNotNull(UpdateVerification.signerProblem(setOf(other), setOf(other), enforcePin = true, pinned = pin))
        assertNull("a debug build is not pinned to the release certificate",
            UpdateVerification.signerProblem(setOf(other), setOf(other), enforcePin = false, pinned = pin))
    }

    @Test fun `the published signer is an acceptable fallback only when it matches the phone`() {
        assertNull(UpdateVerification.metadataSignerProblem(setOf(pin), "sha256:${pin.uppercase()}", true, pin))
        assertEquals(UpdateVerification.NO_INSTALLED_SIGNER,
            UpdateVerification.metadataSignerProblem(emptySet(), pin, true, pin))
    }

    @Test fun `a fallback without a published signer fails closed`() {
        assertNotNull(UpdateVerification.metadataSignerProblem(setOf(pin), null, true, pin))
        assertNotNull(UpdateVerification.metadataSignerProblem(setOf(pin), "not-a-digest", true, pin))
        assertNotNull(UpdateVerification.metadataSignerProblem(setOf(pin), other, true, pin))
        assertNotNull(UpdateVerification.metadataSignerProblem(setOf(pin), other, enforcePin = false, pinned = pin))
    }

    @Test fun `the version gate needs a code that is strictly newer than the running app`() {
        val newer = UpdateVerification.versionVerdict(677, 677, 676)
        assertNull(newer.problem)
        assertEquals(677L, newer.code)

        assertEquals("این فایل نسخهٔ جدیدتری از نصب فعلی نیست",
            UpdateVerification.versionVerdict(676, 676, 676).problem)
        assertEquals("این فایل نسخهٔ جدیدتری از نصب فعلی نیست",
            UpdateVerification.versionVerdict(675, 675, 676).problem)
        assertEquals("APK دریافت‌شده از نسخهٔ اعلام‌شده قدیمی‌تر است",
            UpdateVerification.versionVerdict(675, 677, 676).problem)
        assertNotNull("an unknown version cannot be offered",
            UpdateVerification.versionVerdict(null, null, 676).problem)
    }

    @Test fun `an unreadable archive still verifies with the declared code`() {
        val verdict = UpdateVerification.versionVerdict(null, 677, 676)
        assertNull(verdict.problem)
        assertEquals(677L, verdict.code)
    }

    @Test fun `digest normalisation accepts the release formats and rejects noise`() {
        assertEquals(pin, UpdateVerification.sha256OrNull(pin))
        assertEquals(pin, UpdateVerification.sha256OrNull("sha256:$pin"))
        assertEquals(pin, UpdateVerification.sha256OrNull("  ${pin.uppercase()}  "))
        assertNull(UpdateVerification.sha256OrNull(null))
        assertNull(UpdateVerification.sha256OrNull(pin.dropLast(1)))
    }
}
