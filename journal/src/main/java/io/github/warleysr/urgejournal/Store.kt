package io.github.warleysr.urgejournal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit

/** The journal, kept on this phone only. Nothing here is sent anywhere. */
class JournalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("journal", Context.MODE_PRIVATE)

    fun all(): List<Entry> = Entry.listFromJson(prefs.getString(KEY, null)).sortedBy { it.time }

    fun add(entry: Entry) {
        val next = (all() + entry).takeLast(LIMIT)
        prefs.edit(commit = true) { putString(KEY, Entry.listToJson(next)) }
    }

    fun setOutcome(time: Long, outcome: Outcome) {
        val next = all().map { if (it.time == time) it.copy(outcome = outcome) else it }
        prefs.edit(commit = true) { putString(KEY, Entry.listToJson(next)) }
    }

    private companion object {
        const val KEY = "entries"
        const val LIMIT = 2000
    }
}

/** The connection to Déchaîner: one explicit broadcast, allowed only because both apps share a signing key. */
object Door {
    const val PKG = "io.github.warleysr.dechainer"
    private const val RECEIVER = "io.github.warleysr.dechainer.UrgeActionReceiver"
    private const val ACTION = "io.github.warleysr.dechainer.URGE_ACTION"
    private const val PERMISSION = "io.github.warleysr.dechainer.permission.URGE_ACTION"

    enum class Result { SENT, NOT_INSTALLED, NO_PERMISSION }

    /**
     * Asks Déchaîner to block. SENT means the request left this app; Déchaîner still has to be
     * Device Owner for it to take effect.
     */
    fun send(context: Context, action: DoorAction): Result {
        val installed = runCatching { context.packageManager.getPackageInfo(PKG, 0) }.isSuccess
        if (!installed) return Result.NOT_INSTALLED
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return Result.NO_PERMISSION
        context.sendBroadcast(
            Intent(ACTION).apply {
                component = ComponentName(PKG, RECEIVER)
                putExtra("kind", action.kind)
                putExtra("minutes", action.minutes)
            }
        )
        return Result.SENT
    }
}
