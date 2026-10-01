package io.github.warleysr.dechainer.urge

/**
 * The one person the crisis card calls or texts (D4). Stored on the phone only and never sent to
 * the AI: nothing in the request builders takes one.
 */
data class CrisisContact(val name: String, val number: String) {
    val usable: Boolean get() = number.isNotEmpty()

    companion object {
        const val MAX_NAME = 60

        /** Keeps the digits and a leading plus; drops spaces, dashes and brackets, which a dialler does not need. */
        fun cleanNumber(raw: String): String {
            val t = raw.trim()
            val digits = t.filter { it.isDigit() }
            return if (t.startsWith("+")) "+$digits" else digits
        }

        fun of(name: String, number: String) = CrisisContact(name.trim().take(MAX_NAME), cleanNumber(number))
    }
}
