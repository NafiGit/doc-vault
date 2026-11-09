package com.nahfid.docvault.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Local data layer. Deliberate choice: a single JSON file
 * (filesDir/vault.json) plus files under filesDir/vault/<folderId>/.
 *
 * Why JSON instead of Room: the data is tiny (folders + file metadata),
 * there is no query more complex than "files in this folder, in order",
 * and it keeps the project free of annotation processing and migrations
 * for a first version. If the vault grows past a few hundred files, or
 * needs search, move this behind the same public API to Room.
 *
 * Nothing here touches the network. No account, no cloud, no server.
 * Files are app-private: other apps can only read a file the user
 * explicitly shares, via FileProvider, one URI at a time.
 */
class VaultRepository(context: Context) {

    private val appContext = context.applicationContext
    private val storeFile = File(appContext.filesDir, "vault.json")
    private val vaultDir = File(appContext.filesDir, "vault").apply { mkdirs() }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private var state: VaultState = loadFromDisk()

    private fun loadFromDisk(): VaultState {
        if (!storeFile.exists()) return SeedData.build().also { saveToDisk(it) }
        return try {
            json.decodeFromString<VaultState>(storeFile.readText())
        } catch (_: Exception) {
            // Corrupt store: keep the files on disk, reseed the index.
            // Files are never deleted by a store failure.
            SeedData.build()
        }
    }

    private fun saveToDisk(next: VaultState) {
        state = next
        storeFile.writeText(json.encodeToString(next))
    }

    fun current(): VaultState = state

    fun topLevelFolders(): List<VaultFolder> =
        state.folders.filter { it.parentId == null }.sortedBy { it.order }

    fun childFolders(parentId: String): List<VaultFolder> =
        state.folders.filter { it.parentId == parentId }.sortedBy { it.order }

    fun folderById(id: String): VaultFolder? = state.folders.firstOrNull { it.id == id }

    fun filesIn(folderId: String): List<VaultFile> =
        state.files.filter { it.folderId == folderId }.sortedBy { it.order }

    fun fileById(id: String): VaultFile? = state.files.firstOrNull { it.id == id }

    /** Physical file on disk for a stored (non-placeholder) entry, or null. */
    fun storedFile(entry: VaultFile): File? {
        val name = entry.storedName ?: return null
        val file = File(File(vaultDir, entry.folderId), name)
        return file.takeIf { it.exists() }
    }

    fun addFolder(name: String, parentId: String?): VaultFolder {
        val siblings = state.folders.filter { it.parentId == parentId }
        val folder = VaultFolder(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            parentId = parentId,
            order = (siblings.maxOfOrNull { it.order } ?: -1) + 1
        )
        saveToDisk(state.copy(folders = state.folders + folder))
        return folder
    }

    fun renameFolder(id: String, newName: String) {
        saveToDisk(state.copy(folders = state.folders.map {
            if (it.id == id) it.copy(name = newName.trim()) else it
        }))
    }

    fun deleteFolder(id: String) {
        // Delete the folder, its children, their files on disk, and index rows.
        val doomed = state.folders.filter { it.id == id || it.parentId == id }.map { it.id }
        doomed.forEach { File(vaultDir, it).deleteRecursively() }
        saveToDisk(state.copy(
            folders = state.folders.filterNot { it.id in doomed },
            files = state.files.filterNot { it.folderId in doomed }
        ))
    }

    /** Move a folder to a new position among its siblings. */
    fun moveFolder(id: String, toIndex: Int) {
        val folder = folderById(id) ?: return
        val siblings = state.folders.filter { it.parentId == folder.parentId }
            .sortedBy { it.order }.toMutableList()
        siblings.remove(folder)
        val target = toIndex.coerceIn(0, siblings.size)
        siblings.add(target, folder)
        val reordered = siblings.mapIndexed { index, f -> f.copy(order = index) }
        val others = state.folders.filter { it.parentId != folder.parentId }
        saveToDisk(state.copy(folders = others + reordered))
    }

    /** Import a document from a Storage Access Framework Uri into a folder. */
    suspend fun importFile(folderId: String, uri: Uri, displayName: String, mimeType: String?): VaultFile? =
        withContext(Dispatchers.IO) {
            val dir = File(vaultDir, folderId).apply { mkdirs() }
            val storedName = UUID.randomUUID().toString() + "_" + displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = File(dir, storedName)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            val existing = filesIn(folderId)
            // Replace a same-named placeholder instead of duplicating it.
            val placeholder = existing.firstOrNull { it.isPlaceholder && it.displayName.equals(displayName, ignoreCase = true) }
            val entry = VaultFile(
                id = placeholder?.id ?: UUID.randomUUID().toString(),
                folderId = folderId,
                displayName = displayName,
                storedName = storedName,
                mimeType = mimeType,
                sizeBytes = dest.length(),
                order = placeholder?.order ?: existing.size,
                isPlaceholder = false
            )
            val files = if (placeholder != null) {
                state.files.map { if (it.id == placeholder.id) entry else it }
            } else {
                state.files + entry
            }
            saveToDisk(state.copy(files = files))
            entry
        }

    fun renameFile(id: String, newName: String) {
        saveToDisk(state.copy(files = state.files.map {
            if (it.id == id) it.copy(displayName = newName.trim()) else it
        }))
    }

    fun deleteFile(id: String) {
        val entry = fileById(id) ?: return
        storedFile(entry)?.delete()
        saveToDisk(state.copy(files = state.files.filterNot { it.id == id }))
    }

    /** Move a file to another folder (fast organising). Appends at the end there. */
    fun moveFileToFolder(fileId: String, toFolderId: String) {
        val entry = fileById(fileId) ?: return
        val stored = storedFile(entry)
        if (stored != null) {
            val destDir = File(vaultDir, toFolderId).apply { mkdirs() }
            val dest = File(destDir, entry.storedName!!)
            stored.renameTo(dest)
        }
        val targetOrder = filesIn(toFolderId).size
        saveToDisk(state.copy(files = state.files.map {
            if (it.id == fileId) it.copy(folderId = toFolderId, order = targetOrder) else it
        }))
    }
}
