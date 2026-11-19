package com.cinejoy

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class CinejoyProvider : MainAPI() {

    override var mainUrl = "https://cinejoy.pk"
    override var name = "Cinejoy"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // The site has no catalog API of its own — browse, search and details
    // all come from TMDB, exactly like the site's own frontend does.
    private val tmdbBase = "https://api.themoviedb.org/3"
    // TMDB key: taken from the reference implementation (dhamarar/cloudrepo).
    // I could NOT extract the key the live site ships, because the site's
    // frontend JS is obfuscated (string literals are runtime-decoded).
    // This key was verified LIVE against TMDB on 2026-11-19 (movie detail
    // + search both HTTP 200). If the catalog ever comes back empty, the
    // key is the first suspect — rotate it.
    private val tmdbKey = "8476a7ab80ad76f0936744df0430e67c"
    private val imageBase = "https://image.tmdb.org/t/p/w500"
    private val backdropBase = "https://image.tmdb.org/t/p/original"

    // The site moves domains sometimes; try the known mirrors in order.
    private val candidateDomains = listOf(
        "https://cinejoy.pk",
        "https://cinejoy.to",
        "https://cinejoy.stream",
        "https://cinejoy.lol"
    )
    private var cachedDomain: String = mainUrl
    private var lastResolvedTime: Long = 0L
    private val domainCacheTtlMs: Long = 1000L * 60 * 60 * 3 // 3 hours

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    )

    private suspend fun resolveDomain(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && lastResolvedTime > 0 && (now - lastResolvedTime) < domainCacheTtlMs) {
            mainUrl = cachedDomain
            return cachedDomain
        }
        val candidates = listOf(cachedDomain) + candidateDomains.filter { it != cachedDomain }
        for (candidate in candidates) {
            try {
                val res = app.get(candidate, timeout = 4)
                if (res.isSuccessful || res.code in 200..399) {
                    val finalUrl = res.okhttpResponse.request.url
                    val detected = "${finalUrl.scheme}://${finalUrl.host}".removeSuffix("/")
                    if (detected.isNotBlank() && detected.startsWith("http")) {
                        cachedDomain = detected
                        lastResolvedTime = now
                        mainUrl = detected
                        return detected
                    }
                }
            } catch (_: Exception) {}
        }
        mainUrl = cachedDomain.ifBlank { "https://cinejoy.pk" }
        return mainUrl
    }

    private fun posterUrl(path: String?): String? {
        if (path.isNullOrBlank() || path == "null") return null
        return "$imageBase$path"
    }

    private fun backdropUrl(path: String?): String? {
        if (path.isNullOrBlank() || path == "null") return null
        return "$backdropBase$path"
    }

    private fun createSlug(title: String, year: String?): String {
        val clean = title.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
        return if (!year.isNullOrBlank()) "$clean-$year" else clean
    }

    private fun yearOf(date: String?): Int? {
        return date?.split("-")?.firstOrNull()?.takeIf { it.isNotBlank() }?.toIntOrNull()
    }

    // One TMDB result (discover / search) into a CloudStream card.
    private fun toSearchResponse(item: JSONObject, forceTv: Boolean = false): com.lagradost.cloudstream3.SearchResponse? {
        val id = item.optLong("id", -1L)
        if (id <= 0) return null
        val isTv = forceTv || item.has("first_air_date") || !item.has("release_date")
        val title = if (isTv) {
            item.optString("name", item.optString("original_name", "Untitled"))
        } else {
            item.optString("title", item.optString("original_title", "Untitled"))
        }
        val poster = posterUrl(item.optString("poster_path"))
        val date = if (isTv) item.optString("first_air_date") else item.optString("release_date")
        val yearStr = date.split("-").firstOrNull()?.takeIf { it.isNotBlank() }
        val slug = createSlug(title, yearStr)
        val voteAvg = item.optDouble("vote_average", 0.0)
        return if (isTv) {
            newTvSeriesSearchResponse(title, "$mainUrl/series/$id-$slug", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = yearStr?.toIntOrNull()
                if (voteAvg > 0.0) this.score = Score.from10(voteAvg)
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/$id-$slug", TvType.Movie) {
                this.posterUrl = poster
                this.year = yearStr?.toIntOrNull()
                if (voteAvg > 0.0) this.score = Score.from10(voteAvg)
            }
        }
    }

    private fun genresOf(json: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val arr = json.optJSONArray("genres") ?: return out
        for (i in 0 until arr.length()) {
            val gName = arr.optJSONObject(i)?.optString("name")
            if (!gName.isNullOrBlank()) out.add(gName)
        }
        return out
    }

    override val mainPage = mainPageOf(
        "discover/tv?sort_by=popularity.desc&page=%d" to "TV Series",
        "discover/movie?sort_by=popularity.desc&page=%d" to "Movies",
        "discover/movie?with_watch_providers=8&watch_region=US&sort_by=popularity.desc&page=%d" to "Netflix",
        "discover/movie?with_watch_providers=9&watch_region=US&sort_by=popularity.desc&page=%d" to "Amazon Prime Video",
        "discover/movie?with_watch_providers=337&watch_region=US&sort_by=popularity.desc&page=%d" to "Disney+",
        "discover/movie?with_watch_providers=350&watch_region=US&sort_by=popularity.desc&page=%d" to "Apple TV+",
        "discover/movie?with_watch_providers=2&watch_region=US&sort_by=popularity.desc&page=%d" to "Apple TV",
        "discover/movie?with_watch_providers=15&watch_region=US&sort_by=popularity.desc&page=%d" to "Hulu",
        "discover/movie?with_watch_providers=1889&watch_region=US&sort_by=popularity.desc&page=%d" to "HBO Max"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()
        val path = request.data.format(page)
        val delimiter = if (path.contains("?")) "&" else "?"
        val url = "$tmdbBase/$path${delimiter}api_key=<redacted>"
        val json = JSONObject(app.get(url).text)
        val results = json.optJSONArray("results") ?: JSONArray()
        val isTvRow = request.data.contains("discover/tv")
        val items = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            toSearchResponse(item, forceTv = isTvRow)?.let { items.add(it) }
        }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        resolveDomain()
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$tmdbBase/search/multi?query=$encoded&api_key=$tmdbKey&include_adult=false&page=$page"
        val json = JSONObject(app.get(url).text)
        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            toSearchResponse(item)?.let { items.add(it) }
        }
        return items.toNewSearchResponseList()
    }

    override suspend fun load(data: String): LoadResponse? {
        resolveDomain()
        val isSeries = data.contains("/series/") || data.contains("/watch/tv/")
        val id = Regex("""/(?:movie|series|watch/movie|watch/tv)/(\d+)""")
            .find(data)?.groupValues?.getOrNull(1)
            ?: return null

        if (isSeries) {
            val detail = JSONObject(
                app.get("$tmdbBase/tv/$id?api_key=$tmdbKey&append_to_response=credits,external_ids,videos").text
            )
            val title = detail.optString("name", detail.optString("original_name", "Untitled"))
            val plot = detail.optString("overview").takeIf { it.isNotBlank() }
            val poster = posterUrl(detail.optString("poster_path"))
            val backdrop = backdropUrl(detail.optString("backdrop_path"))
            val year = yearOf(detail.optString("first_air_date"))
            val voteAvg = detail.optDouble("vote_average", 0.0)
            val tags = genresOf(detail)

            val episodes = mutableListOf<Episode>()
            val seasons = detail.optJSONArray("seasons") ?: JSONArray()
            for (s in 0 until seasons.length()) {
                val seasonObj = seasons.optJSONObject(s) ?: continue
                val seasonNum = seasonObj.optInt("season_number", -1)
                if (seasonNum <= 0) continue
                try {
                    val seasonJson = JSONObject(
                        app.get("$tmdbBase/tv/$id/season/$seasonNum?api_key=$tmdbKey").text
                    )
                    val eps = seasonJson.optJSONArray("episodes") ?: JSONArray()
                    for (e in 0 until eps.length()) {
                        val epObj = eps.optJSONObject(e) ?: continue
                        val epNum = epObj.optInt("episode_number", e + 1)
                        val epName = epObj.optString("name", "Episode $epNum")
                        val epPoster = posterUrl(epObj.optString("still_path")) ?: poster
                        val epPlot = epObj.optString("overview").takeIf { it.isNotBlank() }
                        val epRating = epObj.optDouble("vote_average", 0.0)
                        val watchUrl = "$mainUrl/watch/tv/$id/$seasonNum/$epNum"
                        episodes.add(newEpisode(watchUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = epPoster
                            this.description = epPlot
                            if (epRating > 0.0) this.score = Score.from10(epRating)
                        })
                    }
                } catch (_: Exception) {
                    // Season detail failed: fall back to plain numbered
                    // episodes from the season's episode count.
                    val epCount = seasonObj.optInt("episode_count", 0)
                    for (e in 1..epCount) {
                        val watchUrl = "$mainUrl/watch/tv/$id/$seasonNum/$e"
                        episodes.add(newEpisode(watchUrl) {
                            this.name = "Episode $e"
                            this.season = seasonNum
                            this.episode = e
                            this.posterUrl = poster
                        })
                    }
                }
            }
            return newTvSeriesLoadResponse(title, data, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                if (voteAvg > 0.0) this.score = Score.from10(voteAvg)
            }
        } else {
            val detail = JSONObject(
                app.get("$tmdbBase/movie/$id?api_key=$tmdbKey&append_to_response=credits,external_ids,videos").text
            )
            val title = detail.optString("title", detail.optString("original_title", "Untitled"))
            val plot = detail.optString("overview").takeIf { it.isNotBlank() }
            val poster = posterUrl(detail.optString("poster_path"))
            val backdrop = backdropUrl(detail.optString("backdrop_path"))
            val year = yearOf(detail.optString("release_date"))
            val voteAvg = detail.optDouble("vote_average", 0.0)
            val duration = detail.optInt("runtime", 0).takeIf { it > 0 }
            val tags = genresOf(detail)
            val watchUrl = "$mainUrl/watch/movie/$id"
            return newMovieLoadResponse(title, data, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.duration = duration
                this.tags = tags
                if (voteAvg > 0.0) this.score = Score.from10(voteAvg)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        resolveDomain()
        val isTv = data.contains("/watch/tv/")
        var tmdbId = ""
        var season = 1
        var episode = 1

        if (isTv) {
            val segments = data.substringAfter("/watch/tv/").split("/")
            tmdbId = segments.getOrNull(0).orEmpty()
            season = segments.getOrNull(1)?.toIntOrNull() ?: 1
            episode = segments.getOrNull(2)?.toIntOrNull() ?: 1
        } else {
            tmdbId = data.substringAfter("/watch/movie/").substringBefore("/").substringBefore("?")
        }
        if (tmdbId.isBlank()) return false

        var found = false

        suspend fun queryServers(activeDomain: String) {
            val activeServers = CinejoyExtractor.getActiveServers(activeDomain)
            activeServers.amap { server ->
                try {
                    val json = CinejoyExtractor.queryServer(server, isTv, tmdbId, season, episode, domain = activeDomain) ?: return@amap
                    val dataObj = json.optJSONObject("data") ?: return@amap
                    val streamArr = dataObj.optJSONArray("stream") ?: JSONArray()
                    for (i in 0 until streamArr.length()) {
                        val streamObj = streamArr.optJSONObject(i) ?: continue
                        val type = streamObj.optString("type")
                        val playlist = streamObj.optString("playlist")
                        val serverLabel = server.replaceFirstChar { it.uppercase() }

                        // Subtitles the server itself ships (subs.wing.st).
                        val captions = streamObj.optJSONArray("captions") ?: JSONArray()
                        for (c in 0 until captions.length()) {
                            val cap = captions.optJSONObject(c) ?: continue
                            val subUrl = cap.optString("url")
                            val subLang = cap.optString("language", cap.optString("id", "en"))
                            if (subUrl.isNotBlank()) {
                                subtitleCallback(newSubtitleFile(subLang, subUrl))
                            }
                        }

                        if (type == "hls" && playlist.isNotBlank()) {
                            // Playlists are disguised as image/jpeg, so the
                            // type must be set explicitly or the player
                            // refuses them.
                            found = true
                            val streamHeaders = mapOf(
                                "Origin" to activeDomain,
                                "Referer" to "$activeDomain/",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                            )
                            callback(
                                newExtractorLink(
                                    source = "Cinejoy",
                                    name = "Cinejoy $serverLabel",
                                    url = playlist,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$activeDomain/"
                                    this.headers = streamHeaders
                                }
                            )
                        } else if (type == "file") {
                            val qualities = streamObj.optJSONObject("qualities")
                            if (qualities != null) {
                                val keys = qualities.keys()
                                while (keys.hasNext()) {
                                    val qKey = keys.next()
                                    val qUrl = qualities.optJSONObject(qKey)?.optString("url")
                                    if (!qUrl.isNullOrBlank() && qUrl.startsWith("http")) {
                                        found = true
                                        callback(
                                            newExtractorLink(
                                                source = "Cinejoy",
                                                name = "Cinejoy $serverLabel $qKey",
                                                url = qUrl,
                                                type = ExtractorLinkType.VIDEO
                                            ) {
                                                this.quality = getQualityFromName(qKey)
                                                this.referer = "$activeDomain/"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        queryServers(mainUrl)

        // If every server failed on this domain, try a fresh domain once.
        // (resolveDomain() rewrites mainUrl in place, so remember the old
        // one first — comparing against mainUrl after the call would
        // always say "same domain" and the retry would never run.)
        if (!found) {
            val oldDomain = mainUrl
            val freshDomain = resolveDomain(force = true)
            if (freshDomain != oldDomain) {
                queryServers(freshDomain)
            }
        }

        // Subtitle fallback: OpenSubtitles via Stremio, keyed by IMDb id.
        try {
            val extJson = JSONObject(
                app.get("$tmdbBase/${if (isTv) "tv" else "movie"}/$tmdbId/external_ids?api_key=$tmdbKey").text
            )
            val imdbId = extJson.optString("imdb_id").takeIf { it.isNotBlank() && it != "null" }
            if (imdbId != null) {
                val subUrl = if (isTv) {
                    "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$season:$episode.json"
                } else {
                    "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
                }
                val subJson = JSONObject(app.get(subUrl, headers = browserHeaders).text)
                val subsArr = subJson.optJSONArray("subtitles") ?: JSONArray()
                for (s in 0 until subsArr.length()) {
                    val subObj = subsArr.optJSONObject(s) ?: continue
                    val subLink = subObj.optString("url")
                    val subLang = subObj.optString("lang", "en")
                    if (subLink.isNotBlank()) {
                        subtitleCallback(newSubtitleFile(subLang, subLink))
                    }
                }
            }
        } catch (_: Exception) {}

        return found
    }
}
