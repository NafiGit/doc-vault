package com.nahfid.docvault.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nahfid.docvault.data.VaultFile
import com.nahfid.docvault.data.VaultFolder
import com.nahfid.docvault.data.VaultRepository
import com.nahfid.docvault.data.VaultState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * One ViewModel drives both surfaces: the in-app vault screen and the
 * floating bubble panel. They observe the same StateFlow, so a rename
 * or reorder made in one place shows up in the other.
 */
class VaultViewModel(private val repository: VaultRepository) : ViewModel() {

    private val _state = MutableStateFlow(repository.current())
    val state: StateFlow<VaultState> = _state.asStateFlow()

    private fun refresh() { _state.value = repository.current() }

    fun topLevelFolders(): List<VaultFolder> = repository.topLevelFolders()
    fun childFolders(parentId: String): List<VaultFolder> = repository.childFolders(parentId)
    fun filesIn(folderId: String): List<VaultFile> = repository.filesIn(folderId)
    fun storedFile(entry: VaultFile): File? = repository.storedFile(entry)

    fun addFolder(name: String, parentId: String?) {
        if (name.isBlank()) return
        repository.addFolder(name, parentId)
        refresh()
    }

    fun renameFolder(id: String, newName: String) {
        if (newName.isBlank()) return
        repository.renameFolder(id, newName)
        refresh()
    }

    fun deleteFolder(id: String) {
        repository.deleteFolder(id)
        refresh()
    }

    /** Drag-to-reorder: move folder `id` to position `toIndex` among siblings. */
    fun moveFolder(id: String, toIndex: Int) {
        repository.moveFolder(id, toIndex)
        refresh()
    }

    fun importFile(folderId: String, uri: Uri, displayName: String, mimeType: String?) {
        viewModelScope.launch {
            repository.importFile(folderId, uri, displayName, mimeType)
            refresh()
        }
    }

    fun renameFile(id: String, newName: String) {
        repository.renameFile(id, newName)
        refresh()
    }

    fun deleteFile(id: String) {
        repository.deleteFile(id)
        refresh()
    }

    fun moveFileToFolder(fileId: String, toFolderId: String) {
        repository.moveFileToFolder(fileId, toFolderId)
        refresh()
    }
}
