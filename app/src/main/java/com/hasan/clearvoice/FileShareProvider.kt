package com.hasan.clearvoice

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Tiny framework-only replacement for androidx FileProvider.
 * Serves WAV files from the app's recordings folder as
 * content://com.hasan.clearvoice.fileprovider/<filename> so they can be
 * shared to other apps without a file:// URI (blocked since API 24).
 */
class FileShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun resolveFile(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("No file name in $uri")
        val safeName = File(name).name // strip any path traversal
        val ctx = context ?: throw FileNotFoundException("No context")
        val musicDir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
        val candidates = listOfNotNull(
            musicDir?.let { File(it, safeName) },
            File(ctx.filesDir, safeName)
        )
        for (candidate in candidates) {
            if (candidate.exists()) return candidate
        }
        throw FileNotFoundException("File not found: $safeName")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolveFile(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "audio/wav"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        try {
            val file = resolveFile(uri)
            cursor.addRow(arrayOf<Any>(file.name, file.length()))
        } catch (e: FileNotFoundException) {
            // Return an empty cursor rather than crashing the receiving app.
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
