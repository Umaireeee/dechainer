package io.github.warleysr.dechainer.report

import io.github.warleysr.dechainer.ai.AiError
import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.ai.needsOwner

/** What one attempt to build a period's report came to. */
enum class ReportOutcome {
    /** The report is saved (or was already). */
    DONE,

    /** The period has not ended yet. Try again later. */
    NOT_DUE,

    /** Nothing was recorded in the period, so there is no report to make. */
    NO_DATA,

    /** Time can fix it: offline, a busy service, a reply that was not Markdown. Try again with a back-off. */
    RETRY,

    /** No key or consent, or an error only the owner can fix. Waiting changes nothing; the job ends and is queued again when the app opens. */
    NEEDS_OWNER
}

/** The gate and failure decisions of a report run, pure so they are tested on the JVM. */
object ReportRun {
    fun forGate(gate: AiGateResult): ReportOutcome? = when (gate) {
        AiGateResult.OPEN -> null
        AiGateResult.OFFLINE -> ReportOutcome.RETRY
        AiGateResult.NO_KEY, AiGateResult.NO_CONSENT -> ReportOutcome.NEEDS_OWNER
    }

    fun forFailure(error: AiError): ReportOutcome = if (error.needsOwner) ReportOutcome.NEEDS_OWNER else ReportOutcome.RETRY
}
