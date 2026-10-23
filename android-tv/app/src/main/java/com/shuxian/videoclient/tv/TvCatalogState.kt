package com.shuxian.videoclient.tv

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class TvPosterItem(
    val key: String,
    val title: String,
    val poster: String,
    val subtitle: String,
    val video: VideoItem? = null,
)

internal fun VideoItem.toPosterItem() = TvPosterItem(
    key, title, poster,
    listOf(sourceName, remarks).filter { it.isNotBlank() }.joinToString(" · "),
    this,
)

// 一次只加载一页。切换分类取消旧请求；翻页失败保留已有海报，并重试原页。
@Stable
internal class TvCatalogState(private val repository: VodRepository, private val scope: CoroutineScope) {
    var items by mutableStateOf<List<TvPosterItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    var hasMore by mutableStateOf(false)
        private set

    private var request: Job? = null
    private var nextPage = 0
    private val seenKeys = HashSet<String>()
    private var section = "movie"
    private var tag = "热门"
    private var source: VodSource? = null
    private var categoryId: Int? = null

    fun select(section: String, tag: String, source: VodSource?, categoryId: Int?) {
        request?.cancel()
        request = null
        this.section = section
        this.tag = tag
        this.source = source
        this.categoryId = categoryId
        nextPage = 0
        seenKeys.clear()
        items = emptyList()
        error = ""
        loading = false
        hasMore = section != "history" && (section != "custom" || source != null)
        loadNextPage()
    }

    fun loadNextPage() {
        if (loading || !hasMore) return
        loading = true
        error = ""
        request = scope.launch {
            try {
                // 遥控器快速横移时只请求最终停留的分类，翻页不增加等待。
                if (nextPage == 0) delay(150)
                val result = if (section == "custom") {
                    val page = repository.sourceCatalog(requireNotNull(source), categoryId, nextPage + 1)
                    page.items.map { it.toPosterItem() } to page.hasMore
                } else {
                    val page = repository.catalog(section, tag, nextPage)
                    page.map {
                        TvPosterItem("catalog:${it.id}", it.title, it.poster,
                            it.rating.takeIf(String::isNotBlank)?.let { rating -> "★ $rating" } ?: it.info)
                    } to (page.size == 24)
                }
                // 网络返回时可能已经切换分类，取消的任务不能再写入新列表。
                currentCoroutineContext().ensureActive()
                val added = result.first.filter { seenKeys.add(it.key) }
                items = items + added
                nextPage++
                // 某些源忽略页码反复返回同一页，无新增内容时停止自动翻页。
                hasMore = result.second && added.isNotEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "内容加载失败"
            } finally {
                if (currentCoroutineContext().isActive) loading = false
            }
        }
    }
}
