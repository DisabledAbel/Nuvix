/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import android.content.Context
import app.opentv.core.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordingDefaultsTest {
    private lateinit var context: Application
    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvix_private_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `fresh defaults start two minutes early and finish five minutes late`() {
        assertThat(settings().recordPadStartMinutes.value).isEqualTo(2)
        assertThat(settings().recordPadEndMinutes.value).isEqualTo(5)
        assertThat(settings().recordAutoSwitch.value).isFalse()
    }

    @Test fun `existing explicit padding survives store reconstruction`() {
        settings().setRecordPadding(1, 12)
        settings().setRecordAutoSwitch(true)
        assertThat(settings().recordPadStartMinutes.value).isEqualTo(1)
        assertThat(settings().recordPadEndMinutes.value).isEqualTo(12)
        assertThat(settings().recordAutoSwitch.value).isTrue()
    }

    private fun settings() = AppSettings::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(context)
}
