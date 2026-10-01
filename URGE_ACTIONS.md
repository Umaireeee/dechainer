# Urge actions (for the companion urge journal)

Déchaîner exposes one broadcast receiver so a companion app, signed with the **same key**, can turn a decision made in the moment into real blocking.

- **Receiver:** `io.github.warleysr.dechainer/.UrgeActionReceiver` (explicit component)
- **Action:** `io.github.warleysr.dechainer.URGE_ACTION`
- **Permission:** the sender must declare `<uses-permission android:name="io.github.warleysr.dechainer.permission.URGE_ACTION" />`. It is a signature permission, so Android grants it only if both apps are signed with the same key. **Install Déchaîner first**, then the journal; a journal installed earlier is not granted the permission until it is reinstalled.

| Extra `kind` | What it does | `minutes` |
|---|---|---|
| `IMPULSE_BLOCK` | The **urge lock** (blueprint 5.2): every app with an icon is suspended except calls, the alarm clock, Déchaîner and the journal, for a fixed ten minutes. SMS and Emergency Info are blocked too (D3). A second request while it runs changes nothing. Nothing ends it early. | ignored (always 10) |
| `RIDE_LOCK` | The same urge lock as `IMPULSE_BLOCK`. Both names are kept so the journal keeps working unchanged. | ignored (always 10) |
| `FOCUS_BLOCK` | A committed focus block: the phone is bricked until it ends. Nothing ends it early, not even the recovery code. | 25 to 120 (capped) |

Optional extra `intention` (text, `FOCUS_BLOCK` only): what the first session is for, for example the journal's "first move" for the day. It is cleaned like any intention (one line, at most 80 characters) and used only if this request really started the block; if the timer was busy, nothing changes. The end-of-session question then asks about exactly that.

Rules that will not change:
- Commands only **add** blocking. Nothing can end a block, edit a schedule or touch the recovery code.
- Ignored unless Déchaîner is Device Owner.
- Unknown kinds are ignored, and a missing or out-of-range focus duration is pulled into range.
- An urge lock is not started inside a focus block or a punishment day (those already hold the phone for longer), and a focus block is refused on a punishment day.

The old setting "panic button: timer and suspend chosen apps" is gone: the urge lock takes every app, so there is nothing to choose.

Example (from the journal):

```kotlin
sendBroadcast(Intent("io.github.warleysr.dechainer.URGE_ACTION").apply {
    component = ComponentName("io.github.warleysr.dechainer", "io.github.warleysr.dechainer.UrgeActionReceiver")
    putExtra("kind", "IMPULSE_BLOCK")
})
```

Going the other way, Déchaîner's focus screen starts a ride in the journal by launching it with the extra `action` = `ride` (the same extra the journal's tile and icon shortcut use). The journal counts down five seconds, which can be cancelled, before it starts.

## Status provider

`content://io.github.warleysr.dechainer.status` (same signature permission, read only) returns one row: `device_owner` (1 or 0), `urge_lock_until` (epoch millis, 0 if none), `brick_until` (when the phone unlocks: urge lock, focus block or punishment day, 0 if none) and `ride_lock_until` (the old name for `urge_lock_until`, kept for the journal). The old `impulse_until` column is gone.
