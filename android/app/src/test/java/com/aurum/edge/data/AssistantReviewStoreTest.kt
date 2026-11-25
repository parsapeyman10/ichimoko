package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.AssistantEvidenceReview
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AssistantReviewStoreTest {
    @Test fun selectionIsAuditedAndManualRollbackPersists() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("assistant_review", Context.MODE_PRIVATE).edit().clear().commit()
        val fold = AssistantEvidenceReview.Fold(30, 30, 0.5, 0.1, 0.25)
        val review = AssistantEvidenceReview.Report(200, List(3) { fold }, AssistantEvidenceReview.Source.NEURAL, 0.5, "evidence")
        assertEquals("NEURAL", AssistantReviewStore.record(ctx, review, 100))
        val first = JSONArray(AssistantReviewStore.history(ctx))
        assertEquals(3, first.getJSONObject(0).getJSONArray("folds").length())
        AssistantReviewStore.setEnabled(ctx, false)
        assertEquals("BASELINE", AssistantReviewStore.record(ctx, review, 200))
        assertEquals("BASELINE", AssistantReviewStore.record(ctx, review, 300))
        assertEquals(2, JSONArray(AssistantReviewStore.history(ctx)).length())
        assertFalse(AssistantReviewStore.enabled(ctx))
    }
}
