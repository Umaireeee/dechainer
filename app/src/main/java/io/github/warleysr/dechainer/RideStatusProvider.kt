package io.github.warleysr.dechainer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.lock.LockEngine
import io.github.warleysr.dechainer.lock.LockStateStore

/**
 * Lets the urge journal ask what is really in force, instead of trusting that its request worked.
 * Read-only, and guarded by the same signature permission as the door (see the manifest), so only
 * an app signed with the same key can read it. It changes nothing.
 */
class RideStatusProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val now = TrustedClock.now(ctx)
        val owner = try { DeviceOwnerRepository.isDeviceOwner() } catch (_: Exception) { false }
        val urgeEndsAt = if (owner) LockStateStore.urge(ctx).endsAt else 0L
        val brickEndsAt = LockEngine.refreshStatus(ctx)?.endsAt ?: 0L
        val cursor = MatrixCursor(COLUMNS)
        cursor.addRow(statusRow(owner, urgeEndsAt, brickEndsAt, now))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")

    companion object {
        /** `ride_lock_until` is the journal's old name for the urge lock's end, kept so it keeps working. */
        val COLUMNS = arrayOf("device_owner", "urge_lock_until", "brick_until", "ride_lock_until")

        /**
         * One row: 1 or 0 for Device Owner, when the urge lock ends, when the phone unlocks (any
         * brick: urge lock, focus block, punishment day), and the urge lock's end again under its old
         * name. A lock that is not running is 0.
         */
        internal fun statusRow(deviceOwner: Boolean, urgeEndsAt: Long, brickEndsAt: Long, now: Long): Array<Any> {
            val urge = if (urgeEndsAt > now) urgeEndsAt else 0L
            val brick = if (brickEndsAt > now) brickEndsAt else 0L
            return arrayOf(if (deviceOwner) 1 else 0, urge, brick, urge)
        }
    }
}
