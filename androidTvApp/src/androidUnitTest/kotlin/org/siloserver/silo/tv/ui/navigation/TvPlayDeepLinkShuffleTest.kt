package org.siloserver.silo.tv.ui.navigation

import org.siloserver.silo.model.shuffle.ShuffleScopeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvPlayDeepLinkShuffleTest {
    @Test
    fun seriesAndSeasonIdsShuffle() {
        assertEquals(ShuffleScopeKind.SERIES, tvPlayDeepLinkShuffleKind(null, "series-tvdb-353546"))
        assertEquals(ShuffleScopeKind.SEASON, tvPlayDeepLinkShuffleKind(null, "season-tvdb-353546-2"))
    }

    @Test
    fun playableTitlesStillPlay() {
        assertNull(tvPlayDeepLinkShuffleKind(null, "movie-tmdb-862"))
        assertNull(tvPlayDeepLinkShuffleKind(null, "episode-tvdb-353546-2-8"))
        assertNull(tvPlayDeepLinkShuffleKind("audiobook", "local-0123abcd"))
        // An explicit type wins over the id, as Watch Next tiles tag their links.
        assertNull(tvPlayDeepLinkShuffleKind("episode", "series-tvdb-353546"))
    }

    @Test
    fun typeCoversUnmatchedLocalItems() {
        assertEquals(ShuffleScopeKind.SERIES, tvPlayDeepLinkShuffleKind(" Series ", "local-0123abcd"))
        assertEquals(ShuffleScopeKind.SEASON, tvPlayDeepLinkShuffleKind("season", "local-0123abcd"))
        assertNull(tvPlayDeepLinkShuffleKind(null, "local-0123abcd"))
    }
}
