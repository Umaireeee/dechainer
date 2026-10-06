package io.github.warleysr.dechainer.ai

import android.content.Context
import androidx.core.content.edit

/**
 * The owner's AI choices: provider, model, the key (sealed with the Keystore) and the consent to
 * send the note to that provider.
 */
class AiSettings(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    // Decrypting takes a round trip to the Keystore, and the key is read on every redraw.
    @Volatile private var cachedKey: String? = null

    /** The API key, or "" when none is saved or the Keystore can no longer open it. */
    val key: String
        get() {
            cachedKey?.let { return it }
            val value = prefs.getString(KEY_SEALED, null)?.let { SecretBox.open(it) } ?: ""
            cachedKey = value
            return value
        }

    val provider: Provider
        get() = Provider.entries.firstOrNull { it.name == prefs.getString(KEY_PROVIDER, null) }
            ?: Provider.detect(key) ?: Provider.OPENROUTER

    val customBase: String get() = prefs.getString(KEY_CUSTOM_BASE, "") ?: ""

    val model: String get() = prefs.getString(KEY_MODEL, "")?.ifBlank { null } ?: provider.defaultModel

    val baseUrl: String get() = if (provider == Provider.CUSTOM) customBase else provider.baseUrl

    /** Has the owner agreed to send the note to this provider? Changing provider (or a custom address) asks again. */
    val consent: Boolean get() = prefs.getString(KEY_CONSENT_FOR, null) == consentKey

    /**
     * Whether the destination is known: the owner picked a provider, or the key's look identifies it.
     * A plain `sk-` key is ambiguous (DeepSeek, OpenAI and others all use it), so it must not be sent
     * to the fallback provider by default; until the owner picks one, nothing is configured.
     */
    val providerChosen: Boolean get() = prefs.contains(KEY_PROVIDER) || Provider.detect(key) != null

    /**
     * Ready to call: a known destination, a key, a model, and an address that is safe to send a key
     * to. A plain `http://` address is refused up front here, not only when a call is attempted
     * (blueprint 8), so the gate reports NO_KEY and the owner sees the problem in Settings.
     */
    val configured: Boolean get() = providerChosen && toConfig().let { c -> c.configured && AiClient.addressProblem(c.baseUrl) == null }

    fun toConfig(): AiConfig = AiConfig(provider, baseUrl, key, model)

    /** What happened when a key was saved. */
    enum class KeySave { SAVED, CANNOT_ENCRYPT, PROVIDER_NEEDED }

    /**
     * Saves the key, sealed. If this phone cannot encrypt it nothing is saved: the key is never
     * kept as plain text. An empty key removes the saved one.
     */
    fun saveKey(value: String): KeySave {
        val clean = value.trim()
        if (clean.isEmpty()) {
            prefs.edit { remove(KEY_SEALED) }
            cachedKey = ""
            return KeySave.SAVED
        }
        val sealed = SecretBox.seal(clean) ?: return KeySave.CANNOT_ENCRYPT
        prefs.edit { putString(KEY_SEALED, sealed) }
        cachedKey = clean
        // A key whose look does not identify a provider needs the owner to choose one first.
        return if (providerChosen) KeySave.SAVED else KeySave.PROVIDER_NEEDED
    }

    /** The model the owner typed, or "" to use the provider's default. */
    val modelOverride: String get() = prefs.getString(KEY_MODEL, "") ?: ""

    /** Switching provider drops a typed model name: it belonged to the other provider. */
    fun setProvider(p: Provider): Boolean {
        val changed = p != provider
        return write {
            putString(KEY_PROVIDER, p.name)
            if (changed) remove(KEY_MODEL)
        }
    }

    fun setModel(value: String): Boolean = write { putString(KEY_MODEL, value.trim()) }

    fun setCustomBase(value: String): Boolean = write { putString(KEY_CUSTOM_BASE, value.trim()) }

    fun setConsent(agreed: Boolean): Boolean = write {
        if (agreed) putString(KEY_CONSENT_FOR, consentKey) else remove(KEY_CONSENT_FOR)
    }

    private fun write(change: android.content.SharedPreferences.Editor.() -> Unit): Boolean {
        prefs.edit { change() }
        return true
    }

    /** Consent is for one destination: for the owner's own service address, a different address asks again. */
    private val consentKey: String
        get() = if (provider == Provider.CUSTOM) provider.name + "|" + customBase.trim().lowercase() else provider.name

    private companion object {
        const val KEY_SEALED = "key_enc"
        const val KEY_PROVIDER = "provider"
        const val KEY_CUSTOM_BASE = "custom_base"
        const val KEY_MODEL = "model"
        const val KEY_CONSENT_FOR = "consent_for"
    }
}
