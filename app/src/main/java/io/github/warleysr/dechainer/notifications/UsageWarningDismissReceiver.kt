package io.github.warleysr.dechainer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UsageWarningDismissReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_STAGE_ID = "stage_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val stageId = intent.getStringExtra(EXTRA_STAGE_ID) ?: return
        UsageWarningNotifier.markDismissed(context, key, stageId)
    }
}
