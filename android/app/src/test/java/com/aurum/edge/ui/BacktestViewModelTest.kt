package com.aurum.edge.ui

import com.aurum.edge.app.AppContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * رگرسیون «باز کردن تب بک‌تست اپ را می‌بست».
 *
 * ریشه: `_jobs` پایین‌ترِ کلاس اعلان شده بود ولی `init { refresh() }` → `refreshJobs()`
 * در همان مسیر اجرا می‌شد؛ در آن لحظه مقدار ویژگی `null` بود و `_jobs.value` استثنای
 * NullPointerException می‌داد. این تست دقیقاً همان مسیر را می‌سازد: ساخت ViewModel
 * (که `init` را اجرا می‌کند) و خواندن `jobs`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BacktestViewModelTest {

    @Test
    fun `constructing the backtest view model initialises background job state`() {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)

        // اگر ترتیب اعلان ویژگی‌ها به هم بریزد، همین خط با NullPointerException می‌شکند.
        val vm = BacktestViewModel(app, container)

        assertNotNull("فهرست کارهای پس‌زمینه باید از همان ابتدا مقدار داشته باشد", vm.jobs.value)
        assertEquals(0, vm.jobs.value.size)

        // فراخوانی دوباره هم نباید استثنا بدهد (خواندن فایل روی حافظه، در فضای تست خالی است).
        vm.refreshJobs()
        assertNotNull(vm.jobs.value)
    }
}
