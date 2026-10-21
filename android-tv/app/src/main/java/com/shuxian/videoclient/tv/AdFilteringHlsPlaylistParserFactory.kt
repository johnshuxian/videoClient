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

private val adPathHint = Regex(
    "(?:^|[/_?&=-])(?:ad|ads|advert|advertise|commercial|preroll)(?:$|[/_?&=.-])",
    RegexOption.IGNORE_CASE,
)
private val adDateRange = Regex("\\bCLASS\\s*=\\s*\"?(?:ad|advert)", RegexOption.IGNORE_CASE)

internal fun filterHlsAdSegments(manifest: String): String {
    // 直播清单和字节范围清单依赖连续的序号或偏移，保留原文更稳妥。
    if (!manifest.contains("#EXT-X-ENDLIST") || manifest.contains("#EXT-X-BYTERANGE")) return manifest

    val output = mutableListOf<String>()
    var dateRangeAd = false
    var cueAd = false
    var segmentStart = -1
    var skipSegment = false
    var keptSegments = 0
    var removedSegments = 0

    for (line in manifest.lines().map { it.trimEnd('\r') }) {
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
            line.startsWith("#EXTINF") -> {
                segmentStart = output.size
                skipSegment = dateRangeAd || cueAd
            }
        }

        if (line.isNotBlank() && !line.startsWith('#') && segmentStart >= 0) {
            // 只检查路径名，避免普通视频分片的签名参数里碰巧带有 ad 字段。
            if (skipSegment || adPathHint.containsMatchIn(line.substringBefore('?').substringBefore('#'))) {
                output.subList(segmentStart, output.size).clear()
                removedSegments++
            } else {
                output.add(line)
                keptSegments++
            }
            segmentStart = -1
            skipSegment = false
        } else {
            output.add(line)
        }
    }

    // 如果所有片段都被识别为广告，优先保证视频仍可播放。
    return if (removedSegments > 0 && keptSegments > 0) output.joinToString("\n") else manifest
}
