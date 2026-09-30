# Urge actions (for the companion urge journal)

Déchaîner exposes one broadcast receiver so a companion app, signed with the **same key**, can turn a decision made in the moment into real blocking.

- **Receiver:** `io.github.warleysr.dechainer/.UrgeActionReceiver` (explicit component)
- **Action:** `io.github.warleysr.dechainer.URGE_ACTION`
- **Permission:** the sender must declare `<uses-permission android:name="io.github.warleysr.dechainer.permission.URGE_ACTION" />`. It is a signature permission, so Android grants it only if both apps are signed with the same key. **Install Déchaîner first**, then the journal; a journal installed earlier is not granted the permission until it is reinstalled.

| Extra `kind` | What it does | `minutes` (capped) |
|---|---|---|
| `IMPULSE_BLOCK` | The panic button, remotely: locks Déchaîner and suspends the apps the panic button is set to. Never shortens a block already running. | 15 to 360 |
| `FOCUS_BLOCK` | A committed focus block. Leaving early takes the recovery code. | 25 to 120 |

Rules that will not change:
- Commands only **add** blocking. Nothing can end a block, edit a schedule or touch the recovery code.
- Ignored unless Déchaîner is Device Owner.
- Unknown kinds are ignored, and missing or out-of-range durations are pulled into range.

For `IMPULSE_BLOCK` to suspend apps, set **Settings → Impulse lock → panic button** to "timer and suspend" and pick the apps.

Example (from the journal):

```kotlin
sendBroadcast(Intent("io.github.warleysr.dechainer.URGE_ACTION").apply {
    component = ComponentName("io.github.warleysr.dechainer", "io.github.warleysr.dechainer.UrgeActionReceiver")
    putExtra("kind", "IMPULSE_BLOCK")
    putExtra("minutes", 60)
})
```
