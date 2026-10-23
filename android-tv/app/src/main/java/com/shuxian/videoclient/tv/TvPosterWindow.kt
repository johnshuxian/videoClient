package com.shuxian.videoclient.tv

// 可见卡片先排队，然后准备前方三排，最后保留后方一排；不预取整部片库。
internal fun tvPosterPrefetchIndices(first: Int, last: Int, count: Int): List<Int> {
    if (count == 0 || first !in 0 until count || last < first) return emptyList()
    val visibleLast = minOf(last, count - 1)
    val ahead = first..minOf(visibleLast + TV_POSTER_COLUMNS * 3, count - 1)
    val behind = maxOf(0, first - TV_POSTER_COLUMNS) until first
    return ahead.toList() + behind.toList()
}
