package com.managetime.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {
    @Test fun `selected entertainment apps remain eligible`() {
        assertTrue(CapturePolicy.canCapture("tv.danmaku.bili"))
        assertTrue(CapturePolicy.canCapture("com.google.android.youtube"))
    }

    @Test fun `messaging keyboards financial and password apps are always excluded`() {
        listOf("com.tencent.mm", "com.tencent.mobileqq", "org.telegram.messenger", "com.whatsapp", "com.Slack", "com.eg.android.AlipayGphone", "com.google.android.inputmethod.latin", "com.example.bank", "com.x8bit.bitwarden", "com.android.systemui", "com.managetime.app", "").forEach {
            assertFalse("Unexpectedly allowed $it", CapturePolicy.canCapture(it))
        }
    }

    @Test fun `candidate filter rejects obvious secrets contact info and counters`() {
        listOf("你的验证码为 123456", "密码：不应保存", "Password: should-not-save", "one-time password: 345678", "联系我 person@example.com", "联系电话 13800138000", "12345678", "02:45 / 09:22", "Hi", "很长的标题".repeat(60)).forEach {
            assertFalse("Unexpectedly allowed $it", CapturePolicy.safeCandidate(it))
        }
        assertTrue(CapturePolicy.safeCandidate("用十分钟了解 Android 应用的生命周期"))
    }

    @Test fun `editable message comment and account subtrees are excluded`() {
        listOf("app:id/comment_panel", "app:id/message_body", "app:id/search_input", "app:id/password", "app:id/account_number").forEach {
            assertTrue(CapturePolicy.sensitiveViewId(it))
        }
        assertFalse(CapturePolicy.sensitiveViewId("tv.danmaku.bili:id/video_title"))
    }
}
