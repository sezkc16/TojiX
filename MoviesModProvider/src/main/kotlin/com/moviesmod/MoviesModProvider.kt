package com.moviesmod

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
 * MoviesMod (moviesmod.fi) — WordPress / SociallyViral download site:
 * dual/multi-audio Hindi-English movies & TV series.
 *
 * How a title plays, end to end (traced live 2026-11-19, evidence in
 * ~/workspace/tojix-uhd-research/moviesmod-trace/):
 *  1. The post page lists one modpro.blog link per quality (movies:
 *     "Download Links" buttons; series: "Episode Links" buttons, one
 *     per (season, quality) bundle — the Batch/Zip buttons are skipped).
 *  2. The modpro page (links.modpro.blog / episodes.modpro.blog) hides
 *     its thenaukriadda.in ?sid= links behind a 5 s countdown; the
 *     links are in the DOM, so they are read straight off the page.
 *     Movies get two server buttons; series pages list one link per
 *     "Episode N".
 *  3. [bypassHrefli] walks the thenaukriadda gate: an auto-submit
 *     _lp_http form, then a second form (_lp_http2 / _lp_token /
 *     _lp_chain / _lp_hop_index) to a decoy article page, then the
 *     ?lp_go=<token> step (cookie planted by sc("lp-...", "...")),
 *     and the reply carries window.location.replace() to a
 *     driveleech / driveseed landing page or a video-seed.xyz
 *     ?url=<token> link.
 *  4. [loadExtractor] routes that link to the Driveseed / Driveleech /
 *     UHDMovies extractors, which unlock the file URL (the video-seed
 *     /api POST is the main playable path).
 *
 * Same chain as the UHDMovies plugin (same gate domain, same landing
 * hosts), one extra hop (the modpro.blog page) and one extra form
 * round inside the gate. The extractors are the same ones.
 *
 * Metadata is enriched from TMDB (ids) + Cinemeta (plot / rating /
 * poster / episode lists). The TMDB key is the one already used by the
 * Cinejoy / UHDMovies plugins in this repo, verified live against
 * TMDB — never the key hardcoded in the reference implementation.
 */
class MoviesModProvider : MainAPI() {

