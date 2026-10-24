package com.shuxian.videoclient.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvHistoryResumeTest {
    private val saved = TvHistoryEntry(
        VideoItem("1", "续播测试", "", "", "", "测试源", "test", ""),
        "默认", 0, "第2集", 1, 1_800_000L, 3_600_000L, 0L,
    )

    @Test
    fun selectingTheSavedEpisodeResumesButOtherEpisodesStartAtZero() {
        val episodes = (1..3).map { Episode("第${it}集", "") }
        assertEquals(1_800_000L, saved.resumePositionFor(episodes, 1))
        assertEquals(0L, saved.resumePositionFor(episodes, 0))
        assertEquals(0L, saved.resumePositionFor(episodes, 2))
    }

    @Test
    fun selectingTheMovieResumesItsMainFeature() {
        val movie = saved.copy(episodeLabel = "正片", episodeIndex = 0)
        assertEquals(1_800_000L, movie.resumePositionFor(listOf(Episode("正片", "")), 0))
    }

    @Test
    fun reorderedEpisodesResumeByLabelInsteadOfTheOldIndex() {
        val episodes = listOf(Episode("第2集", ""), Episode("第1集", ""))
        assertEquals(1_800_000L, saved.resumePositionFor(episodes, 0))
        assertEquals(0L, saved.resumePositionFor(episodes, 1))
    }

    @Test
    fun renamedEpisodesUseTheSavedIndexAndMissingEpisodesDoNotResume() {
        val episodes = listOf(Episode("01", ""), Episode("02", ""))
        assertEquals(1_800_000L, saved.resumePositionFor(episodes, 1))
        assertEquals(0L, saved.resumePositionFor(episodes.take(1), 0))
    }
}
