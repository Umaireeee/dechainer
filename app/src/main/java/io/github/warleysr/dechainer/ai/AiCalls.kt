package io.github.warleysr.dechainer.ai

import io.github.warleysr.dechainer.urge.UrgeKind

/** What the questions call came back with. */
sealed interface QuestionsOutcome {
    data class Reply(val reply: QuestionsReply) : QuestionsOutcome
    data class Failed(val error: AiError) : QuestionsOutcome
}

/** What a Markdown call (the deep dive, the weekly report) came back with. */
sealed interface MarkdownOutcome {
    data class Ok(val markdown: String) : MarkdownOutcome
    data class Failed(val error: AiError) : MarkdownOutcome
}

typealias ChatCall = (AiConfig, String, String, AiLimits) -> AiResult
typealias StreamCall = (AiConfig, String, String, AiLimits, (String) -> Unit) -> AiResult

/**
 * The three calls of blueprint section 8. Each is a pure request builder ([AiPrompts]), one client
 * call, and a pure parser for the reply ([QuestionsParser], [MarkdownReply]). Blocking: call them
 * off the main thread. The client is injectable so the glue is tested without a network.
 */
class AiCalls(
    private val chat: ChatCall = AiClient::chat,
    private val stream: StreamCall = AiClient::chatStream
) {
    /** 3 to 5 questions about the note, the support reply, or a failure. */
    fun generateQuestions(config: AiConfig, kind: UrgeKind, note: String): QuestionsOutcome {
        val req = AiPrompts.questions(kind, note)
        return when (val r = chat(config, req.system, req.user, AiLimits.QUESTIONS)) {
            is AiResult.Ok -> QuestionsOutcome.Reply(QuestionsParser.parse(r.text))
            is AiResult.Failed -> QuestionsOutcome.Failed(r.error)
        }
    }

    /** The deep dive as Markdown, capped at [Rules.MAX_DEEP_DIVE_WORDS] (blueprint 8). [onText] is told everything written so far while it arrives. */
    fun deepDive(config: AiConfig, input: DeepDiveInput, onText: (String) -> Unit = {}): MarkdownOutcome {
        val req = AiPrompts.deepDive(input)
        return when (val out = markdown(stream(config, req.system, req.user, AiLimits.DEEP_DIVE, onText))) {
            is MarkdownOutcome.Ok -> MarkdownOutcome.Ok(MarkdownReply.limitWords(out.markdown, io.github.warleysr.dechainer.Rules.MAX_DEEP_DIVE_WORDS))
            is MarkdownOutcome.Failed -> out
        }
    }

    /** The weekly report as Markdown, from derived data only. */
    fun weeklyReport(config: AiConfig, derivedInputs: String): MarkdownOutcome {
        val req = AiPrompts.weeklyReport(derivedInputs)
        return markdown(chat(config, req.system, req.user, AiLimits.WEEKLY))
    }

    /** The monthly report as Markdown, from derived data only. */
    fun monthlyReport(config: AiConfig, derivedInputs: String): MarkdownOutcome {
        val req = AiPrompts.monthlyReport(derivedInputs)
        return markdown(chat(config, req.system, req.user, AiLimits.LONG_REPORT))
    }

    /** The yearly report as Markdown, from derived data only. */
    fun yearlyReport(config: AiConfig, derivedInputs: String): MarkdownOutcome {
        val req = AiPrompts.yearlyReport(derivedInputs)
        return markdown(chat(config, req.system, req.user, AiLimits.LONG_REPORT))
    }

    private fun markdown(r: AiResult): MarkdownOutcome = when (r) {
        // A reply that is not Markdown is a failed call, so the note is kept and tried again.
        is AiResult.Ok -> MarkdownReply.clean(r.text)?.let { MarkdownOutcome.Ok(it) } ?: MarkdownOutcome.Failed(AiError.EMPTY)
        is AiResult.Failed -> MarkdownOutcome.Failed(r.error)
    }
}
