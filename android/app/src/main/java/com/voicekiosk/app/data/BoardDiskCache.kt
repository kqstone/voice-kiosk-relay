package com.voicekiosk.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * Offline disk cache for relay bridge boards.
 * Saves board JSONs and manifest to cacheDir (default context.filesDir/boards_cache/)
 * so boards can be loaded instantly on startup or when bridge/network is offline.
 */
class BoardDiskCache(val cacheDir: File) {

    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    companion object {
        private const val TAG = "BoardDiskCache"
        const val DIR_NAME = "boards_cache"
        const val MANIFEST_FILE = "manifest.json"
    }

    init {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create board cache dir", e)
        }
    }

    @Synchronized
    fun saveBoard(boardId: String, rawJson: String) {
        try {
            val file = File(cacheDir, getBoardFilename(boardId))
            file.writeText(rawJson, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save board $boardId to disk cache", e)
        }
    }

    @Synchronized
    fun saveManifest(boardIds: List<String>) {
        try {
            val arr = JSONArray(boardIds)
            val file = File(cacheDir, MANIFEST_FILE)
            file.writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save board manifest to disk cache", e)
        }
    }

    @Synchronized
    fun loadManifest(): List<String> {
        val file = File(cacheDir, MANIFEST_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            val arr = JSONArray(text)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read board manifest", e)
            emptyList()
        }
    }

    @Synchronized
    fun loadBoard(boardId: String): BoardModel? {
        val file = File(cacheDir, getBoardFilename(boardId))
        if (!file.exists()) return null
        return try {
            val text = file.readText(Charsets.UTF_8)
            BoardParser.parseBoard(boardId, text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cached board $boardId", e)
            null
        }
    }

    @Synchronized
    fun loadAllBoards(): List<BoardModel> {
        val manifest = loadManifest()
        val boards = mutableListOf<BoardModel>()
        if (manifest.isNotEmpty()) {
            for (id in manifest) {
                loadBoard(id)?.let { boards.add(it) }
            }
        } else {
            // Fallback: list all board_*.json files
            val files = cacheDir.listFiles { _, name -> name.startsWith("board_") && name.endsWith(".json") }
            files?.forEach { file ->
                try {
                    val text = file.readText(Charsets.UTF_8)
                    val id = file.name.removePrefix("board_").removeSuffix(".json")
                    boards.add(BoardParser.parseBoard(id, text))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load cached board from ${file.name}", e)
                }
            }
        }
        return boards
    }

    @Synchronized
    fun deleteBoard(boardId: String) {
        try {
            val file = File(cacheDir, getBoardFilename(boardId))
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete board file for $boardId", e)
        }
    }

    private fun getBoardFilename(boardId: String): String {
        val safe = boardId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return "board_$safe.json"
    }
}
