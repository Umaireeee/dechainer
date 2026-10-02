package io.github.warleysr.dechainer.lock

/**
 * What a brick mode leaves open besides what no mode ever takes away (this app, the launcher, the
 * dialer so incoming calls work, keyboards and system UI: [PhoneFacts.protectedApps]).
 */
data class AllowSet(
    /** Alarm-clock apps, so a morning alarm still rings. */
    val alarm: Boolean,
    /** Emergency Info and Safety apps. */
    val emergency: Boolean,
    /** The default SMS app. */
    val sms: Boolean,
    /** The owner's own allow list for this mode applies on top (the focus list). */
    val ownerList: Boolean
)

/**
 * The allow sets of the two brick modes (blueprint 5.2). Changing any of them loosens a lock, so
 * it needs the recovery code and the unlock delay (9.1): a veto of a default is a one-line change
 * here, made by the owner, never by a build on its own.
 */
object LockAllow {
    /** D3: calls and the dialer, the alarm clock. Everything else is blocked, SMS and Emergency Info included. */
    val URGE = AllowSet(alarm = true, emergency = false, sms = false, ownerList = false)

    /** As it has always been: calls, the alarm clock, the emergency apps, and the apps the owner allowed. SMS stays off. */
    val FOCUS = AllowSet(alarm = true, emergency = true, sms = false, ownerList = true)

    fun of(mode: LockMode): AllowSet? = when (mode) {
        LockMode.URGE_LOCK -> URGE
        LockMode.FOCUS_BLOCK -> FOCUS
        else -> null
    }

    /**
     * The apps a brick mode suspends: every app with an icon except the protected ones and what
     * [allow] opens. [ownerApps] is the owner's list for that mode and counts only if the set lets it.
     */
    fun blocked(phone: PhoneFacts, allow: AllowSet, ownerApps: Set<String>): Set<String> {
        var open = phone.protectedApps
        if (allow.alarm) open = open + phone.alarmApps
        if (allow.emergency) open = open + phone.emergencyApps
        if (allow.sms) open = open + phone.smsApps
        if (allow.ownerList) open = open + ownerApps
        return phone.launcherApps - open
    }
}
