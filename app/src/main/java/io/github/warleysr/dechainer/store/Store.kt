package io.github.warleysr.dechainer.store

import android.content.Context

/** The one place the database is opened from. Repositories hang off it as they are added. */
object Store {
    @Volatile private var database: DechainerDatabase? = null
    @Volatile private var appState: AppStateRepository? = null

    fun appState(context: Context): AppStateRepository =
        appState ?: synchronized(this) {
            appState ?: AppStateRepository(database(context)).also { appState = it }
        }

    private fun database(context: Context): DechainerDatabase =
        database ?: synchronized(this) {
            database ?: DechainerDatabase(context).also { database = it }
        }

    /** For tests: close and forget the database so the next call opens a fresh one. */
    internal fun resetForTests() = synchronized(this) {
        database?.close()
        database = null
        appState = null
    }
}
