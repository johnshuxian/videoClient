package com.shuxian.videoclient.tv

import android.content.Context
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
    private val preferences = context.getSharedPreferences("tv_settings", Context.MODE_PRIVATE)

    // 详情单独保存，播放进度频繁更新时无需反复序列化整部剧的集数和线路。
    fun cachedDetail(item: VideoItem): VideoDetail? {
        val raw = preferences.getString("detail:${item.sourceUrl}:${item.id}", null) ?: return null
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
        preferences.edit().putString("detail:${item.sourceUrl}:${item.id}", saved.toString()).apply()
    }

    fun removeDetail(item: VideoItem) {
        preferences.edit().remove("detail:${item.sourceUrl}:${item.id}").apply()
    }

    fun clearHistory() {
        // 一次写入同时清除记录和对应详情，避免逐条删除时反复写入偏好设置。
        val entries = history
        val editor = preferences.edit().putString("history", "[]")
        entries.forEach { editor.remove("detail:${it.item.sourceUrl}:${it.item.id}") }
        editor.apply()
    }

    var subscriptionUrl: String
        get() = preferences.getString("subscription_url", "").orEmpty()
        set(value) { preferences.edit().putString("subscription_url", value).apply() }

    var selectedSourceUrl: String
        get() = preferences.getString("selected_source_url", "").orEmpty()
        set(value) { preferences.edit().putString("selected_source_url", value).apply() }

    var sources: List<VodSource>
        get() {
            val array = JSONArray(preferences.getString("sources", "[]"))
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
            preferences.edit().putString("sources", array.toString()).apply()
        }

    var history: List<TvHistoryEntry>
        get() {
            val array = JSONArray(preferences.getString("history", "[]"))
            return buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    val video = entry.optJSONObject("video") ?: continue
                    val id = video.optString("id")
                    val sourceUrl = video.optString("sourceUrl")
                    if (id.isBlank() || sourceUrl.isBlank()) continue
                    add(TvHistoryEntry(
                        VideoItem(
                            id, video.optString("title"), video.optString("poster"), video.optString("year"),
                            video.optString("remarks"), video.optString("sourceName"), sourceUrl, video.optString("rating"),
                        ),
                        entry.optString("lineName"), entry.optInt("lineIndex"),
                        entry.optString("episodeLabel"), entry.optInt("episodeIndex"),
                        entry.optLong("positionMs"), entry.optLong("durationMs"), entry.optLong("updatedAt"),
                    ))
                }
            }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { entry ->
                array.put(JSONObject().apply {
                    put("video", JSONObject().apply {
                        put("id", entry.item.id)
                        put("title", entry.item.title)
                        put("poster", entry.item.poster)
                        put("year", entry.item.year)
                        put("remarks", entry.item.remarks)
                        put("sourceName", entry.item.sourceName)
                        put("sourceUrl", entry.item.sourceUrl)
                        put("rating", entry.item.rating)
                    })
                    put("lineName", entry.lineName)
                    put("lineIndex", entry.lineIndex)
                    put("episodeLabel", entry.episodeLabel)
                    put("episodeIndex", entry.episodeIndex)
                    put("positionMs", entry.positionMs)
                    put("durationMs", entry.durationMs)
                    put("updatedAt", entry.updatedAt)
                })
            }
            preferences.edit().putString("history", array.toString()).apply()
        }
}
