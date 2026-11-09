package com.nahfid.docvault.data

import kotlinx.serialization.Serializable

/**
 * One folder. Folders can nest one level: a top-level folder has
 * parentId == null, a sub-folder points at its parent.
 * `order` is the display order inside its parent; the user reorders
 * folders by dragging, which just rewrites these numbers.
 */
@Serializable
data class VaultFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val order: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * One file inside a folder.
 *
 * The file itself lives in app-private storage at
 * files/vault/<folderId>/<storedName>. Only the metadata below is
 * kept in the JSON store; the bytes never leave the device.
 *
 * A placeholder entry (isPlaceholder = true) has no stored file yet.
 * It exists so the seeded folders show what belongs in them, e.g.
 * "Class X Certificate.pdf" under School / Class X. Tapping it
 * prompts the user to import the real file, which replaces it.
 */
@Serializable
data class VaultFile(
    val id: String,
    val folderId: String,
    val displayName: String,
    val storedName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val order: Int = 0,
    val isPlaceholder: Boolean = false
) {
    val isPdf: Boolean get() = displayName.endsWith(".pdf", ignoreCase = true)
    val isImage: Boolean get() = mimeType?.startsWith("image/") == true
        || displayName.endsWith(".jpg", ignoreCase = true)
        || displayName.endsWith(".jpeg", ignoreCase = true)
        || displayName.endsWith(".png", ignoreCase = true)
}

/** Whole vault, serialised as one JSON file. */
@Serializable
data class VaultState(
    val folders: List<VaultFolder> = emptyList(),
    val files: List<VaultFile> = emptyList(),
    val seeded: Boolean = false,
    val version: Int = 1
)
