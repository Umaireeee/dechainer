package io.github.warleysr.dechainer.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The app's one SQLite database: plain `SQLiteOpenHelper`, WAL on, schema versioned by
 * [Migrations]. No code generation. A downgrade (an older build over a newer one) is not
 * supported and fails when opened, which the callers treat as "store unavailable".
 */
class DechainerDatabase(context: Context, name: String? = FILE_NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, Migrations.LATEST) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = migrate(db, 0, Migrations.LATEST)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = migrate(db, oldVersion, newVersion)

    // The helper already runs both callbacks inside one transaction: all of a migration or none of it.
    private fun migrate(db: SQLiteDatabase, from: Int, to: Int) {
        Migrations.statementsBetween(from, to).forEach { db.execSQL(it) }
    }

    companion object {
        const val FILE_NAME = "dechainer.db"
    }
}
