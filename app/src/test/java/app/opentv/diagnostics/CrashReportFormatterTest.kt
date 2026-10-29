/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrashReportFormatterTest {
    @Test fun excludesMessagesAndUntrustedFrameData() {
        val inner = IllegalArgumentException("https://private.example/live?username=me&token=secret")
        inner.stackTrace = arrayOf(
            StackTraceElement("app.opentv.player.PlayerController", "prepare", "PlayerController.kt", 42),
            StackTraceElement("https://private.example", "token=secret", "secret.kt", 10),
        )
        val outer = IllegalStateException("password=extremely-private", inner)
        outer.stackTrace = arrayOf(
            StackTraceElement("app.opentv.MainActivity", "onStart", "MainActivity.kt", 120),
        )

        val report = CrashReportFormatter.format(outer, 123456L, "1.0.0", 24)

        assertThat(report).contains("java.lang.IllegalStateException")
        assertThat(report).contains("app.opentv.player.PlayerController.prepare:42")
        assertThat(report).contains("app.opentv.MainActivity.onStart:120")
        assertThat(report).doesNotContain("password")
        assertThat(report).doesNotContain("private.example")
        assertThat(report).doesNotContain("token=secret")
        assertThat(report).doesNotContain("username=")
        assertThat(report).doesNotContain("secret.kt")
    }

    @Test fun boundsDeepCauseChainsAndLargeStacks() {
        var cause: Throwable = IllegalStateException("private")
        repeat(8) { cause = IllegalArgumentException("private", cause) }
        cause.stackTrace = Array(200) { index ->
            StackTraceElement("app.opentv.Test", "frame", "Test.kt", index + 1)
        }
        val report = CrashReportFormatter.format(cause, -1L, "1.0.0", 24)
        assertThat(report.length).isAtMost(3500)
        assertThat(report).contains("Time (Unix ms): 0")
        assertThat(report).doesNotContain("private")
        assertThat(report.split("Exception ").size).isAtMost(4)
    }

    @Test fun ignoresMalformedSymbolsAndExceptionMessages() {
        val failure = IllegalArgumentException("stream id: abc / https://host")
        failure.stackTrace = arrayOf(
            StackTraceElement("app.opentv.Player", "play", "full/secret/path", 50),
            StackTraceElement("host:123/private", "bad", "file.kt", 3),
        )
        val report = CrashReportFormatter.format(failure, 30L, "1.0.0", 24)
        assertThat(report).contains("app.opentv.Player.play:50")
        assertThat(report).doesNotContain("full/secret/path")
        assertThat(report).doesNotContain("host:123/private")
        assertThat(report).doesNotContain("stream id")
    }
}
