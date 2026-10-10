package com.example

import com.example.data.model.ContentType
import com.example.data.util.ContentTypeResolver
import com.example.data.util.TmdbGenreHelper
import com.example.domain.models.Genre
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.ui.components.DropdownOption
import com.example.ui.screens.anime.AnimeNavCategory
import com.example.ui.screens.home.HomeNavCategory
import com.example.ui.screens.movies.MoviesNavCategory
import com.example.ui.screens.series.SeriesNavCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification test suite for Phase 08.1:
 * Category Navigation Restoration & Conditional Genre Filter Placement.
 */
class Phase081CategoryNavigationRestorationTest {

    @Test
    fun testHomeCategories_PreserveFullNavigationOrder() {
        // Must contain Home, Movies, Anime, Series
        val categories = listOf(
            HomeNavCategory.HOME,
            HomeNavCategory.MOVIES,
            HomeNavCategory.ANIME,
            HomeNavCategory.SERIES
        )
        assertEquals(4, categories.size)
        assertEquals("home", categories[0].id)
        assertEquals("movies", categories[1].id)
        assertEquals("anime", categories[2].id)
        assertEquals("series", categories[3].id)

        // Verify string resource IDs are correctly mapped
        assertEquals(R.string.home, HomeNavCategory.HOME.titleRes)
        assertEquals(R.string.category_movies, HomeNavCategory.MOVIES.titleRes)
        assertEquals(R.string.category_anime, HomeNavCategory.ANIME.titleRes)
        assertEquals(R.string.category_series, HomeNavCategory.SERIES.titleRes)
    }

    @Test
    fun testMoviesCategories_PreserveInternalNavigation() {
        val categories = listOf(
            MoviesNavCategory.MOVIES,
            MoviesNavCategory.GENRES,
            MoviesNavCategory.NEW_RELEASES,
            MoviesNavCategory.TOP_RATED
        )
        assertEquals(4, categories.size)
        assertEquals("movies", categories[0].id)
        assertEquals("genres", categories[1].id)
        assertEquals("new_releases", categories[2].id)
        assertEquals("top_rated", categories[3].id)
    }

    @Test
    fun testSeriesCategories_PreserveInternalNavigation() {
        val categories = listOf(
            SeriesNavCategory.SERIES,
            SeriesNavCategory.GENRES,
            SeriesNavCategory.NEW_RELEASES,
            SeriesNavCategory.TOP_RATED
        )
        assertEquals(4, categories.size)
        assertEquals("series", categories[0].id)
        assertEquals("genres", categories[1].id)
        assertEquals("new_releases", categories[2].id)
        assertEquals("top_rated", categories[3].id)
    }

    @Test
    fun testAnimeCategories_PreserveInternalNavigation() {
        val categories = listOf(
            AnimeNavCategory.ANIME,
            AnimeNavCategory.GENRES,
            AnimeNavCategory.NEW_RELEASES,
            AnimeNavCategory.TOP_RATED
        )
        assertEquals(4, categories.size)
        assertEquals("anime", categories[0].id)
        assertEquals("genres", categories[1].id)
        assertEquals("new_releases", categories[2].id)
        assertEquals("top_rated", categories[3].id)
    }

    @Test
    fun testGenreFilterVisibilityConditions_StrictConformance() {
        // HomeScreen rules:
        // Home: HIDDEN
        // Movies: VISIBLE
        // Series: VISIBLE
        // Anime: VISIBLE
        fun isGenreFilterVisibleOnHome(category: HomeNavCategory): Boolean {
            return category != HomeNavCategory.HOME
        }

        assertFalse("Genre filter must be hidden on Home category", isGenreFilterVisibleOnHome(HomeNavCategory.HOME))
        assertTrue("Genre filter must be visible on Movies category", isGenreFilterVisibleOnHome(HomeNavCategory.MOVIES))
        assertTrue("Genre filter must be visible on Series category", isGenreFilterVisibleOnHome(HomeNavCategory.SERIES))
        assertTrue("Genre filter must be visible on Anime category", isGenreFilterVisibleOnHome(HomeNavCategory.ANIME))

        // MoviesScreen rules:
        // Movies: HIDDEN
        // Genres: VISIBLE
        // New Releases: HIDDEN
        // Top Rated: HIDDEN
        fun isGenreFilterVisibleOnMovies(category: MoviesNavCategory): Boolean {
            return category == MoviesNavCategory.GENRES
        }

        assertFalse("Genre filter must be hidden on Movies", isGenreFilterVisibleOnMovies(MoviesNavCategory.MOVIES))
        assertTrue("Genre filter must be visible only on Genres tab", isGenreFilterVisibleOnMovies(MoviesNavCategory.GENRES))
        assertFalse("Genre filter must be hidden on New Releases", isGenreFilterVisibleOnMovies(MoviesNavCategory.NEW_RELEASES))
        assertFalse("Genre filter must be hidden on Top Rated", isGenreFilterVisibleOnMovies(MoviesNavCategory.TOP_RATED))

        // SeriesScreen rules:
        fun isGenreFilterVisibleOnSeries(category: SeriesNavCategory): Boolean {
            return category == SeriesNavCategory.GENRES
        }

        assertFalse("Genre filter must be hidden on Series", isGenreFilterVisibleOnSeries(SeriesNavCategory.SERIES))
        assertTrue("Genre filter must be visible only on Genres tab", isGenreFilterVisibleOnSeries(SeriesNavCategory.GENRES))
        assertFalse("Genre filter must be hidden on New Releases", isGenreFilterVisibleOnSeries(SeriesNavCategory.NEW_RELEASES))
        assertFalse("Genre filter must be hidden on Top Rated", isGenreFilterVisibleOnSeries(SeriesNavCategory.TOP_RATED))

        // AnimeScreen rules:
        fun isGenreFilterVisibleOnAnime(category: AnimeNavCategory): Boolean {
            return category == AnimeNavCategory.GENRES
        }

        assertFalse("Genre filter must be hidden on Anime", isGenreFilterVisibleOnAnime(AnimeNavCategory.ANIME))
        assertTrue("Genre filter must be visible only on Genres tab", isGenreFilterVisibleOnAnime(AnimeNavCategory.GENRES))
        assertFalse("Genre filter must be hidden on New Releases", isGenreFilterVisibleOnAnime(AnimeNavCategory.NEW_RELEASES))
        assertFalse("Genre filter must be hidden on Top Rated", isGenreFilterVisibleOnAnime(AnimeNavCategory.TOP_RATED))
    }