    override var mainUrl = "https://moviesmod.fi"
    override var name = "MoviesMod"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbBase = "https://api.themoviedb.org/3"
    // Same key as the Cinejoy / UHDMovies plugins (verified live vs TMDB 2026-11-19).
    private val tmdbKey = "8476a7ab80ad76f0936744df0430e67c"

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    )

    // No verified fallback domain exists: Phisher's domains.json and
    // mmodlist.org both list only moviesmod.fi. Do NOT invent one
    // (.net / .org / .in are unverified).
    private val candidateDomains = listOf(
        "https://moviesmod.fi"
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
        "movies/hollywood/" to "Hollywood",
        "dual-audio/" to "Dual Audio",
        "multi-audio/" to "Multi Audio",
        "imdb-top-movies/" to "IMDb Top",
        "quality/480p/" to "480p",
        "quality/1080p/" to "1080p"
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
        val items = doc.select("article.latestPost.excerpt")
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
        // The search endpoint answers 302 first; the app client
        // follows redirects to the results page.
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = try {
            cfKiller("$mainUrl?s=$encoded ").document
        } catch (_: Exception) {
            return null
        }
        val items = doc.select("article.latestPost.excerpt")
            .mapNotNull { toSearchResult(it) }
        return items.toNewSearchResponseList()
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleRaw = element.select("h2.title a").text()
            .trim().removePrefix("Download ")
        if (titleRaw.isBlank()) return null
        val title = titleRaw.substringBefore("(")
            .substringBefore("Season").substringBefore("S0").trim()
            .takeIf { it.isNotBlank() } ?: titleRaw
        val hrefRaw = element.select("a.post-image").attr("href")
            .takeIf { it.isNotBlank() }
            ?: element.select("h2.title a").attr("href")
                .takeIf { it.isNotBlank() }
            ?: return null
        val href = fixUrlWithBase(hrefRaw, mainUrl)
        val img = element.selectFirst("img.wp-post-image")
        val posterRaw = img?.attr("src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
        val poster = posterRaw?.let { fixUrlWithBase(it, mainUrl) }
        val quality = getSearchQuality(titleRaw)
        val isSeries = Regex("(?i)\\b(?:season|seasons|series|s\\d+|episodes?)\\b")
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
        val rawTitle = doc.select("h1.title.single-title.entry-title")
            .text().trim().takeIf { it.isNotBlank() }
            ?: doc.select("h1.entry-title").text().trim()
                .takeIf { it.isNotBlank() }
            ?: return null
        val cleanTitle = rawTitle.removePrefix("Download ").trim()
        val title = cleanTitle.substringBefore("(")
            .substringBefore("Season").substringBefore("S0").trim()
            .takeIf { it.isNotBlank() } ?: cleanTitle
        val poster = doc.select("meta[property=og:image]")
            .attr("content").takeIf { it.isNotBlank() }
            ?: doc.selectFirst("article.article img.wp-post-image")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?.takeIf { it.isNotBlank() }
        val collectionPoster = poster
        val year = Regex("(?<=\\()[\\d(\\]]+(?!=\\))")
            .find(rawTitle)?.value?.toIntOrNull()
        val tags = doc.select("div.thecategory a").eachText()
        val contentEl = doc.selectFirst("div.entry-content")
        val hasEpisodeButtons = contentEl
            ?.select("a.maxbutton-episode-links")
            ?.isNotEmpty() == true
        val isSeries = hasEpisodeButtons ||
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

        // Series: one (season, quality) modpro page per "Episode Links"
        // button — each page lists its own "Episode N" links. Fetch
        // the pages in parallel and group by (season, episode).
        val scope = contentEl ?: doc
        val bundles = collectBundles(scope)
        val expanded = bundles.amap { bundle ->
            val links = try {
                expandEpisodePage(bundle.url)
            } catch (_: Exception) {
                emptyList()
            }
            bundle to links
        }
        val episodesByKey =
            linkedMapOf<Pair<Int, Int>, MutableList<ModLinks>>()
        for ((bundle, pageLinks) in expanded) {
            for ((epNum, sid) in pageLinks) {
                episodesByKey.getOrPut(bundle.season to epNum) {
                    mutableListOf()
                }.add(ModLinks(bundle.label, sid))
            }
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
            newEpisode(hrefs) {
                this.name = epName
                this.season = season
                this.episode = episode
                this.posterUrl = epThumb
                this.description = epDesc
                this.runTime = epRuntime
                this.addDate(epAired)
            }
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(
                title, url, TvType.TvSeries, episodes
            ) {
                applyMeta(
                    poster, collectionPoster, logo, year, tags,
                    background, description, imdbRating
                )
            }
        }

        // Fallback: no modpro page yielded per-episode links (layout
        // change or fetch failure). One entry per (season, quality)
        // bundle, named by its label; loadLinks expands the modpro
        // page and resolves every episode link on it.
        val fallbackEpisodes = bundles.mapIndexed { index, bundle ->
            newEpisode(listOf(ModLinks(bundle.label, bundle.url))) {
                this.name = bundle.label
                this.season = bundle.season
                this.episode = index + 1
            }
        }
        if (fallbackEpisodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(
                title, url, TvType.TvSeries, fallbackEpisodes
            ) {
                applyMeta(
                    poster, collectionPoster, logo, year, tags,
                    background, description, imdbRating
                )
            }
        }
        return movieResponse(
            doc, title, url, poster, collectionPoster, logo,
            year, tags, background, description, imdbRating
        )
    }

    /**
     * One "Episode Links" button per (season, quality) bundle, in page
     * order. The "Batch/Zip File" buttons are skipped. Label = the
     * preceding h3/h4 text, e.g. "Season 1 {Hindi-English} 480p x264
     * Msubs [220MB]"; season is tracked from the headings as they
     * appear, so a bundle whose own label carries no season still
     * lands in the right one.
     */
    private fun collectBundles(scope: Element): List<Bundle> {
        val bundles = mutableListOf<Bundle>()
        var currentSeason = 1
        for (el in scope.select("h1, h2, h3, h4, h5, pre, p, a")) {
            if (el.tagName() != "a") {
                Regex("(?i)(?:season\\s*|S)(\\d+)").find(el.text())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                        currentSeason = it
                    }
            }
            if (el.tagName() != "a" ||
                !el.attr("class").contains("maxbutton-episode-links")
            ) continue
            val href = el.attr("href")
            if (href.isBlank()) continue
            var labelEl = el.parent()
            while (labelEl != null && labelEl.tagName() != "p") {
                labelEl = labelEl.parent()
            }
            var sibling = labelEl?.previousElementSibling()
            while (sibling != null && sibling.text().isBlank()) {
                sibling = sibling.previousElementSibling()
            }
            val label = sibling?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: "Season $currentSeason"
            val season = Regex("(?i)(?:season\\s*|S)(\\d+)")
                .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: currentSeason
            bundles.add(Bundle(season, label, href))
        }
        return bundles
    }

    /** Fetch an episodes.modpro.blog page and return its
     *  (episode, thenaukriadda ?sid= link) pairs. The page also
     *  carries two server buttons and a comment link — only links
     *  whose own text is "Episode N" count. */
    private suspend fun expandEpisodePage(
        url: String
    ): List<Pair<Int, String>> {
        val doc = cfKiller(url).document
        return doc.select("a[href*=\"thenaukriadda.in/?sid=\"]")
            .mapNotNull { a ->
                val epNum = Regex("(?i)Episode\\s*(\\d+)")
                    .find(a.text())?.groupValues?.getOrNull(1)
                    ?.toIntOrNull() ?: return@mapNotNull null
                val href = a.attr("href")
                if (href.isBlank()) return@mapNotNull null
                epNum to href
            }.distinctBy { it.second }
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
        val scope = doc.selectFirst("div.entry-content") ?: doc
        val data = scope.select("a.maxbutton-download-links")
            .mapNotNull { btn ->
                val link = btn.attr("href")
                if (link.isBlank()) return@mapNotNull null
                // Label = text of the nearest preceding non-blank
                // sibling of the enclosing <p> (e.g. "Download The
                // Odyssey (2026) {English-Hindi} 480p x264 [570MB]").
                var labelEl = btn.parent()
                while (labelEl != null && labelEl.tagName() != "p") {
                    labelEl = labelEl.parent()
                }
                var sibling = labelEl?.previousElementSibling()
                while (sibling != null && sibling.text().isBlank()) {
                    sibling = sibling.previousElementSibling()
                }
                val label = sibling?.text()?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "MoviesMod"
                ModLinks(label, link)
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
                ModLinks(
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
     * One gate/modpro link -> playable link(s). A modpro.blog page is
     * expanded first (its server buttons, or its per-episode links),
     * then each thenaukriadda link goes through the gate. Direct
     * landing-page links go straight to the extractor.
     */
    private suspend fun resolveSingleLink(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isDirectLink(link)) {
            return loadExtractor(link, subtitleCallback, callback)
        }
        if (link.contains("modpro.blog")) {
            val sids = try {
                expandModpro(link)
            } catch (_: Exception) {
                emptyList()
            }
            if (sids.isEmpty()) {
                return resolveGate(link, subtitleCallback, callback)
            }
            sids.amap { sid ->
                try {
                    resolveGate(sid, subtitleCallback, callback)
                } catch (_: Exception) {
                }
            }
            return true
        }
        return resolveGate(link, subtitleCallback, callback)
    }

    /**
     * One gate link -> playable link. The gate walk is deterministic
     * (form -> form -> lp_go -> replace), so a single pass is enough;
     * the second pass only runs when the first comes back empty. (The
     * UHDMovies provider re-runs the bypass unconditionally and uses
     * the second result; here the first non-null result wins.)
     */
    private suspend fun resolveGate(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // v4: same retry as UHDMovies — a transient gate stall must not
        // kill the link on the first null walk.
        var first = try {
            bypassHrefli(link)
        } catch (_: Exception) {
            null
        }
        if (first == null) {
            // No kotlinx delay in providers by repo rule — a short block.
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
            }
            first = try {
                bypassHrefli(link)
            } catch (_: Exception) {
                null
            }
        }
        if (first != null) {
            return loadExtractor(first, subtitleCallback, callback)
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

    /**
     * A links.modpro.blog page carries two server buttons (Fast Server
     * / Google Drive Server 2); an episodes.modpro.blog page carries
     * one link per episode. The host tells them apart — and the
     * comment link (a plain <a> to ?sid=, no maxbutton class, text
     * "our comment section") is excluded on both.
     */
    private suspend fun expandModpro(url: String): List<String> {
        val doc = cfKiller(url).document
        return if (url.contains("episodes.modpro.blog")) {
            doc.select("a[href*=\"thenaukriadda.in/?sid=\"]")
                .mapNotNull { a ->
                    if (!Regex("(?i)Episode\\s*\\d+")
                            .containsMatchIn(a.text())
                    ) return@mapNotNull null
                    a.attr("href").takeIf { it.isNotBlank() }
                }.distinct()
        } else {
            doc.select("a.maxbutton[href*=\"thenaukriadda.in/?sid=\"]")
                .mapNotNull { a ->
                    a.attr("href").takeIf { it.isNotBlank() }
                }.distinct()
        }
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

/** One (season, quality) bundle on a series post page: its label
 *  ("Season 1 {Hindi-English} 480p x264 Msubs [220MB]") and its
 *  episodes.modpro.blog page. */
private data class Bundle(
    val season: Int,
    val label: String,
    val url: String
)

/**
 * One download button on a post page, or one episode link on a modpro
 * page: its label (e.g. "Download The Odyssey (2026) {English-Hindi}
 * 480p x264 [570MB]", or "Season 1 480p x264") and its gate link.
 * Serialised as the movie / episode data that
 * [MoviesModProvider.loadLinks] parses back.
 */
data class ModLinks(
    @JsonProperty("sourceName") val sourceName: String,
    @JsonProperty("sourceLink") val sourceLink: String
)
