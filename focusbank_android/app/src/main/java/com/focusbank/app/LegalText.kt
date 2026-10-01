package com.focusbank.app

/**
 * Terms & conditions shown from the top-right menu. Plain data so the wording can be edited
 * without touching UI code. DRAFT: fill in the bracketed placeholders and have the text reviewed
 * before publishing.
 */
object LegalText {
    const val TERMS_EFFECTIVE_DATE = "1 October 2026"
    const val CONTACT = "[developer contact email]"
    const val GOVERNING_LAW = "[country / state]"

    data class Section(val title: String, val body: String)

    val terms = listOf(
        Section(
            "1. Agreeing to these terms",
            "By installing or using FocusBank you agree to these terms. If you do not agree, please stop using " +
                "the app and uninstall it."
        ),
        Section(
            "2. What FocusBank is",
            "FocusBank is a personal self-control tool. You choose which apps to protect, set your own tasks, " +
                "rewards, penalties and goals, and the app limits your own use of the apps you selected. It works on " +
                "an honour system: the rules are ones you set for yourself, and you can change them or turn the app off."
        ),
        Section(
            "3. Using it responsibly",
            "Use FocusBank only on your own device and for yourself. Do not protect apps you may need in an " +
                "emergency, such as the phone dialer, messaging you rely on for safety, maps or health apps. You can " +
                "always stop blocking by turning FocusBank off in Settings › Accessibility, or by uninstalling it. " +
                "FocusBank is not a parental-control or monitoring tool and must not be used to watch or restrict " +
                "another person."
        ),
        Section(
            "4. Accessibility service and permissions",
            "Blocking only works if you turn on FocusBank's Accessibility service. While it is on, FocusBank sees " +
                "the package name of the app on screen and whether the screen is on or off. It uses this only to " +
                "count your allowance, apply penalties, track screen-free time and show the block screen. It does " +
                "not read what you type or the content of other apps. Notification permission is used for reminders, " +
                "penalties and the ongoing protection notice."
        ),
        Section(
            "5. Your data",
            "FocusBank has no account and no server. Your tasks, balances, usage records, penalties and settings " +
                "are stored only on this device. Nothing is uploaded, sold or shared. Backups are files you choose to " +
                "save and are under your control. Uninstalling FocusBank or using \"Reset all data\" deletes the data " +
                "stored by the app."
        ),
        Section(
            "6. No guarantee that blocking always works",
            "FocusBank is provided \"as is\". Android, battery savers or your phone's manufacturer may stop or " +
                "delay the service, reminders and deadline alarms, and blocking may not work on every device or " +
                "Android version. Time counts, penalties and rewards are best-effort and may be inaccurate."
        ),
        Section(
            "7. Not medical advice",
            "FocusBank is a productivity aid. It is not a treatment for addiction or any health condition. If you " +
                "are worried about your phone use or wellbeing, please talk to a qualified professional."
        ),
        Section(
            "8. Limitation of liability",
            "To the extent permitted by law, the developer is not liable for any loss arising from using or being " +
                "unable to use FocusBank. This includes missed deadlines or tasks, lost data, being blocked from an " +
                "app, or the app failing to block one. Nothing in these terms limits rights you have under laws that " +
                "cannot be excluded."
        ),
        Section(
            "9. Changes to these terms",
            "These terms may be updated with new versions of the app. The date below shows when they last changed. " +
                "Continuing to use FocusBank after an update means you accept the new terms."
        ),
        Section(
            "10. Governing law and contact",
            "These terms are governed by the laws of $GOVERNING_LAW. Questions about FocusBank or these terms: $CONTACT."
        )
    )
}
