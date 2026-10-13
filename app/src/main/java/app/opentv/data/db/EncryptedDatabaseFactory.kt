/* Nuvix modifications, Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.data.db

import android.content.Context
import android.system.Os
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.opentv.core.DatabaseKeyStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/** Defers native loading, key access, and conversion until Room's worker thread opens the DB. */
internal class EncryptedDatabaseFactory(private val context: Context) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        object : SupportSQLiteOpenHelper {
            private var delegate: SupportSQLiteOpenHelper? = null
            private var wal = true
            override val databaseName: String? get() = configuration.name
            override val writableDatabase: SupportSQLiteDatabase get() = helper().writableDatabase
            override val readableDatabase: SupportSQLiteDatabase get() = helper().readableDatabase

            override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(this) {
                wal = enabled
                delegate?.setWriteAheadLoggingEnabled(enabled)
                Unit
            }

            override fun close() = synchronized(this) { delegate?.close(); Unit }

            private fun helper(): SupportSQLiteOpenHelper = synchronized(this) {
                delegate ?: synchronized(openLock) {
                    loadNative()
                    val name = requireNotNull(configuration.name) { "Encrypted persistent database requires a name" }
                    val file = context.getDatabasePath(name)
                    val plaintext = EncryptedDatabaseMigration.isPlaintext(file)
                    val password = DatabaseKeyStore(context).getOrCreate(
                        encryptedDatabaseExists = file.exists() && file.length() > 0 && !plaintext,
                    )
                    EncryptedDatabaseMigration.convertIfNeeded(file, password)
                    retainingHelper(configuration, password, wal).also { delegate = it }
                }
            }
        }

    companion object {
        private val openLock = Any()
        private var loaded = false
        private fun loadNative() {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                Logger.setTarget(NoopTarget())
                loaded = true
            }
        }

        /**
         * SQLCipher 4.12's SupportOpenHelperFactory does not forward Room's onCorruption callback:
         * it installs a null DatabaseErrorHandler, which selects a handler that deletes the DB.
         * Bind the native handler directly and forward Room's other callbacks unchanged.
         */
        internal fun retainingHelper(
            configuration: SupportSQLiteOpenHelper.Configuration,
            password: ByteArray,
            wal: Boolean,
        ): SQLiteOpenHelper {
            val callback = configuration.callback
            val keepOriginal = DatabaseErrorHandler { _, failure ->
                throw IllegalStateException(
                    "Encrypted database could not be opened. Existing app data has been retained.",
                    failure,
                )
            }
            return object : SQLiteOpenHelper(
                configuration.context, configuration.name, password, null, callback.version,
                0, keepOriginal, null, wal,
            ) {
                override fun onConfigure(db: SQLiteDatabase) = callback.onConfigure(db)
                override fun onCreate(db: SQLiteDatabase) = callback.onCreate(db)
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    callback.onUpgrade(db, oldVersion, newVersion)
                override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    callback.onDowngrade(db, oldVersion, newVersion)
                override fun onOpen(db: SQLiteDatabase) = callback.onOpen(db)
            }
        }
    }
}

/** One-time upgrade for any pre-encryption Nuvix database; never replaces a failed conversion. */
internal object EncryptedDatabaseMigration {
    private val plaintextHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    fun isPlaintext(file: File): Boolean {
        if (!file.exists() || file.length() < plaintextHeader.size) return false
        return FileInputStream(file).use { stream ->
            val header = ByteArray(plaintextHeader.size)
            var read = 0
            while (read < header.size) {
                val count = stream.read(header, read, header.size - read)
                if (count < 0) return@use false
                read += count
            }
            header.contentEquals(plaintextHeader)
        }
    }

    fun convertIfNeeded(file: File, password: ByteArray) {
        if (!isPlaintext(file)) return
        val temporary = File(file.parentFile, "${file.name}.encrypted-tmp")
        SQLiteDatabase.deleteDatabase(temporary) // A previous interrupted export is never trusted.
        val keepOriginal = DatabaseErrorHandler { _, _ ->
            throw IllegalStateException("Database conversion failed. The original database has been retained.")
        }
        try {
            SQLiteDatabase.openDatabase(file.absolutePath, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, keepOriginal, null).use { source ->
                // Fold old WAL pages into the source before replacing its file. No Room connection
                // exists yet, and every process-local open is serialized by EncryptedDatabaseFactory.
                source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray<String>()).use { cursor ->
                    check(!cursor.moveToFirst() || cursor.getInt(0) == 0) { "Database checkpoint is busy" }
                }
                source.rawQuery("PRAGMA journal_mode=DELETE", emptyArray<String>()).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0).equals("delete", true)) { "Database journal could not be closed" }
                }
                val version = source.version
                source.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(temporary.absolutePath, password))
                source.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { it.moveToFirst() }
                source.execSQL("PRAGMA encrypted.user_version = $version")
                source.execSQL("DETACH DATABASE encrypted")
            }
            SQLiteDatabase.openDatabase(temporary.absolutePath, password, null, SQLiteDatabase.OPEN_READONLY, keepOriginal, null).use { encrypted ->
                encrypted.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Encrypted database integrity check failed" }
                }
                encrypted.rawQuery("PRAGMA cipher_integrity_check", emptyArray<String>()).use { cursor ->
                    check(!cursor.moveToFirst()) { "Encrypted database authentication check failed" }
                }
            }
            check(!isPlaintext(temporary) && temporary.length() > 0) { "Database export was not encrypted" }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            // rename(2) atomically replaces within this directory: crashes/failures leave either
            // the original complete plaintext DB or the complete validated encrypted DB.
            Os.rename(temporary.absolutePath, file.absolutePath)
        } catch (failure: Exception) {
            throw IllegalStateException("Database encryption could not be completed. The original app data has been retained.", failure)
        } finally {
            SQLiteDatabase.deleteDatabase(temporary)
        }
    }
}
