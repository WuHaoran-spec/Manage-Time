package com.managetime.app.capture

/** Conservative hard exclusions always win over the user's selected-app list. */
object CapturePolicy {
    private val excludedPackages = setOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "org.telegram.messenger",
        "org.thoughtcrime.securesms", "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca",
        "com.discord", "com.Slack", "com.alibaba.android.rimet", "com.tencent.wework",
        "com.google.android.gm", "com.microsoft.office.outlook", "com.netease.mail",
        "com.android.mms", "com.google.android.apps.messaging", "com.android.contacts",
        "com.android.dialer", "com.google.android.dialer", "com.android.settings",
        "com.eg.android.AlipayGphone", "com.android.vending", "com.managetime.app"
    ).map { it.lowercase() }.toSet()
    private val excludedFragments = listOf(
        "inputmethod", "keyboard", "keyguard", "systemui", "permissioncontroller", "launcher",
        "password", "authenticator", "keychain", "keepass", "bitwarden", "1password",
        "bank", "wallet", "payment", "security", "messenger", "sms", "email"
    )

    fun canCapture(packageName: String): Boolean {
        val name = packageName.lowercase()
        return name.isNotBlank() && name !in excludedPackages && name != "android" &&
            excludedFragments.none(name::contains)
    }

    fun safeCandidate(text: String): Boolean {
        if (text.length !in 6..240) return false
        if (listOf("验证码", "密码", "身份证", "银行卡", "verification code", "password", "passcode", "security code").any { text.contains(it, ignoreCase = true) }) return false
        if (Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").containsMatchIn(text)) return false
        if (Regex("(?<!\\d)\\d[\\d -]{9,18}\\d(?!\\d)").containsMatchIn(text)) return false
        if (text.matches(Regex("[\\d\\s:./%+-]+"))) return false
        return true
    }

    fun sensitiveViewId(viewId: String): Boolean = listOf(
        "edit", "input", "password", "message", "chat", "comment", "reply", "search", "account", "phone", "email", "payment"
    ).any { viewId.contains(it, ignoreCase = true) }
}
