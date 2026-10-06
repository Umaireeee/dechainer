package io.github.warleysr.dechainer

import io.github.warleysr.dechainer.ai.AiError
import io.github.warleysr.dechainer.ai.AiGateResult
import io.github.warleysr.dechainer.ai.AiResult
import io.github.warleysr.dechainer.urge.DeepDiveRetry
import io.github.warleysr.dechainer.urge.InFlight
import io.github.warleysr.dechainer.urge.UrgeEntry
import io.github.warleysr.dechainer.urge.UrgeKind
import io.github.warleysr.dechainer.urge.UrgeSource
import io.github.warleysr.dechainer.urge.UrgeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepDiveRetryTest {
    private fun pending(id: Long) = UrgeEntry(id, 0, UrgeKind.URGE, UrgeSource.HOME, 0, 0, UrgeStatus.PENDING_DEEPDIVE, "note $id", null, null, null)

    private class Run(val saved: MutableMap<Long, String> = mutableMapOf(), val called: MutableList<Long> = mutableListOf())

    private fun run(
        entries: List<UrgeEntry>, gate: AiGateResult = AiGateResult.OPEN, inFlight: InFlight = InFlight(),
        saveOk: Boolean = true, generate: (UrgeEntry) -> AiResult
    ): Pair<DeepDiveRetry.Outcome, Run> {
        val r = Run()
        val out = DeepDiveRetry.run(entries, gate, inFlight, { r.called += it.id; generate(it) }) { id, md ->
            if (saveOk) r.saved[id] = md
            saveOk
        }
        return out to r
    }

    @Test
    fun nothingWaitingMeansNothingToDo() {
        assertEquals(DeepDiveRetry.Outcome.NOTHING, run(emptyList()) { error("not called") }.first)
    }

    @Test
    fun everyPendingEntryGetsItsDeepDiveSaved() {
        val (out, r) = run(listOf(pending(1), pending(2))) { AiResult.Ok("## A\nfor ${it.id}") }
        assertEquals(DeepDiveRetry.Outcome.DONE, out)
        assertEquals(mapOf(1L to "## A\nfor 1", 2L to "## A\nfor 2"), r.saved)
    }

    @Test
    fun noKeyOrNoConsentEndsTheJobWithoutCallingAnything() {
        for (gate in listOf(AiGateResult.NO_KEY, AiGateResult.NO_CONSENT)) {
            val (out, r) = run(listOf(pending(1)), gate) { error("not called") }
            assertEquals(DeepDiveRetry.Outcome.NEEDS_OWNER, out)
            assertTrue(r.called.isEmpty())
        }
    }

    @Test
    fun offlineAsksForALaterRetryWithoutCallingAnything() {
        val (out, r) = run(listOf(pending(1)), AiGateResult.OFFLINE) { error("not called") }
        assertEquals(DeepDiveRetry.Outcome.RETRY, out)
        assertTrue(r.called.isEmpty())
    }

    @Test
    fun aFailureThatTimeCanFixIsRetriedAndSavesNothing() {
        for (e in listOf(AiError.NETWORK, AiError.SERVER, AiError.RATE_LIMIT, AiError.EMPTY)) {
            val (out, r) = run(listOf(pending(1))) { AiResult.Failed(e) }
            assertEquals(e.name, DeepDiveRetry.Outcome.RETRY, out)
            assertTrue(r.saved.isEmpty())
        }
    }

    @Test
    fun aFailureOnlyTheOwnerCanFixDoesNotLoopForever() {
        for (e in listOf(AiError.BAD_KEY, AiError.NO_CREDITS, AiError.BAD_MODEL, AiError.BAD_URL, AiError.INSECURE_URL)) {
            val (out, r) = run(listOf(pending(1))) { AiResult.Failed(e) }
            assertEquals(e.name, DeepDiveRetry.Outcome.NEEDS_OWNER, out)
            assertTrue(r.saved.isEmpty())
        }
    }

    @Test
    fun aReplyThatIsNotMarkdownIsAFailedCallAndTheNoteStays() {
        val (out, r) = run(listOf(pending(1))) { AiResult.Ok("{\"headline\":\"x\"}") }
        assertEquals(DeepDiveRetry.Outcome.RETRY, out)
        assertTrue(r.saved.isEmpty())
    }

    @Test
    fun oneFailureDoesNotStopTheOthersAndAsksForARetry() {
        val (out, r) = run(listOf(pending(1), pending(2), pending(3))) {
            if (it.id == 2L) AiResult.Failed(AiError.NETWORK) else AiResult.Ok("## ok ${it.id}")
        }
        assertEquals(DeepDiveRetry.Outcome.RETRY, out)
        assertEquals(setOf(1L, 3L), r.saved.keys)
    }

    @Test
    fun aFailureOnlyTheOwnerCanFixStopsTheRestInsteadOfReCallingThem() {
        // Entry 1 saves, entry 2 hits a bad key: the job stops there, so entry 3 is never sent again on retry.
        val (out, r) = run(listOf(pending(1), pending(2), pending(3))) {
            if (it.id == 2L) AiResult.Failed(AiError.BAD_KEY) else AiResult.Ok("## ok ${it.id}")
        }
        assertEquals(DeepDiveRetry.Outcome.NEEDS_OWNER, out)
        assertEquals(listOf(1L, 2L), r.called)
        assertEquals(setOf(1L), r.saved.keys)
    }

    @Test
    fun aSaveThatFailsIsRetried() {
        val (out, _) = run(listOf(pending(1)), saveOk = false) { AiResult.Ok("## ok") }
        assertEquals(DeepDiveRetry.Outcome.RETRY, out)
    }

    @Test
    fun anEntryTheScreenIsWorkingOnIsSkippedAndLookedAtAgainLater() {
        val inFlight = InFlight()
        assertTrue(inFlight.tryBegin(1))
        val (out, r) = run(listOf(pending(1), pending(2)), inFlight = inFlight) { AiResult.Ok("## ok") }
        assertEquals(DeepDiveRetry.Outcome.RETRY, out)
        assertEquals(listOf(2L), r.called)
        // The job lets go of what it took.
        assertTrue(inFlight.tryBegin(2))
    }

    @Test
    fun inFlightHandsOutEachEntryOnceUntilItIsReleased() {
        val f = InFlight()
        assertTrue(f.tryBegin(7))
        assertFalse(f.tryBegin(7))
        f.end(7)
        assertTrue(f.tryBegin(7))
    }

    @Test
    fun theCallIsReleasedEvenIfGeneratingThrows() {
        val f = InFlight()
        try {
            DeepDiveRetry.run(listOf(pending(1)), AiGateResult.OPEN, f, { throw IllegalStateException("boom") }) { _, _ -> true }
        } catch (_: IllegalStateException) {
        }
        assertTrue(f.tryBegin(1))
    }
}
