package com.nahfid.docvault

import android.app.Application
import com.nahfid.docvault.data.VaultRepository

class DocVaultApp : Application() {
    /** Single repository instance, shared by MainActivity and BubbleService. */
    val repository: VaultRepository by lazy { VaultRepository(this) }
}
