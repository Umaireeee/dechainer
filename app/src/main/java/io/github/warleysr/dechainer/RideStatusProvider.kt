package io.github.warleysr.dechainer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.github.warleysr.dechainer.data.DeviceOwnerRepository
import io.github.warleysr.dechainer.data.RideLock
import io.github.warleysr.dechainer.security.SecurityManager

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
        val now = System.currentTimeMillis()
        val owner = try { DeviceOwnerRepository.isDeviceOwner() } catch (_: Exception) { false }
        val cursor = MatrixCursor(COLUMNS)
        cursor.addRow(
            statusRow(
                deviceOwner = owner,
                rideLockMillis = RideLock.remainingMillis(ctx, now),
                impulseMillis = SecurityManager.getImpulseBlockRemainingTime(ctx),
                now = now
            )
        )
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")

    companion object {
        val COLUMNS = arrayOf("device_owner", "ride_lock_until", "impulse_until")

        /** One row: 1 or 0 for Device Owner, and when each lock ends (0 when it is not running). */
        internal fun statusRow(deviceOwner: Boolean, rideLockMillis: Long, impulseMillis: Long, now: Long): Array<Any> = arrayOf(
            if (deviceOwner) 1 else 0,
            if (rideLockMillis > 0L) now + rideLockMillis else 0L,
            if (impulseMillis > 0L) now + impulseMillis else 0L
        )
    }
}
