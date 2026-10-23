package com.shuxian.videoclient.tv

import kotlin.math.abs

internal const val TV_POSTER_COLUMNS = 6

internal enum class TvGridDirection { Left, Right, Up, Down }

// -1 表示回到筛选区；左右不跨行，向下进入不完整的末行时落到最后一张。
internal fun tvPosterTarget(index: Int, count: Int, direction: TvGridDirection, columns: Int = TV_POSTER_COLUMNS): Int {
    return when (direction) {
        TvGridDirection.Left -> if (index % columns > 0) index - 1 else index
        TvGridDirection.Right -> if (index % columns < columns - 1 && index + 1 < count) index + 1 else index
        TvGridDirection.Up -> if (index < columns) -1 else index - columns
        TvGridDirection.Down -> if ((index / columns + 1) * columns < count) {
            minOf(index + columns, count - 1)
        } else index
    }
}

// 只移动到最近的可见边缘；完整可见、或超高卡片已覆盖整个视口时不再滚动。
internal fun tvFocusScrollDistance(itemTop: Int, itemBottom: Int, viewportTop: Int, viewportBottom: Int): Int {
    val before = itemTop - viewportTop
    val after = itemBottom - viewportBottom
    if ((before >= 0 && after <= 0) || (before <= 0 && after >= 0)) return 0
    return if (abs(before) < abs(after)) before else after
}
