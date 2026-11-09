package com.nahfid.docvault.data

import java.util.UUID

/**
 * Seed data: folder structure only, with placeholder file entries.
 *
 * No real documents are bundled. No certificate, transcript, ticket
 * number, key or personal file is in this project. The placeholders
 * name the kind of document that belongs in each folder so the user
 * can tap one and import the real file from the device.
 */
object SeedData {

    fun build(): VaultState {
        val folders = mutableListOf<VaultFolder>()
        val files = mutableListOf<VaultFile>()
        fun folder(name: String, parentId: String?, order: Int): VaultFolder {
            val f = VaultFolder(id = UUID.randomUUID().toString(), name = name, parentId = parentId, order = order)
            folders += f
            return f
        }
        fun placeholder(folder: VaultFolder, displayName: String, order: Int) {
            files += VaultFile(
                id = UUID.randomUUID().toString(),
                folderId = folder.id,
                displayName = displayName,
                order = order,
                isPlaceholder = true
            )
        }

        val school = folder("School", null, 0)
        val classX = folder("Class X", school.id, 0)
        val classXII = folder("Class XII", school.id, 1)
        placeholder(classX, "Class X Certificate.pdf", 0)
        placeholder(classX, "Class X Marksheet.pdf", 1)
        placeholder(classXII, "Class XII Certificate.pdf", 0)
        placeholder(classXII, "Class XII Marksheet.pdf", 1)

        val university = folder("University", null, 1)
        val transcripts = folder("Transcripts Sem 1-8", university.id, 0)
        val degree = folder("Degree", university.id, 1)
        val cmm = folder("CMM", university.id, 2)
        val pcc = folder("PCC", university.id, 3)
        val tc = folder("Transfer Certificate", university.id, 4)
        placeholder(transcripts, "Semester 1 Transcript.pdf", 0)
        placeholder(transcripts, "Semester 2 Transcript.pdf", 1)
        placeholder(transcripts, "Semester 3 Transcript.pdf", 2)
        placeholder(transcripts, "Semester 4 Transcript.pdf", 3)
        placeholder(transcripts, "Semester 5 Transcript.pdf", 4)
        placeholder(transcripts, "Semester 6 Transcript.pdf", 5)
        placeholder(transcripts, "Semester 7 Transcript.pdf", 6)
        placeholder(transcripts, "Semester 8 Transcript.pdf", 7)
        placeholder(degree, "Degree Certificate.pdf", 0)
        placeholder(cmm, "Consolidated Marks Memo.pdf", 0)
        placeholder(pcc, "Medium of Instruction Certificate.pdf", 0)
        placeholder(tc, "Transfer Certificate.pdf", 0)

        return VaultState(folders = folders, files = files, seeded = true)
    }
}
