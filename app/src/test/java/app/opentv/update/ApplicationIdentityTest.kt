package app.opentv.update

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.disabledabel.nuvix.BuildConfig
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise merged manifest resolution with the application ID independent of the namespace. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ApplicationIdentityTest {
    @Test fun `installed debug identity comes from applicationId not namespace`() {
        val context = RuntimeEnvironment.getApplication()
        assertThat(BuildConfig.APPLICATION_ID).isEqualTo("com.nuvix.player.debug")
        assertThat(context.packageName).isEqualTo(BuildConfig.APPLICATION_ID)
        assertThat(Class.forName("app.opentv.OpenTvApp").superclass).isEqualTo(Application::class.java)
    }

    @Test fun `launcher intent filters still resolve the existing activity class`() {
        val context = RuntimeEnvironment.getApplication()
        for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(context.packageName)
            val results = context.packageManager.queryIntentActivities(intent, 0)
            assertThat(results.map { it.activityInfo.name }).contains("app.opentv.MainActivity")
        }
        val home = context.packageManager.getActivityInfo(
            ComponentName(context.packageName, "app.opentv.launcher.NuvixHome"), PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertThat(home.targetActivity).isEqualTo("app.opentv.MainActivity")
        assertThat(home.enabled).isFalse()
        // Email-code authentication has no package-bound browser/OAuth redirect filter.
        val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("nuvix://auth/callback"))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        assertThat(context.packageManager.queryIntentActivities(view, 0)).isEmpty()
    }

    @Test fun `update FileProvider uses installed identity and retained private path`() {
        val context = RuntimeEnvironment.getApplication()
        val authority = "${context.packageName}.fileprovider"
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        assertThat(provider?.authority).isEqualTo(authority)
        assertThat(provider?.exported).isFalse()
        val apk = File(context.cacheDir, "updates/nuvix.apk").apply { parentFile!!.mkdirs(); writeText("fixture") }
        assertThat(FileProvider.getUriForFile(context, authority, apk).authority).isEqualTo(authority)
        assertThat(context.packageManager.resolveContentProvider("com.disabledabel.nuvix.fileprovider", 0)).isNull()
    }

    @Test fun `recording playback and installer components retain original class names`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.packageManager
        assertThat(manager.getServiceInfo(ComponentName(context.packageName, "app.opentv.recording.RecordingService"), 0).exported).isFalse()
        assertThat(manager.getServiceInfo(ComponentName(context.packageName, "androidx.media3.session.MediaSessionService"), 0).exported).isFalse()
        for (name in listOf("app.opentv.update.InstallStatusReceiver", "app.opentv.recording.RecordingAlarmReceiver")) {
            assertThat(manager.getReceiverInfo(ComponentName(context.packageName, name), 0).exported).isFalse()
        }
    }
}
