/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.core.ServiceLocator
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Free, server-less "cloud" sync through the user's own NAS.
 *
 * There is no server of ours and no account: every device drops its own [SyncBundle] as a small
 * JSON file into one shared folder on the SMB share the user already set up for recordings, and
 * reads back every other device's file. The folder is a plain rendezvous — the merge logic lives
 * in [SyncEngine], exactly the one used by LAN sync, so favourites, watch history and NAS
 * recordings converge the same way whether they arrive over wifi or via the NAS.
 *
 * Each device writes `<share>/<folder>/OpenTV/sync/<deviceId>.json`. Reading is additive and
 * newest-wins (see [SyncEngine]); nothing on the NAS is ever deleted, so a device that has been
 * offline for months still contributes when it next syncs.
 */
class NasSync(private val graph: ServiceLocator.Graph) {

    private val engine = SyncEngine(graph)

    sealed interface Result {
        /** [merged] items pulled in from [peers] other devices' files (this device's own excluded). */
        data class Success(val merged: Int, val peers: Int) : Result

        /** No SMB/NAS is configured yet — the user needs to set one up under Recording settings. */
        data object NotConfigured : Result

        /** Anything went wrong reaching or reading the NAS. [message] is best-effort, may be null. */
        data class Failed(val message: String?) : Result
    }

    /**
     * Publish this device's bundle, then merge in every other device's. Safe to call from anywhere:
     * all I/O is on [Dispatchers.IO], a blank/unreachable NAS returns a [Result] rather than
     * throwing, and a single unreadable peer file is skipped rather than failing the whole run.
     */
    suspend fun sync(): Result = withContext(Dispatchers.IO) {
        val config = RecordingStorage.smbConfig(graph.settings)
        if (!config.isConfigured) return@withContext Result.NotConfigured

        runCatching {
            val deviceId = graph.settings.syncDeviceId
            val ownFile = "$deviceId.json"

            // 1. Write this device's bundle. openForWrite creates the sync folder if missing.
            val bundleJson = engine.encode(engine.gather())
            SmbClient.writeText(config, locatorFor(config, ownFile), bundleJson)

            // 2. Read and merge every OTHER device's bundle.
            val peerFiles = SmbClient.list(config, syncFolderPath(config))
                .filter { it.endsWith(".json", ignoreCase = true) && it != ownFile }

            var merged = SyncEngine.MergeResult()
            var peers = 0
            for (name in peerFiles) {
                val text = runCatching { SmbClient.readText(config, locatorFor(config, name)) }
                    .getOrNull() ?: continue
                val bundle = runCatching { engine.decode(text) }.getOrNull() ?: continue
                merged += engine.apply(bundle)
                peers++
            }
            Result.Success(merged.total, peers)
        }.getOrElse { Result.Failed(it.message) }
    }

    /** `<folder>/OpenTV/sync` within the share — the shared rendezvous every device agrees on. */
    private fun syncFolderPath(config: SmbConfig): String {
        val base = config.folder.split('/', '\\').filter { it.isNotBlank() }
        return (base + listOf(SYNC_DIR, "sync")).joinToString("/")
    }

    private fun locatorFor(config: SmbConfig, fileName: String): String =
        "smb://${config.host}/${config.share}/${syncFolderPath(config)}/$fileName"

    private companion object {
        const val SYNC_DIR = "OpenTV"
    }
}
