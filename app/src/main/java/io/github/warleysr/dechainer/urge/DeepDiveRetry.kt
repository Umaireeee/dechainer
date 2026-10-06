package io.github.warleysr.dechainer.urge

import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.ai.AiResult
import io.github.warleysr.dechainer.ai.MarkdownReply
import io.github.warleysr.dechainer.ai.needsOwner
import java.util.concurrent.ConcurrentHashMap

/** The entries a deep dive is being made for right now, so the screen and the retry job never both call the AI for one. */
class InFlight {
    private val ids = ConcurrentHashMap.newKeySet<Long>()

    /** True if [id] was free and is now taken. */
    fun tryBegin(id: Long): Boolean = ids.add(id)

    fun end(id: Long) {
        ids.remove(id)
    }
}

/**
 * The retry job's decisions (blueprint 6.2): every entry that is still PENDING_DEEPDIVE gets
 * another try, until it succeeds or the owner deletes it. Pure: the caller hands in how to call
 * the AI and how to save, so this is tested without a network or a database.
 */
object DeepDiveRetry {
    enum class Outcome {
        /** Nothing is waiting. */
        NOTHING,

        /** Everything that was waiting now has its deep dive. */
        DONE,

        /** Something failed in a way that time can fix (offline, busy): try again with a back-off. */
        RETRY,

        /** No key, no consent, or an error only the owner can fix. Waiting changes nothing; the job ends and is queued again when settings change or the app opens. */
        NEEDS_OWNER
    }

    fun run(
        pending: List<UrgeEntry>,
        gate: AiGateResult,
        inFlight: InFlight,
        generate: (UrgeEntry) -> AiResult,
        save: (id: Long, markdown: String) -> Boolean
    ): Outcome {
        if (pending.isEmpty()) return Outcome.NOTHING
        when (gate) {
            AiGateResult.NO_KEY, AiGateResult.NO_CONSENT -> return Outcome.NEEDS_OWNER
            AiGateResult.OFFLINE -> return Outcome.RETRY
            AiGateResult.OPEN -> Unit
        }
        var retry = false
        var needsOwner = false
        for (entry in pending) {
            if (!inFlight.tryBegin(entry.id)) {
                // The screen is making this one right now; look again later in case it fails.
                retry = true
                continue
            }
            try {
                when (val r = generate(entry)) {
                    is AiResult.Ok -> {
                        val md = MarkdownReply.clean(r.text)
                        // A reply that is not Markdown is a failed call: the note stays.
                        if (md == null) retry = true else if (!save(entry.id, md)) retry = true
                    }
                    // A key/credit/model/address error is the same for every entry: stop calling and
                    // wait for the owner, instead of re-sending each pending entry on every back-off.
                    is AiResult.Failed -> if (r.error.needsOwner) { needsOwner = true; break } else retry = true
                }
            } finally {
                inFlight.end(entry.id)
            }
        }
        return when {
            needsOwner -> Outcome.NEEDS_OWNER
            retry -> Outcome.RETRY
            else -> Outcome.DONE
        }
    }
}
