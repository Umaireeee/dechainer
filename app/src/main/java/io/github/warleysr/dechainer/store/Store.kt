package io.github.warleysr.dechainer.store

import android.content.Context

/** The one place the database is opened from. Repositories hang off it as they are added. */
object Store {
    @Volatile private var database: DechainerDatabase? = null
    @Volatile private var appState: AppStateRepository? = null
    @Volatile private var urgeEntries: UrgeEntryRepository? = null

    fun appState(context: Context): AppStateRepository =
        appState ?: synchronized(this) {
            appState ?: AppStateRepository(database(context)).also { appState = it }
        }

    fun urgeEntries(context: Context): UrgeEntryRepository =
        urgeEntries ?: synchronized(this) {
            urgeEntries ?: UrgeEntryRepository(database(context)).also { urgeEntries = it }
        }

    private fun database(context: Context): DechainerDatabase =
        database ?: synchronized(this) {
            database ?: DechainerDatabase(context).also { database = it }
        }

    /**
     * For tests: empties every table through the open database, without closing or deleting the file.
     * The real Application's startup work opens the same file on its own thread, so a test that
     * deletes the file under it races with that thread.
     */
    internal fun clearForTests(context: Context) {
        val db = database(context).writableDatabase
        db.execSQL("DELETE FROM urge_entry")
        db.execSQL("DELETE FROM app_state")
    }

    /** For tests: close and forget the database so the next call opens a fresh one. */
    internal fun resetForTests() = synchronized(this) {
        database?.close()
        database = null
        appState = null
        urgeEntries = null
    }
}