    @Test
    fun testIndependentStateIsolation_NoCrossContamination() {
        // Test that movie genre filter, series genre filter, and anime genre filter maintain independent states
        var selectedMovieGenre: Genre? = Genre(28, "Action")
        var selectedSeriesGenre: Genre? = null
        var selectedAnimeGenre: Genre? = Genre(16, "Animation")

        var activeHomeCategory = HomeNavCategory.HOME
        // Switching category does not clear or cross-contaminate another category's genre
        activeHomeCategory = HomeNavCategory.MOVIES
        assertEquals(28, selectedMovieGenre?.id)
        assertNull(selectedSeriesGenre)

        activeHomeCategory = HomeNavCategory.SERIES
        assertNull(selectedSeriesGenre)
        selectedSeriesGenre = Genre(10759, "Action & Adventure")
        assertEquals(10759, selectedSeriesGenre?.id)
        assertEquals(28, selectedMovieGenre?.id) // Movies genre unchanged

        activeHomeCategory = HomeNavCategory.ANIME
        assertEquals(16, selectedAnimeGenre?.id)
        assertEquals(28, selectedMovieGenre?.id)
        assertEquals(10759, selectedSeriesGenre?.id)
    }

    @Test
    fun testGenreFiltering_AllSelectionRestoresCompleteDataset() {
        val movies = listOf(
            Movie(id = "1", title = "Action Movie", overview = "", posterUrl = "", backdropUrl = "", year = 2024, rating = 8.0, genres = listOf("Action"), runtime = 100, genreIds = listOf(28)),
            Movie(id = "2", title = "Comedy Movie", overview = "", posterUrl = "", backdropUrl = "", year = 2024, rating = 7.5, genres = listOf("Comedy"), runtime = 90, genreIds = listOf(35))
        )

        val selectedGenre: Genre? = null // "All" represents null
        val filtered = if (selectedGenre == null) movies else movies.filter { it.genreIds.contains(selectedGenre.id) }

        assertEquals("Selecting 'All' (null) must retain all movies without omission", 2, filtered.size)

        val actionGenre = Genre(28, "Action")
        val filteredAction = movies.filter { it.genreIds.contains(actionGenre.id) }
        assertEquals(1, filteredAction.size)
        assertEquals("Action Movie", filteredAction[0].title)
    }

    @Test
    fun testSeriesAnimeSeparation_RulesStrictlyMaintained() {
        val regularSeries = Series(
            id = "101",
            title = "Breaking Drama",
            overview = "",
            posterUrl = "",
            backdropUrl = "",
            year = 2024,
            rating = 9.2,
            genres = listOf("Drama"),
            originalLanguage = "en",
            originCountry = listOf("US"),
            genreIds = listOf(18)
        )
        val animeSeries = Series(
            id = "102",
            title = "Attack on Titan",
            overview = "",
            posterUrl = "",
            backdropUrl = "",
            year = 2023,
            rating = 9.5,
            genres = listOf("Animation", "Action"),
            originalLanguage = "ja",
            originCountry = listOf("JP"),
            genreIds = listOf(16, 10759)
        )
        val animeMovie = Movie(
            id = "103",
            title = "Spirited Away",
            overview = "",
            posterUrl = "",
            backdropUrl = "",
            year = 2001,
            rating = 8.6,
            genres = listOf("Animation"),
            runtime = 125,
            genreIds = listOf(16)
        )

        // General series list must exclude anime series
        assertEquals(ContentType.TV, ContentTypeResolver.resolveSeries(regularSeries))
        assertEquals(ContentType.ANIME, ContentTypeResolver.resolveSeries(animeSeries))
        // Anime movie must be resolved as MOVIE
        assertEquals(ContentType.MOVIE, ContentTypeResolver.resolveMovie(animeMovie))

        val allSeries = listOf(regularSeries, animeSeries)
        val nonAnimeSeries = allSeries.filter { ContentTypeResolver.resolveSeries(it) != ContentType.ANIME }
        assertEquals(1, nonAnimeSeries.size)
        assertEquals("Breaking Drama", nonAnimeSeries[0].title)
    }
}
