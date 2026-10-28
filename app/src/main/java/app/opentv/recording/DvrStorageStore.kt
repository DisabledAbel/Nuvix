/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.content.Context
import java.security.MessageDigest

/** Device-local policies and creation receipts. Never exported through NAS/cloud settings sync. */
class DvrStorageStore(context: Context) {
    private val prefs = context.getSharedPreferences("dvr_storage", Context.MODE_PRIVATE)

    fun policy(destination: String) = DvrStoragePolicy(
        warnings = prefs.getBoolean("$destination.warnings", true),
        warningBytes = prefs.getLong("$destination.warning", DvrStoragePolicy.GIB).coerceIn(DvrStoragePolicy.GIB, 100 * DvrStoragePolicy.GIB),
        cleanupEnabled = prefs.getBoolean("$destination.cleanup", false),
        targetBytes = prefs.getLong("$destination.target", 2 * DvrStoragePolicy.GIB).coerceIn(DvrStoragePolicy.GIB, 100 * DvrStoragePolicy.GIB),
        retentionDays = prefs.getInt("$destination.retention", 30).coerceIn(1, 3650),
    )

    /** The UI must explain the policy and obtain separate per-destination consent before enabling. */
    fun save(destination: String, policy: DvrStoragePolicy): Boolean = prefs.edit()
        .putBoolean("$destination.warnings", policy.warnings)
        .putLong("$destination.warning", policy.warningBytes.coerceIn(DvrStoragePolicy.GIB, 100 * DvrStoragePolicy.GIB))
        .putBoolean("$destination.cleanup", policy.cleanupEnabled)
        .putLong("$destination.target", policy.targetBytes.coerceIn(DvrStoragePolicy.GIB, 100 * DvrStoragePolicy.GIB))
        .putInt("$destination.retention", policy.retentionDays.coerceIn(1, 3650)).commit()

    /** Only called after Nuvix successfully creates its own output, with the resolved SAF locator. */
    fun created(id: Long, locator: String, destination: String): Boolean =
        prefs.edit().putString("owned.${fingerprint(locator)}", "$id:$destination").commit()

    fun owned(id: Long, locator: String, destination: String): Boolean =
        prefs.getString("owned.${fingerprint(locator)}", null) == "$id:$destination"

    fun destination(id: Long, locator: String): String? =
        prefs.getString("owned.${fingerprint(locator)}", null)?.takeIf { it.startsWith("$id:") }?.substringAfter(':')

    fun forget(locator: String) { prefs.edit().remove("owned.${fingerprint(locator)}").apply() }
    fun report(destination: String, message: String) { prefs.edit().putString("$destination.report", message).apply() }
    fun report(destination: String): String? = prefs.getString("$destination.report", null)

    companion object {
        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
