package io.github.warleysr.dechainer.urge

/**
 * A blunt check on the owner's note (blueprint 6.2), so a person in crisis gets the support card.
 * English keywords only: the prompts also tell the AI to answer in the care shape when a note shows
 * risk in any language. Ported from the journal.
 */
object Safety {
    private val markers = listOf(
        "kill myself", "kill me", "end my life", "end it all", "take my own life", "want to die",
        "wish i was dead", "wish i were dead", "better off dead", "suicide", "suicidal", "hurt myself",
        "harm myself", "self harm", "self-harm", "don't want to live", "dont want to live",
        "don't want to be here", "no reason to live", "can't go on", "cant go on"
    )

    fun needsSupport(note: String): Boolean {
        // Phone keyboards type a curly apostrophe; the phrases above use the plain one.
        val t = note.lowercase().replace('’', '\'').replace('‘', '\'').replace('`', '\'')
        return markers.any { it in t }
    }
}
