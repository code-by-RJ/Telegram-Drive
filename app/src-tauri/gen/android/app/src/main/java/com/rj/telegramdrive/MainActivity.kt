package com.rj.telegramdrive

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import android.os.Build
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment

class MainActivity : TauriActivity() {

    companion object {
        private var instance: WeakReference<MainActivity>? = null
        private val uriCacheMap = ConcurrentHashMap<String, CachedFileEntry>()
        private val shareCount = AtomicInteger(0)

        data class CachedFileEntry(
            val uri: String,
            val cachedPath: String,
            val fileName: String,
            val fileSize: Long
        )

        @JvmStatic
        fun openFileExternally(path: String, mimeType: String): Boolean {
            return try {
                val activity = instance?.get() ?: return false
                val file = File(path)
                if (!file.exists()) return false
                val uri: Uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e("MainActivity", "openFileExternally error: ${e.message}", e)
                false
            }
        }

        @JvmStatic
        fun getAndClearShareCount(): Int {
            return shareCount.getAndSet(0)
        }

        @JvmStatic
        fun listCachedFiles(): String {
            return try {
                val arr = JSONArray()
                for ((_, entry) in uriCacheMap) {
                    val obj = JSONObject()
                    obj.put("uri", entry.uri)
                    obj.put("cached_path", entry.cachedPath)
                    obj.put("file_name", entry.fileName)
                    obj.put("file_size", entry.fileSize)
                    arr.put(obj)
                }
                arr.toString()
            } catch (e: Exception) {
                Log.e("MainActivity", "listCachedFiles error: ${e.message}", e)
                "[]"
            }
        }

        @JvmStatic
        fun removeCachedPath(uri: String) {
            try {
                val entry = uriCacheMap.remove(uri)
                entry?.let { File(it.cachedPath).delete() }
            } catch (e: Exception) {
                Log.e("MainActivity", "removeCachedPath error: ${e.message}", e)
            }
        }

        @JvmStatic
        fun getCachedPath(uri: String): String {
            return uriCacheMap[uri]?.cachedPath ?: ""
        }

        @JvmStatic
        fun getLocalFileFromUri(uri: String): String {
            return try {
                val activity = instance?.get() ?: return ""
                val androidUri = Uri.parse(uri)
                val fileName = getFileName(activity, androidUri) ?: "shared_file"
                val cachedFile = File(activity.cacheDir, fileName)
                activity.contentResolver.openInputStream(androidUri)?.use { input ->
                    FileOutputStream(cachedFile).use { output -> input.copyTo(output) }
                }
                uriCacheMap[uri] = CachedFileEntry(uri, cachedFile.absolutePath, fileName, cachedFile.length())
                shareCount.incrementAndGet()
                cachedFile.absolutePath
            } catch (e: Exception) {
                Log.e("MainActivity", "getLocalFileFromUri error: ${e.message}", e)
                ""
            }
        }

        @JvmStatic
        fun saveFileToPublicDownloads(sourcePath: String, fileName: String, mimeType: String): Boolean {
            return try {
                val activity = instance?.get() ?: return false
                val sourceFile = File(sourcePath)
                if (!sourceFile.exists()) return false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = activity.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        resolver.openOutputStream(it)?.use { output ->
                            sourceFile.inputStream().use { input -> input.copyTo(output) }
                        }
                        contentValues.clear()
                        contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(it, contentValues, null, null)
                    }
                } else {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    downloadsDir.mkdirs()
                    val destFile = File(downloadsDir, fileName)
                    sourceFile.copyTo(destFile, overwrite = true)
                }
                true
            } catch (e: Exception) {
                Log.e("MainActivity", "saveFileToPublicDownloads error: ${e.message}", e)
                false
            }
        }

        private fun getFileName(activity: MainActivity, uri: Uri): String? {
            var name: String? = null
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
            return name ?: uri.lastPathSegment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = WeakReference(this)
        handleSharedIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            uri?.let { Companion.getLocalFileFromUri(it.toString()) }
        }
    }
}