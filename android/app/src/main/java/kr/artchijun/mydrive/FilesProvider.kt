package kr.artchijun.mydrive

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

// 받은 파일을 다른 앱(사진·PDF 보기 등)이 열 수 있게 건네는 창구. 앱 캐시 안 파일만 읽기 전용으로
class FilesProvider : ContentProvider() {
    companion object {
        fun authority(ctx: Context) = ctx.packageName + ".files"
        fun uriFor(ctx: Context, f: File): Uri {
            val rel = f.canonicalPath.removePrefix(ctx.cacheDir.canonicalPath).trimStart('/')
            return Uri.Builder().scheme("content").authority(authority(ctx)).path(rel).build()
        }
        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "apk") return "application/vnd.android.package-archive"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun fileOf(uri: Uri): File {
        val base = context!!.cacheDir.canonicalFile
        val f = File(base, uri.path ?: "").canonicalFile
        if (!f.path.startsWith(base.path + "/") || !f.isFile) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = mimeOf(uri.lastPathSegment ?: "")
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
