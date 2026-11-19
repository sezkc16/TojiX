package com.uhdmovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * UHDMovies (uhdmovies.ceo) — WordPress / GridLove download site:
 * 4K / 1080p dual-audio Hindi-English movies & TV series.
 *
 * How a title plays, end to end:
 *  1. The post page lists "Download (G-Drive)" MaxButtons. Each button
 *     points at a short-link gate (thenaukriadda.in and friends).
 *  2. [bypassHrefli] walks the gate (auto-submit forms, lp_go / ?go=
 *     tokens) and returns a driveleech / driveseed landing page or a
 *     video-seed.xyz/?url=<token> link.
 *  3. [loadExtractor] routes that link to the Driveseed / Driveleech /
 *     UHDMovies extractors, which unlock the file URL (the video-seed
 *     /api POST is the main playable path).
 *
 * Metadata is enriched from TMDB (ids) + Cinemeta (plot / rating /
 * poster / episode lists). The TMDB key is the one already used by the
 * Cinejoy plugin in this repo, verified live against TMDB — never the
 * key hardcoded in the reference implementation.
 */
class UHDMoviesProvider : MainAPI() {

    override var mainUrl = "https://uhdmovies.ceo"
    override var name = "UHDMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbBase = "https://api.themoviedb.org/3"
    // Same key as the Cinejoy plugin (verified live vs TMDB 2026-11-19).
    private val tmdbKey = "8476a7ab80ad76f0936744df0430e67c"

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    )

    // The site's main domain is .ceo; .my is the known fallback.
    private val candidateDomains = listOf(
        "https://uhdmovies.ceo",
        "https://uhdmovies.my"
    )
    private var cachedDomain: String = mainUrl
    private var lastResolvedTime: Long = 0L
    private val domainCacheTtlMs: Long = 1000L * 60 * 60 * 3 // 3 hours

    private suspend fun resolveDomain(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && lastResolvedTime > 0 &&
            (now - lastResolvedTime) < domainCacheTtlMs
        ) {
            mainUrl = cachedDomain
            return cachedDomain
        }
        val candidates = listOf(cachedDomain) +
            candidateDomains.filter { it != cachedDomain }
        for (candidate in candidates) {
            try {
                val res = app.get(candidate, timeout = 4)
                if (res.isSuccessful || res.code in 200..399) {
                    cachedDomain = candidate
                    lastResolvedTime = now
                    mainUrl = candidate
                    return candidate
                }
            } catch (_: Exception) {
            }
        }
        mainUrl = cachedDomain
        return cachedDomain
    }

    /**
     * GET with browser headers; if Cloudflare answers with its
     * "Just a moment" challenge page, retry once. (The reference
     * retries with a CloudflareKiller interceptor; that class is not
     * referenced here because its presence in the plugin lib could
     * not be verified, and a bad import would break the build for
     * every plugin in the repo. The site currently serves real pages
     * to plain browser-UA requests, so this is a fallback only.)
     */
    private suspend fun cfKiller(url: String): NiceResponse {
        val first = app.get(url, headers = browserHeaders)
        if (first.document.select("title").text() != "Just a moment") {
            return first
        }
        return app.get(url, headers = browserHeaders)
    }

    override val mainPage = mainPageOf(
        "" to "Home",
        "movies/" to "Movies",
        "tv-series/" to "TV Series",
        "movies/collection-movies/" to "Hollywood",
        "web-series/" to "Web Series",
        "tv-shows/netflix/" to "Netflix",
        "amazon-prime/" to "Amazon Prime",
        "4k-hdr/" to "4K HDR",
        "imax/" to "IMAX"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        resolveDomain()
        val url = if (page != 1) {
            val base = "$mainUrl/${request.data.trimEnd('/')}".trimEnd('/')
            "$base/page/$page/"
        } else {
            "$mainUrl/${request.data}"
        }
        val doc = try {
            cfKiller(url).document
        } catch (_: Exception) {
            return newHomePageResponse(
                HomePageList(request.name, emptyList()), hasNext = false
            )
        }
        val items = doc.select("article.gridlove-post")
            .mapNotNull { toSearchResult(it) }
        return newHomePageResponse(
            HomePageList(request.name, items), hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {
        resolveDomain()
        // Note the trailing space after the query, as the site expects.
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            cfKiller("$mainUrl?s=$encoded ").document
        } catch (_: Exception) {
            return null
        }
        val items = doc.select("article.gridlove-post")
            .mapNotNull { toSearchResult(it) }
        return items.toNewSearchResponseList()
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleRaw = element.select("h1.sanket").text()
            .trim().removePrefix("Download ")
        if (titleRaw.isBlank()) return null
        val title = Regex("(^.*\\)\\d*)").find(titleRaw)
            ?.groupValues?.getOrNull(1) ?: titleRaw
        val href = element.select("div.entry-image > a").attr("href")
            .takeIf { it.isNotBlank() }?.let { fixUrlWithBase(it, mainUrl) }
            ?: return null
        val img = element.selectFirst("div.entry-image > a > img")
        val posterRaw = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        val poster = posterRaw?.let { fixUrlWithBase(it, mainUrl) }
        val quality = getSearchQuality(titleRaw)
        val classAttr = element.attr("class")
        val isSeries = Regex(
            "(?i)\\b(?:category-)?(?:tv-series|tv-shows|web-series)\\b"
        ).containsMatchIn(classAttr) ||
            Regex("(?i)\\b(?:season|seasons|series|s\\d+|episodes?)\\b")
                .containsMatchIn(titleRaw)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveDomain()
        val doc = try {
            cfKiller(url).document
        } catch (_: Exception) {
            return null
        }
        val rawTitle = doc.select(
            "div.gridlove-content div.entry-header h1.entry-title"
        ).text().trim().removePrefix("Download ")
        if (rawTitle.isBlank()) return null
        val title = rawTitle.substringBefore("(")
            .substringBefore("Season").substringBefore("S0").trim()
        val poster = doc.selectFirst("div.entry-content > p img")
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            ?.takeIf { it.isNotBlank() }
        val collectionPoster = doc.select("meta[property=og:image]")
            .attr("content").takeIf { it.isNotBlank() }
        val year = Regex("(?<=\\()[\\d(\\]]+(?!=\\))")
            .find(rawTitle)?.value?.toIntOrNull()
        val tags = doc.select(
            "div.entry-category > a.gridlove-cat, div.entry-category a"
        ).eachText()
        val contentEl = doc.selectFirst("div.entry-content")
        val isSeries = (
            contentEl?.select("a")?.any {
                it.text().contains("Episode", ignoreCase = true)
            } == true
            ) ||
            tags.any {
                Regex(
                    "(?i)\\b(?:tv[-\\s]?series|tv[-\\s]?shows|web[-\\s]?series|series)\\b"
                ).containsMatchIn(it)
            } ||
            Regex("(?i)\\b(?:season|seasons|series|s\\d+|episodes?)\\b")
                .containsMatchIn(rawTitle)

        val ids = fetchIds(title, year, isSeries)
        val meta = ids.imdbId?.takeIf { it.isNotBlank() }
            ?.let { fetchMetaData(it, isSeries) }
        val background = meta?.optString("background")
            ?.takeIf { it.isNotBlank() } ?: poster
        val description = meta?.optString("description")
            ?.takeIf { it.isNotBlank() }
        val imdbRating = meta?.optString("imdbRating")
            ?.takeIf { it.isNotBlank() }
        val logo = meta?.optString("logo")?.takeIf { it.isNotBlank() }

        if (!isSeries) {
            return movieResponse(
                doc, title, url, poster, collectionPoster, logo,
                year, tags, background, description, imdbRating
            )
        }

        // Series: scan for "Episode N" links, grouped by (season, episode).
        val episodesByKey = linkedMapOf<Pair<Int, Int>, MutableList<String>>()
        var currentSeason = 1
        val scope = contentEl ?: doc
        for (el in scope.select("h1, h2, h3, h4, h5, pre, p, a")) {
            if (el.tagName() != "a") {
                Regex("(?i)(?:season\\s*|S)(\\d+)").find(el.text())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                        currentSeason = it
                    }
            }
            if (el.tagName() != "a" ||
                !el.text().contains("Episode", ignoreCase = true)
            ) continue
            if (el.text().contains("Zip", ignoreCase = true)) continue
            val epNum = Regex("(?i)Episode\\s*(\\d+)").find(el.text())
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            val href = el.attr("href")
            if (href.isBlank()) continue
            val season = Regex("(?i)(?:season\\s*|S)(\\d+)\\s*(?:episode|ep|E)")
                .find(el.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: currentSeason
            episodesByKey.getOrPut(season to epNum) { mutableListOf() }
                .add(href)
        }

        val videos = meta?.optJSONArray("videos")
        val episodes = episodesByKey.map { (key, hrefs) ->
            val (season, episode) = key
            var video: JSONObject? = null
            if (videos != null) {
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    if (v.optInt("season") == season &&
                        v.optInt("episode") == episode
                    ) {
                        video = v
                        break
                    }
                }
            }
            val epName = video?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?: video?.optString("title")?.takeIf { it.isNotBlank() }
                ?: "Episode $episode"
            val epDesc = video?.optString("overview")
                ?.takeIf { it.isNotBlank() }
                ?: video?.optString("description")?.takeIf { it.isNotBlank() }
            val epThumb = video?.optString("thumbnail")
                ?.takeIf { it.isNotBlank() }
            val epAired = video?.optString("firstAired")
                ?.takeIf { it.isNotBlank() }
                ?: video?.optString("released")?.takeIf { it.isNotBlank() }
            val epRuntime = video?.optString("runtime")
                ?.filter { it.isDigit() }?.toIntOrNull()
            val epData = hrefs.map { UHDLinks("UHD", it) }
            newEpisode(epData) {
                this.name = epName
                this.season = season
                this.episode = episode
                this.posterUrl = epThumb
                this.description = epDesc
                this.runTime = epRuntime
                this.addDate(epAired)
            }
        }

        if (episodes.isEmpty()) {
            return movieResponse(
                doc, title, url, poster, collectionPoster, logo,
                year, tags, background, description, imdbRating
            )
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            applyMeta(
                poster, collectionPoster, logo, year, tags,
                background, description, imdbRating
            )
        }
    }

    private suspend fun movieResponse(
        doc: Document,
        title: String,
        url: String,
        poster: String?,
        collectionPoster: String?,
        logo: String?,
        year: Int?,
        tags: List<String>,
        background: String?,
        description: String?,
        imdbRating: String?
    ): LoadResponse {
        val data = doc.select(
            "div.entry-content a.maxbutton-1, div.entry-content a.maxbutton"
        ).mapNotNull { btn ->
            val link = btn.attr("href")
            if (link.isBlank()) return@mapNotNull null
            // Label = text of the nearest preceding non-blank sibling
            // of the enclosing <p> (e.g. "Download [1080p] 10Bit HEVC").
            var labelEl = btn.parent()
            while (labelEl != null && labelEl.tagName() != "p") {
                labelEl = labelEl.parent()
            }
            var sibling = labelEl?.previousElementSibling()
            while (sibling != null && sibling.text().isBlank()) {
                sibling = sibling.previousElementSibling()
            }
            val label = sibling?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: "UHD"
            UHDLinks(label, link)
        }
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            applyMeta(
                poster, collectionPoster, logo, year, tags,
                background, description, imdbRating
            )
        }
    }

    /** Shared details-page metadata (poster / year / tags / backdrop /
     *  plot / score) for movies and series. Note: this lib version does
     *  not expose LoadResponse.addTrailer / addImdbId / addTMDbId (no
     *  provider in this repo sets a trailer or tracker ids), so the
     *  trailer and the TMDB/IMDb ids are fetched for enrichment only
     *  and not attached to the response. */
    private suspend fun LoadResponse.applyMeta(
        poster: String?,
        collectionPoster: String?,
        logo: String?,
        year: Int?,
        tags: List<String>,
        background: String?,
        description: String?,
        imdbRating: String?
    ) {
        this.posterUrl = poster?.takeIf { it.isNotBlank() }
            ?: collectionPoster?.takeIf { it.isNotBlank() }
        try {
            this.logoUrl = logo
        } catch (_: Throwable) {
        }
        this.year = year
        this.tags = tags
        this.backgroundPosterUrl = background?.takeIf { it.isNotBlank() }
            ?: collectionPoster?.takeIf { it.isNotBlank() }
        this.plot = description
        imdbRating?.toDoubleOrNull()?.let { this.score = Score.from10(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http")) {
            return resolveSingleLink(data, subtitleCallback, callback)
        }
        val links = try {
            val arr = JSONArray(data)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                UHDLinks(
                    obj.optString("sourceName"),
                    obj.optString("sourceLink")
                )
            }.filter { it.sourceLink.isNotBlank() }
        } catch (_: Exception) {
            return false
        }
        links.amap { link ->
            try {
                resolveSingleLink(link.sourceLink, subtitleCallback, callback)
            } catch (_: Exception) {
            }
        }
        return true
    }

    /**
     * One gate link -> playable link. Direct landing-page links go
     * straight to the extractor; anything else goes through the gate
     * bypass twice (the gate sometimes needs a beat between passes),
     * then to the extractor.
     */
    private suspend fun resolveSingleLink(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isDirectLink(link)) {
            return loadExtractor(link, subtitleCallback, callback)
        }
        val first = try {
            bypassHrefli(link)
        } catch (_: Exception) {
            null
        }
        if (first == null) {
            return loadExtractor(link, subtitleCallback, callback)
        }
        // No kotlinx delay in providers by repo rule — a short block.
        try {
            Thread.sleep(1500)
        } catch (_: InterruptedException) {
        }
        val second = try {
            bypassHrefli(link)
        } catch (_: Exception) {
            null
        }
        return loadExtractor(second ?: link, subtitleCallback, callback)
    }

    private fun isDirectLink(link: String): Boolean {
        val lower = link.lowercase()
        return lower.contains("driveleech") ||
            lower.contains("driveseed") ||
            lower.contains("video-seed") ||
            lower.contains("video-leech")
    }

    private data class IdResult(val tmdbId: Int?, val imdbId: String?)

    /** TMDB search -> first hit -> external_ids -> IMDb id. */
    private suspend fun fetchIds(
        title: String,
        year: Int?,
        isSeries: Boolean
    ): IdResult {
        return try {
            val type = if (isSeries) "tv" else "movie"
            val encoded = URLEncoder.encode(title, "UTF-8")
            val yearParam = if (year != null) {
                if (isSeries) "&first_air_date_year=$year" else "&year=$year"
            } else {
                ""
            }
            val searchJson = JSONObject(
                app.get(
                    "$tmdbBase/search/$type?api_key=$tmdbKey&query=$encoded$yearParam"
                ).text
            )
            val tmdbId = searchJson.optJSONArray("results")
                ?.optJSONObject(0)?.optInt("id")?.takeIf { it != 0 }
                ?: return IdResult(null, null)
            val extJson = JSONObject(
                app.get(
                    "$tmdbBase/$type/$tmdbId/external_ids?api_key=$tmdbKey"
                ).text
            )
            val imdbId = extJson.optString("imdb_id")
                .takeIf { it.isNotBlank() && it != "null" }
            IdResult(tmdbId, imdbId)
        } catch (_: Exception) {
            IdResult(null, null)
        }
    }

    /** Cinemeta metadata node for an IMDb id (plot / rating / poster /
     *  logo / episode list for series). */
    private suspend fun fetchMetaData(
        imdbId: String,
        isSeries: Boolean
    ): JSONObject? {
        return try {
            val metaType = if (isSeries) "series" else "movie"
            val json = JSONObject(
                app.get(
                    "https://v3-cinemeta.strem.io/meta/$metaType/$imdbId.json"
                ).text
            )
            json.optJSONObject("meta")
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * One download button on a post page: its label (e.g. "Download [1080p]
 * 10Bit HEVC") and its gate link. Serialised as the movie / episode
 * data that [UHDMoviesProvider.loadLinks] parses back.
 */
data class UHDLinks(
    @JsonProperty("sourceName") val sourceName: String,
    @JsonProperty("sourceLink") val sourceLink: String
)
