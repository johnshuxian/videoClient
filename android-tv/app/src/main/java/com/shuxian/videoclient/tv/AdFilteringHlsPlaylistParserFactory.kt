package com.shuxian.videoclient.tv

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream

// 让 Media3 保持原有的 HLS 下载、解密和解析流程，只在解析点播清单前移除广告片段。
@androidx.annotation.OptIn(UnstableApi::class)
internal class AdFilteringHlsPlaylistParserFactory : HlsPlaylistParserFactory {
    private val delegate = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        filteringParser(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        filteringParser(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun filteringParser(parser: ParsingLoadable.Parser<HlsPlaylist>): ParsingLoadable.Parser<HlsPlaylist> =
        ParsingLoadable.Parser { uri, input ->
            val manifest = input.bufferedReader(Charsets.UTF_8).readText()
            val filtered = filterHlsAdSegments(manifest)
            parser.parse(uri, ByteArrayInputStream(filtered.toByteArray(Charsets.UTF_8)))
        }
}

private val adUrl = Regex("adjump|advert|/ads?/", RegexOption.IGNORE_CASE)
private val adDateRange = Regex("\\bCLASS\\s*=\\s*\"?(?:ad|advert)", RegexOption.IGNORE_CASE)
private val durationTag = Regex("^#EXTINF:([\\d.]+)")

private data class SegmentGroup(
    val start: Int,
    val end: Int,
    val disc: Int,
    val urls: List<String>,
    val seconds: Double,
)

private fun scanGroups(lines: List<String>): List<SegmentGroup> {
    val boundaries = mutableListOf(-1)
    lines.forEachIndexed { index, line ->
        if (line.trim() == "#EXT-X-DISCONTINUITY") boundaries.add(index)
    }
    boundaries.add(lines.size)
    return (0 until boundaries.lastIndex).map { groupIndex ->
        val start = boundaries[groupIndex] + 1
        val end = boundaries[groupIndex + 1]
        val urls = mutableListOf<String>()
        var seconds = 0.0
        for (index in start until end) {
            val line = lines[index].trim()
            val duration = durationTag.find(line)
            if (duration != null) seconds += duration.groupValues[1].toDoubleOrNull() ?: 0.0
            else if (line.isNotEmpty() && !line.startsWith("#")) urls.add(line)
        }
        SegmentGroup(start, end, boundaries[groupIndex], urls, seconds)
    }
}

private fun removeGroups(lines: List<String>, groups: List<SegmentGroup>): List<String> {
    val removed = BooleanArray(lines.size)
    for (group in groups) {
        if (group.disc >= 0) removed[group.disc] = true
        for (index in group.start until group.end) {
            // 正片可能复用广告段声明的密钥或初始化片段。
            if (!lines[index].trim().startsWith("#EXT-X-KEY:") &&
                !lines[index].trim().startsWith("#EXT-X-MAP:") &&
                lines[index].trim() != "#EXT-X-ENDLIST") removed[index] = true
        }
    }
    return lines.filterIndexed { index, _ -> !removed[index] }
}

internal fun filterHlsAdSegments(manifest: String): String {
    // 直播清单和字节范围清单依赖连续的序号或偏移，保留原文更稳妥。
    if (!manifest.contains("#EXT-X-ENDLIST") || manifest.contains("#EXT-X-BYTERANGE")) return manifest

    var filtered: List<String> = manifest.lines().map { it.trimEnd('\r') }
    var groups = scanGroups(filtered)
    // 地址规则只删除整组命中的片段，单个广告样式地址不再直接剔除。
    filtered = removeGroups(filtered, groups.filter {
        it.disc >= 0 && it.urls.isNotEmpty() && it.urls.all { url -> adUrl.containsMatchIn(url) }
    })

    groups = scanGroups(filtered)
    // 两个至少 120 秒的正片组之间，才把不超过 30 秒的组判作中插。
    filtered = removeGroups(filtered, groups.filterIndexed { index, group ->
        index > 0 && group.urls.isNotEmpty() && group.seconds > 0 && group.seconds <= 30 &&
            groups[index - 1].seconds >= 120 && (groups.getOrNull(index + 1)?.seconds ?: 0.0) >= 120
    })

    groups = scanGroups(filtered)
    val lead = groups.getOrNull(1)
    if (groups.firstOrNull()?.urls?.isEmpty() == true && lead != null && lead.urls.isNotEmpty() &&
        groups.size > 2 && lead.seconds > 0 && lead.seconds <= 90 && lead.urls.size <= 20) {
        val others = groups.drop(2).map { it.urls.size }.sorted()
        val median = others[others.size / 2]
        if (groups.size < 5 || kotlin.math.abs(lead.urls.size - median) > kotlin.math.max(3.0, median * 0.5))
            filtered = removeGroups(filtered, listOf(lead))
    }

    // 显式标记最后处理，避免删掉片头广告后把短正片误判成新的片头。
    val output = mutableListOf<String>()
    var dateRangeAd = false
    var cueAd = false
    var segmentStart = -1
    for (line in filtered) {
        when {
            line.startsWith("#EXT-X-DATERANGE") && adDateRange.containsMatchIn(line) -> {
                dateRangeAd = true
                continue
            }
            line.startsWith("#EXT-X-CUE-OUT") -> {
                cueAd = true
                continue
            }
            line.startsWith("#EXT-X-CUE-IN") -> {
                cueAd = false
                continue
            }
            line == "#EXT-X-DISCONTINUITY" -> dateRangeAd = false
            line.startsWith("#EXTINF") -> segmentStart = output.size
        }
        if (line.isNotBlank() && !line.startsWith('#') && segmentStart >= 0) {
            if (dateRangeAd || cueAd) output.subList(segmentStart, output.size).clear()
            else output.add(line)
            segmentStart = -1
        } else output.add(line)
    }
    filtered = output
    val firstSegment = filtered.indexOfFirst { it.isNotBlank() && !it.trim().startsWith("#") }
    if (firstSegment < 0) return manifest
    filtered = filtered.filterIndexed { index, line ->
        index >= firstSegment || line.trim() != "#EXT-X-DISCONTINUITY"
    }
    return filtered.joinToString("\n")
}
