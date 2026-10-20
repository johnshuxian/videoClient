package com.shuxian.videoclient.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

data class VodSource(val name: String, val url: String, val isAdult: Boolean, val enabled: Boolean)
data class VodCategory(val id: Int, val name: String, val parentId: Int)
data class CatalogItem(val title: String, val poster: String, val rating: String, val info: String)
data class VideoItem(
    val id: String,
    val title: String,
    val poster: String,
    val year: String,
    val remarks: String,
    val sourceName: String,
    val sourceUrl: String,
    val rating: String,
)
data class Episode(val label: String, val url: String)
data class PlayLine(val name: String, val episodes: List<Episode>)
data class VideoDetail(
    val title: String,
    val poster: String,
    val year: String,
    val area: String,
    val actors: String,
    val description: String,
    val lines: List<PlayLine>,
)
data class VodPage(val items: List<VideoItem>, val hasMore: Boolean)

class VodRepository {
    private val posterCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun endpoint(address: String, vararg parameters: Pair<String, String>): String {
        val original = Uri.parse(address)
        val replaced = parameters.map { it.first }.toSet()
        val builder = original.buildUpon().clearQuery()
        // 订阅源 URL 可能自带 ac、wd 等参数；这里和其他端一样覆盖同名参数。
        original.queryParameterNames.filterNot { it in replaced }.forEach { key ->
            original.getQueryParameters(key).forEach { value -> builder.appendQueryParameter(key, value) }
        }
        parameters.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun connection(address: String, referer: String? = null): HttpURLConnection {
        val url = URL(address)
        require(url.protocol == "http" || url.protocol == "https") { "地址必须以 http 开头" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36")
            // 图片和接口可能检查来源；默认使用目标站点，豆瓣海报使用豆瓣页面。
            setRequestProperty("Referer", referer ?: if (url.host.endsWith("doubanio.com"))
                "https://movie.douban.com/" else "${url.protocol}://${url.authority}/")
        }
    }

    private suspend fun text(address: String, referer: String? = null, timeout: Int = 12_000): String = withContext(Dispatchers.IO) {
        val request = connection(address, referer)
        request.connectTimeout = timeout
        request.readTimeout = timeout
        try {
            if (request.responseCode !in 200..299) error("HTTP ${request.responseCode}")
            request.inputStream.bufferedReader().use { it.readText() }
        } finally {
            request.disconnect()
        }
    }

    private suspend fun json(address: String, timeout: Int = 12_000) = JSONObject(text(address, timeout = timeout))

    suspend fun importSources(address: String): List<VodSource> {
        val value = JSONTokener(text(address.trim())).nextValue()
        val entries = when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("sources")
            else -> null
        } ?: error("订阅文件需要包含 sources 数组")
        val seen = mutableSetOf<String>()
        val sources = buildList {
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if ((!url.startsWith("http://") && !url.startsWith("https://")) || !seen.add(url)) continue
                val name = item.optString("name").trim().ifEmpty { Uri.parse(url).host ?: url }
                val isAdult = item.optBoolean("isAdult")
                add(VodSource(name, url, isAdult, !isAdult))
            }
        }
        if (sources.isEmpty()) error("订阅文件中没有有效的点播源")
        return sources
    }

    suspend fun catalog(section: String, tag: String, page: Int): List<CatalogItem> {
        val type = if (section == "variety") "tv" else section
        val actualTag = when {
            section == "variety" -> "综艺"
            section == "tv" && tag == "最新" -> "热门"
            else -> tag
        }
        val address = endpoint(
            "https://movie.douban.com/j/search_subjects",
            "type" to type,
            "tag" to actualTag,
            "sort" to if (tag == "最新") "time" else "recommend",
            "page_limit" to "24",
            "page_start" to (page * 24).toString(),
        )
        val subjects = json(address).optJSONArray("subjects") ?: JSONArray()
        return buildList {
            for (index in 0 until subjects.length()) {
                val item = subjects.optJSONObject(index) ?: continue
                val title = item.optString("title")
                if (title.isNotBlank()) add(
                    CatalogItem(title, item.optString("cover"), item.optString("rate"), item.optString("episodes_info"))
                )
            }
        }
    }

    suspend fun categories(source: VodSource): List<VodCategory> {
        val entries = json(source.url).optJSONArray("class") ?: error("该点播源没有分类信息")
        return buildList {
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val id = item.optInt("type_id")
                val name = item.optString("type_name").trim()
                if (id > 0 && name.isNotEmpty()) add(VodCategory(id, name, item.optInt("type_pid")))
            }
        }
    }

    private fun posterAddress(poster: String, sourceUrl: String): String {
        if (poster.isBlank()) return ""
        return try { URL(URL(sourceUrl), poster).toString() } catch (_: Exception) { "" }
    }

    private fun videoItem(item: JSONObject, source: VodSource): VideoItem? {
        val id = item.optString("vod_id")
        val title = item.optString("vod_name")
        if (id.isBlank() || title.isBlank()) return null
        return VideoItem(
            id, title, posterAddress(item.optString("vod_pic"), source.url),
            item.optString("vod_year"), item.optString("vod_remarks"),
            source.name, source.url, item.optString("vod_score"),
        )
    }

    suspend fun sourceCatalog(source: VodSource, categoryId: Int?, page: Int): VodPage {
        val parameters = mutableListOf("ac" to "videolist", "pg" to page.toString())
        if (categoryId != null) parameters.add("t" to categoryId.toString())
        val data = json(endpoint(source.url, *parameters.toTypedArray()))
        if (!data.has("list") && data.optInt("total", -1) != 0) error("点播源未返回影片列表")
        val entries = data.optJSONArray("list") ?: JSONArray()
        val items = buildList<VideoItem> {
            for (index in 0 until entries.length()) {
                entries.optJSONObject(index)?.let { entry -> videoItem(entry, source)?.let { add(it) } }
            }
        }
        return VodPage(items, page < data.optInt("pagecount", page))
    }

    suspend fun search(
        query: String,
        sources: List<VodSource>,
        onProgress: (completed: Int, total: Int, items: List<VideoItem>, failure: String?) -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(6) // 源多时限制同时请求的数量，设置页不暴露这个内部参数。
        val enabled = sources.filter { it.enabled && !it.isAdult }
        var completed = 0
        enabled.map { source ->
            async {
                semaphore.withPermit {
                    var items = emptyList<VideoItem>()
                    var failure: String? = null
                    try {
                        val response = json(endpoint(source.url, "ac" to "videolist", "wd" to query), 8_500)
                        val entries = response.optJSONArray("list")
                        if (entries == null && response.optInt("total", -1) != 0) error("搜索接口未返回影片列表")
                        items = buildList {
                            if (entries == null) return@buildList
                            for (index in 0 until entries.length()) {
                                entries.optJSONObject(index)?.let { entry -> videoItem(entry, source)?.let { add(it) } }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure = "${source.name}：${error.message ?: "请求失败"}"
                    }
                    // 回到主协程后逐源上报，慢源不会挡住已找到的影片。
                    completed++
                    onProgress(completed, enabled.size, items, failure)
                }
            }
        }.awaitAll()
    }

    suspend fun detail(item: VideoItem): VideoDetail {
        val entries = json(endpoint(item.sourceUrl, "ac" to "videolist", "ids" to item.id)).optJSONArray("list")
        val vod = entries?.optJSONObject(0) ?: error("该资源没有详情")
        val names = vod.optString("vod_play_from").split("$$$")
        val lines = vod.optString("vod_play_url").split("$$$").mapIndexedNotNull { lineIndex, group ->
            val episodes = group.split('#').mapIndexedNotNull { episodeIndex, value ->
                val separator = value.indexOf('$')
                val label = if (separator < 0) "第 ${episodeIndex + 1} 集" else value.substring(0, separator)
                val url = if (separator < 0) value else value.substring(separator + 1)
                if (url.startsWith("http://") || url.startsWith("https://")) Episode(label.ifBlank { "第 ${episodeIndex + 1} 集" }, url)
                else null
            }
            if (episodes.isEmpty()) null else PlayLine(names.getOrNull(lineIndex).orEmpty().ifBlank { "线路 ${lineIndex + 1}" }, episodes)
        }
        val description = vod.optString("vod_content").ifBlank { vod.optString("vod_blurb") }
            .replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ").trim()
        return VideoDetail(
            vod.optString("vod_name"), posterAddress(vod.optString("vod_pic"), item.sourceUrl),
            vod.optString("vod_year"), vod.optString("vod_area"), vod.optString("vod_actor"),
            description, lines,
        )
    }

    suspend fun playableUrl(address: String): String {
        if (!Uri.parse(address).path.orEmpty().matches(Regex("/share/[^/]+/?"))) return address
        val html = text(address, "${Uri.parse(address).scheme}://${Uri.parse(address).authority}/")
        val literal = Regex("\\bvar\\s+main\\s*=\\s*(\"(?:\\\\.|[^\"\\\\])*\")")
            .find(html)?.groupValues?.get(1) ?: error("分享页未找到视频地址")
        val resolved = JSONTokener(literal).nextValue() as String
        return URL(URL(address), resolved).toString()
    }

    suspend fun poster(address: String): Bitmap? {
        if (address.isBlank()) return null
        synchronized(posterCache) { posterCache.get(address) }?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val request = connection(address)
                try {
                    // 保留图片源的像素尺寸；海报通常不大，强制采样会让封面明显变糊。
                    val bitmap = request.inputStream.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) synchronized(posterCache) { posterCache.put(address, bitmap) }
                    bitmap
                } finally { request.disconnect() }
            } catch (_: Exception) { null }
        }
    }
}
