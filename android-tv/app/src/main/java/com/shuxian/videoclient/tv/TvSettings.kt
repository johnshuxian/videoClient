package com.shuxian.videoclient.tv

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

data class TvHistoryEntry(
    val item: VideoItem,
    val lineName: String,
    val lineIndex: Int,
    val episodeLabel: String,
    val episodeIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val key: String get() = "${item.sourceUrl}:${item.id}"
}

class TvSettings(context: Context) {
    private val database = context.openOrCreateDatabase("settings.db", Context.MODE_PRIVATE, null)

    init {
        database.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        database.execSQL("""CREATE TABLE IF NOT EXISTS play_history (
            key TEXT PRIMARY KEY,
            item_json TEXT NOT NULL,
            title TEXT NOT NULL DEFAULT '',
            source_url TEXT NOT NULL DEFAULT '',
            line_name TEXT NOT NULL,
            line_index INTEGER NOT NULL,
            episode_label TEXT NOT NULL,
            episode_index INTEGER NOT NULL,
            position_ms INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""")
        database.execSQL("CREATE TABLE IF NOT EXISTS detail_cache (key TEXT PRIMARY KEY, detail_json TEXT NOT NULL)")
        val historyColumns = database.rawQuery("PRAGMA table_info(play_history)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        if ("title" !in historyColumns) database.execSQL("ALTER TABLE play_history ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        if ("source_url" !in historyColumns) database.execSQL("ALTER TABLE play_history ADD COLUMN source_url TEXT NOT NULL DEFAULT ''")
        // 旧记录只有 item_json；回填后才能按片名和来源查最新一条。
        val oldRows = database.rawQuery("SELECT key, item_json FROM play_history WHERE title = '' OR source_url = ''", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
        oldRows.forEach { (key, json) ->
            val item = JSONObject(json)
            database.update("play_history", ContentValues().apply {
                put("title", item.optString("title"))
                put("source_url", item.optString("sourceUrl"))
            }, "key = ?", arrayOf(key))
        }
        database.execSQL("CREATE INDEX IF NOT EXISTS play_history_title_updated ON play_history (title, updated_at DESC)")
        val preferences = context.getSharedPreferences("tv_settings", Context.MODE_PRIVATE)
        val legacySettings = preferences.all
        // 先迁移数据库中的旧详情，再迁移 SharedPreferences；成功后才清理旧值。
        database.beginTransaction()
        try {
            database.execSQL("""INSERT OR IGNORE INTO detail_cache (key, detail_json)
                SELECT substr(key, 8), value FROM settings WHERE key LIKE 'detail:%'""")
            database.execSQL("DELETE FROM settings WHERE key LIKE 'detail:%'")
            legacySettings.forEach { (key, value) ->
                if (key.startsWith("detail:") && value is String) {
                    val row = ContentValues().apply {
                        put("key", key.removePrefix("detail:"))
                        put("detail_json", value)
                    }
                    database.insertWithOnConflict("detail_cache", null, row, SQLiteDatabase.CONFLICT_IGNORE)
                } else if (key != "history") {
                    val text = when (value) {
                        is String -> value
                        is Boolean -> value.toString()
                        else -> null
                    }
                    if (text != null) {
                        val row = ContentValues().apply { put("key", key); put("value", text) }
                        database.insertWithOnConflict("settings", null, row, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
            }
            val oldHistory = legacySettings["history"] as? String
            if (oldHistory != null) {
                val entries = JSONArray(oldHistory)
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: continue
                    val video = entry.optJSONObject("video") ?: continue
                    val item = videoFromJson(video) ?: continue
                    val record = TvHistoryEntry(
                        item, entry.optString("lineName"), entry.optInt("lineIndex"),
                        entry.optString("episodeLabel"), entry.optInt("episodeIndex"),
                        entry.optLong("positionMs"), entry.optLong("durationMs"), entry.optLong("updatedAt"),
                    )
                    database.insertWithOnConflict("play_history", null, historyValues(record), SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        if (legacySettings.isNotEmpty()) preferences.edit().clear().apply()
    }

    private fun getSetting(key: String): String? = database.rawQuery(
        "SELECT value FROM settings WHERE key = ?", arrayOf(key),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun setSetting(key: String, value: String) {
        val row = ContentValues().apply { put("key", key); put("value", value) }
        database.insertWithOnConflict("settings", null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun videoFromJson(video: JSONObject): VideoItem? {
        val id = video.optString("id")
        val sourceUrl = video.optString("sourceUrl")
        if (id.isBlank() || sourceUrl.isBlank()) return null
        return VideoItem(
            id, video.optString("title"), video.optString("poster"), video.optString("year"),
            video.optString("remarks"), video.optString("sourceName"), sourceUrl, video.optString("rating"),
        )
    }

    private fun historyValues(entry: TvHistoryEntry) = ContentValues().apply {
        put("key", entry.key)
        put("item_json", JSONObject().apply {
            put("id", entry.item.id)
            put("title", entry.item.title)
            put("poster", entry.item.poster)
            put("year", entry.item.year)
            put("remarks", entry.item.remarks)
            put("sourceName", entry.item.sourceName)
            put("sourceUrl", entry.item.sourceUrl)
            put("rating", entry.item.rating)
        }.toString())
        put("title", entry.item.title)
        put("source_url", entry.item.sourceUrl)
        put("line_name", entry.lineName)
        put("line_index", entry.lineIndex)
        put("episode_label", entry.episodeLabel)
        put("episode_index", entry.episodeIndex)
        put("position_ms", entry.positionMs)
        put("duration_ms", entry.durationMs)
        put("updated_at", entry.updatedAt)
    }

    // 详情单独保存，播放进度频繁更新时无需反复序列化整部剧的集数和线路。
    fun cachedDetail(item: VideoItem): VideoDetail? {
        val raw = database.rawQuery(
            "SELECT detail_json FROM detail_cache WHERE key = ?", arrayOf("${item.sourceUrl}:${item.id}"),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return null
        val saved = JSONObject(raw)
        val lines = saved.optJSONArray("lines") ?: return null
        return VideoDetail(
            saved.optString("title"), saved.optString("poster"), saved.optString("year"),
            saved.optString("area"), saved.optString("actors"), saved.optString("description"),
            buildList {
                for (lineIndex in 0 until lines.length()) {
                    val line = lines.optJSONObject(lineIndex) ?: continue
                    val episodes = line.optJSONArray("episodes") ?: continue
                    add(PlayLine(line.optString("name"), buildList {
                        for (episodeIndex in 0 until episodes.length()) {
                            val episode = episodes.optJSONObject(episodeIndex) ?: continue
                            add(Episode(episode.optString("label"), episode.optString("url")))
                        }
                    }))
                }
            },
        )
    }

    fun saveDetail(item: VideoItem, detail: VideoDetail) {
        val saved = JSONObject().apply {
            put("title", detail.title)
            put("poster", detail.poster)
            put("year", detail.year)
            put("area", detail.area)
            put("actors", detail.actors)
            put("description", detail.description)
            put("lines", JSONArray().apply {
                detail.lines.forEach { line ->
                    put(JSONObject().apply {
                        put("name", line.name)
                        put("episodes", JSONArray().apply {
                            line.episodes.forEach { episode ->
                                put(JSONObject().apply {
                                    put("label", episode.label)
                                    put("url", episode.url)
                                })
                            }
                        })
                    })
                }
            })
        }
        val row = ContentValues().apply {
            put("key", "${item.sourceUrl}:${item.id}")
            put("detail_json", saved.toString())
        }
        database.insertWithOnConflict("detail_cache", null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearHistory() {
        database.beginTransaction()
        try {
            database.delete("play_history", null, null)
            database.delete("detail_cache", null, null)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    var subscriptionUrl: String
        get() = getSetting("subscription_url").orEmpty()
        set(value) { setSetting("subscription_url", value) }

    var exactSearch: Boolean
        get() = getSetting("exact_search") == "true"
        set(value) { setSetting("exact_search", value.toString()) }

    // 旧安装没有保存列数时默认六列，只读取界面提供的三档数量。
    var posterColumns: Int
        get() = getSetting("poster_columns")?.toIntOrNull()?.takeIf { it in listOf(5, 6, 8) } ?: 6
        set(value) { setSetting("poster_columns", value.toString()) }

    var selectedSourceUrl: String
        get() = getSetting("selected_source_url").orEmpty()
        set(value) { setSetting("selected_source_url", value) }

    var sources: List<VodSource>
        get() {
            val array = JSONArray(getSetting("sources") ?: "[]")
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(VodSource(item.optString("name"), item.optString("url"), item.optBoolean("adult"), item.optBoolean("enabled")))
                }
            }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { source ->
                array.put(JSONObject().apply {
                    put("name", source.name)
                    put("url", source.url)
                    put("adult", source.isAdult)
                    put("enabled", source.enabled)
                })
            }
            setSetting("sources", array.toString())
        }

    private fun historyEntry(cursor: Cursor): TvHistoryEntry? {
        val item = videoFromJson(JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("item_json")))) ?: return null
        return TvHistoryEntry(
            item,
            cursor.getString(cursor.getColumnIndexOrThrow("line_name")),
            cursor.getInt(cursor.getColumnIndexOrThrow("line_index")),
            cursor.getString(cursor.getColumnIndexOrThrow("episode_label")),
            cursor.getInt(cursor.getColumnIndexOrThrow("episode_index")),
            cursor.getLong(cursor.getColumnIndexOrThrow("position_ms")),
            cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
            cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
        )
    }

    fun latestOtherSourceHistory(title: String, sourceUrl: String): TvHistoryEntry? = database.rawQuery(
        """SELECT * FROM play_history WHERE title = ? AND source_url <> ?
            ORDER BY updated_at DESC, key DESC LIMIT 1""", arrayOf(title, sourceUrl),
    ).use { cursor -> if (cursor.moveToFirst()) historyEntry(cursor) else null }

    val history: List<TvHistoryEntry>
        get() {
            val cursor = database.rawQuery("SELECT * FROM play_history ORDER BY updated_at DESC", null)
            return cursor.use {
                buildList {
                    while (cursor.moveToNext()) {
                        historyEntry(cursor)?.let { add(it) }
                    }
                }
            }
        }

    fun upsertHistory(entry: TvHistoryEntry) {
        database.insertWithOnConflict("play_history", null, historyValues(entry), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteHistory(key: String) {
        database.beginTransaction()
        try {
            database.delete("play_history", "key = ?", arrayOf(key))
            database.delete("detail_cache", "key = ?", arrayOf(key))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }
}
