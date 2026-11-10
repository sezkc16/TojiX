package com.shuttletv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// ShuttleTV — TMDB-catalog provider.
//
// ShuttleTV (shuttletv.pk) is a Next.js TMDB frontend. Its own video player
// (CineSrc) is gated behind a browser-only WASM/JS challenge, so no
// CloudStream provider can use it. This module therefore works the same way
// as the community ShuttleTV module: TMDB v3 for catalog / search / detail,
// and third-party stream extractors (Videasy, Vidlink, VidFast, Xpass,
// Stremio addons, ProjectFreeTv, Autoembed) for playback.
// Streams do NOT come from ShuttleTV itself.
class ShuttleTVProvider : MainAPI() {

    override var mainUrl = "https://shuttletv.pk"
    override var name = "ShuttleTV"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // TMDB auth: Bearer read token (v4). TMDB's own API is blocked from
    // datacenter IPs, so calls could not be verified from the build machine;
    // they should work from a phone / in-app OkHttp.
    private val tmdbHeaders = mapOf(
        "Authorization" to "Bearer $tmdbReadToken",
        "Accept" to "application/json",
        "User-Agent" to chromeUa,
    )

    private val streamHeaders = mapOf(
        "Accept" to "*/*",
        "User-Agent" to chromeUa,
        "Origin" to "https://player.videasy.net",
        "Referer" to "https://shuttletv.pk/",
    )

    override val mainPage = mainPageOf(
        "trending/movie/week" to "Trending Movies",
        "trending/tv/week" to "Trending Shows",
        "movie/popular" to "Popular Movies",
        "tv/popular" to "Popular Shows",
        "movie/top_rated" to "Top Rated Movies",
        "tv/top_rated" to "Top Rated Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = tmdbGet<TmdbResults>("${request.data}?language=en-US&page=$page")
        val cards = response?.results.orEmpty()
            .mapNotNull { it.toSearchResponse(request.data.mediaTypeFromPath()) }
        return newHomePageResponse(HomePageList(request.name, cards), hasNext = cards.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList<com.lagradost.cloudstream3.SearchResponse>().toNewSearchResponseList()
        val encoded = cleanQuery.urlEncode()
        val response = tmdbGet<TmdbResults>("search/multi?language=en-US&query=$encoded&page=$page")
            ?: return null
        return response.results
            .filter { it.media_type != "person" }
            .mapNotNull { it.toSearchResponse(it.media_type) }
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<TmdbData>(url) ?: return null
        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        val detailPath = "${data.type}/${data.id}?language=en-US&append_to_response=external_ids,videos"
        val detail = tmdbGet<TmdbDetail>(detailPath) ?: return null
        val title = detail.title ?: detail.name ?: data.title ?: return null
        val poster = detail.poster_path.tmdbImage()
        val year = (detail.release_date ?: detail.first_air_date).year()
        val imdbId = detail.external_ids?.imdb_id

        return if (type == TvType.Movie) {
            val streamJson = TmdbStreamData(
                type = "movie",
                tmdbId = data.id,
                imdbId = imdbId,
                title = title,
                year = year,
            ).toJson() ?: return null
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                streamJson
            ) {
                posterUrl = poster
                this.year = year
                plot = detail.overview
            }
        } else {
            val episodes = detail.seasons
                .orEmpty()
                .filter { (it.season_number ?: 0) > 0 }
                .flatMap { season ->
                    loadSeasonEpisodes(data.id, title, year, imdbId, season.season_number)
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = detail.overview
            }
        }
    }

    private suspend fun loadSeasonEpisodes(
        showId: Int?,
        showTitle: String,
        showYear: Int?,
        imdbId: String?,
        season: Int?,
    ): List<Episode> {
        if (showId == null || season == null) return emptyList()
        return tmdbGet<TmdbSeason>("tv/$showId/season/$season?language=en-US")
            ?.episodes
            .orEmpty()
            .mapNotNull { episode ->
                val streamJson = TmdbStreamData(
                    type = "tv",
                    tmdbId = showId,
                    imdbId = imdbId,
                    title = showTitle,
                    year = showYear ?: episode.air_date.year(),
                    season = season,
                    episode = episode.episode_number,
                ).toJson() ?: return@mapNotNull null
                newEpisode(streamJson) {
                    name = episode.name
                    this.season = season
                    this.episode = episode.episode_number
                    posterUrl = episode.still_path.tmdbImage()
                    description = episode.overview
                    runTime = episode.runtime
                }
            }
    }

    private fun TmdbMedia.toSearchResponse(fallbackType: String?): com.lagradost.cloudstream3.SearchResponse? {
        val type = media_type ?: fallbackType ?: return null
        val title = title ?: name ?: original_title ?: original_name ?: return null
        val payload = TmdbData(id = id ?: return null, type = type, title = title).toJson() ?: return null
        val poster = poster_path.tmdbImage()
        return if (type == "tv") {
            newTvSeriesSearchResponse(title, payload, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, payload, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    private suspend inline fun <reified T : Any> tmdbGet(path: String): T? {
        return runCatching {
            app.get("$tmdbApi/${path.trimStart('/')}", headers = tmdbHeaders).parsed<T>()
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamData = tryParseJson<TmdbStreamData>(data) ?: return false
        var found = false

        if (invokeVideasy(streamData, subtitleCallback, callback)) found = true
        if (invokeVidlink(streamData, callback)) found = true
        if (invokeXpass(streamData, callback)) found = true
        if (invokeStremioHttps(streamData, subtitleCallback, callback)) found = true
        if (invokeAutoembed(streamData, subtitleCallback, callback)) found = true
        if (invokeProjectFreeTv(streamData, subtitleCallback, callback)) found = true
        if (!found && invokeVidFastPro(streamData, subtitleCallback, callback)) found = true

        return found
    }

    // ---- Videasy ----
    // GET api.videasy.net/<server>/sources-with-title?... -> encrypted text
    // POST enc-dec.app/api/dec-videasy {text, id} -> {result: {sources, subtitles}}
    private suspend fun invokeVideasy(
        data: TmdbStreamData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.tmdbId == null || data.title.isNullOrBlank()) return false
        val encodedTitle = data.title.urlEncode().urlEncode()
        var found = false
        val servers = if (data.season == null) videasyMovieServers else videasyTvServers

        for (server in servers) {
            val sourceUrl = if (data.season == null) {
                "$videasyApi/$server/sources-with-title?title=$encodedTitle&mediaType=movie&year=${data.year.orEmpty()}&tmdbId=${data.tmdbId}&imdbId=${data.imdbId.orEmpty()}"
            } else {
                "$videasyApi/$server/sources-with-title?title=$encodedTitle&mediaType=tv&year=${data.year.orEmpty()}&tmdbId=${data.tmdbId}&episodeId=${data.episode.orEmpty()}&seasonId=${data.season}&imdbId=${data.imdbId.orEmpty()}"
            }

            val encrypted = runCatching {
                app.get(sourceUrl, headers = streamHeaders).text
            }.getOrNull().orEmpty()
            if (encrypted.isBlank()) continue

            val decrypted = runCatching {
                app.post(
                    "$decryptApi/dec-videasy",
                    json = mapOf("text" to encrypted, "id" to data.tmdbId.toString()),
                ).parsed<VideasyDecryptResponse>()
            }.getOrNull()?.result ?: continue

            decrypted.sources.forEach { source ->
                val url = source.url ?: return@forEach
                callback(
                    newExtractorLink(
                        source = "ShuttleTV Videasy",
                        name = "ShuttleTV Videasy ${server.uppercase()} ${source.quality.orEmpty()}".trim(),
                        url = url,
                        type = url.toExtractorType()
                    ) {
                        quality = qualityValue(source.quality ?: url)
                        headers = url.videoHeaders()
                    }
                )
                found = true
            }

            decrypted.subtitles.forEach { subtitle ->
                val url = subtitle.url ?: return@forEach
                val language = subtitle.language ?: subtitle.lang ?: "Subtitle"
                subtitleCallback(SubtitleFile(language, url))
            }
        }

        return found
    }

    // ---- Vidlink ----
    // GET enc-dec.app/api/enc-vidlink?text=<tmdbId> -> {result: <encoded id>}
    // GET vidlink.pro/api/b/movie|tv/<encoded>[/<season>/<episode>] ->
    //   {stream: {qualities: {"1080": {url, type, headers, requiresProxy}, ...}, captions}}
    // (The API used to return a single stream.playlist URL; that field no
    // longer exists, so the legacy shape is only kept as a fallback.)
    private suspend fun invokeVidlink(
        data: TmdbStreamData,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val tmdbId = data.tmdbId ?: return false
        val encodedId = runCatching {
            app.get("$decryptApi/enc-vidlink?text=$tmdbId")
                .parsed<VidlinkEncodeResponse>()
                ?.result
        }.getOrNull() ?: return false

        val baseHeaders = mapOf(
            "User-Agent" to chromeUa,
            "Connection" to "keep-alive",
            "Referer" to "$vidlinkApi/",
            "Origin" to vidlinkApi,
        )

        val streamUrl = if (data.season == null) {
            "$vidlinkApi/api/b/movie/$encodedId"
        } else {
            "$vidlinkApi/api/b/tv/$encodedId/${data.season}/${data.episode.orEmpty()}"
        }

        val stream = runCatching {
            app.get(streamUrl, headers = baseHeaders)
                .parsed<VidlinkResponse>()
                ?.stream
        }.getOrNull() ?: return false

        var found = false

        // New API shape: one direct URL per quality, highest first.
        val qualities = stream.qualities.orEmpty()
            .filterValues { it.url?.isNotBlank() == true }
            .toList()
            .sortedByDescending { (key, _) -> key.toIntOrNull() ?: 0 }
        qualities.forEach { (qualityKey, quality) ->
            val url = quality.url ?: return@forEach
            val label = "${qualityKey}p"
            // The quality's own headers (e.g. referer/origin of the actual
            // file host) override the base Vidlink ones; drop any base key
            // that matches case-insensitively so no duplicate header is sent.
            val mergedHeaders = baseHeaders.toMutableMap()
            quality.headers.orEmpty().forEach { (key, value) ->
                mergedHeaders.keys
                    .filter { it.equals(key, ignoreCase = true) }
                    .forEach { mergedHeaders.remove(it) }
                mergedHeaders[key] = value
            }
            callback(
                newExtractorLink(
                    source = "ShuttleTV Vidlink",
                    name = "ShuttleTV Vidlink $label",
                    url = url,
                    type = url.toExtractorType()
                ) {
                    quality = qualityValue(label)
                    this.headers = mergedHeaders
                }
            )
            found = true
        }

        // Legacy shape fallback: a single playlist URL.
        if (!found) {
            val playlist = stream.playlist?.takeIf { it.isNotBlank() } ?: return false
            callback(
                newExtractorLink(
                    source = "ShuttleTV Vidlink",
                    name = "ShuttleTV Vidlink",
                    url = playlist,
                    type = playlist.toExtractorType()
                ) {
                    quality = Qualities.Unknown.value
                    this.headers = baseHeaders
                }
            )
            found = true
        }

        return found
    }

    // ---- VidFast ----
    // GET vidfast.pro/movie|tv/<tmdbId>[/<season>/<episode>] -> page with "en":"<text>"
    // GET enc-dec.app/api/enc-vidfast?text=<text>&version=1 -> {servers, stream, token}
    // POST <servers> (empty form body) -> encrypted server list
    // POST enc-dec.app/api/dec-vidfast {text, version} -> server list
    // per server: POST <stream>/<hash> -> encrypted -> dec-vidfast -> {url, tracks, 4kAvailable}
    private suspend fun invokeVidFastPro(
        data: TmdbStreamData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val tmdbId = data.tmdbId ?: return false
        val pageUrl = if (data.season == null) {
            "$vidfastApi/movie/$tmdbId"
        } else {
            "$vidfastApi/tv/$tmdbId/${data.season}/${data.episode.orEmpty()}"
        }
        val headers = mutableMapOf(
            "User-Agent" to chromeUa,
            "Referer" to "$vidfastApi/",
            "X-Requested-With" to "XMLHttpRequest",
        )

        val encodedText = runCatching {
            val page = app.get(pageUrl, headers = headers).text
            Regex("""\\"en\\":\\"(.*?)\\"""").find(page)?.groupValues?.get(1)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false

        val decoded = runCatching {
            app.get("$decryptApi/enc-vidfast?text=${encodedText.urlEncode()}&version=1")
                .parsed<VidFastDecodeResponse>()
                ?.result
        }.getOrNull() ?: return false

        val serversUrl = decoded.servers ?: return false
        val streamBaseUrl = decoded.stream ?: return false
        val token = decoded.token ?: return false
        headers["X-CSRF-Token"] = token

        // The servers endpoint is a POST with no form fields; pass an empty
        // form body so OkHttp still has a (non-null) request body.
        val serversEncrypted = runCatching {
            app.post(serversUrl, headers = headers, data = mapOf<String, String>()).text
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false

        val servers = runCatching {
            app.post(
                "$decryptApi/dec-vidfast",
                json = mapOf("text" to serversEncrypted, "version" to "1"),
            ).parsed<VidFastServersResponse>()?.result.orEmpty()
        }.getOrDefault(emptyList())

        return invokeVidFastServers(
            servers = servers.prioritizedVidFastServers(isTv = data.season != null),
            streamBaseUrl = streamBaseUrl,
            headers = headers,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }

    private suspend fun invokeVidFastServers(
        servers: List<VidFastServerInfo>,
        streamBaseUrl: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        servers.forEach { server ->
            val serverHash = server.data ?: return@forEach
            val encrypted = runCatching {
                app.post("$streamBaseUrl/$serverHash", headers = headers, data = mapOf<String, String>()).text
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach

            val stream = runCatching {
                app.post(
                    "$decryptApi/dec-vidfast",
                    json = mapOf("text" to encrypted, "version" to "1"),
                ).parsed<VidFastStreamResponse>()?.result
            }.getOrNull() ?: return@forEach

            stream.tracks.orEmpty().forEach { track ->
                val file = track.file ?: return@forEach
                val label = track.label ?: "Subtitle"
                subtitleCallback(SubtitleFile(label, file))
            }

            val fileUrl = stream.url?.takeIf { it.isNotBlank() } ?: return@forEach
            callback(
                newExtractorLink(
                    source = "ShuttleTV VidFast",
                    name = "ShuttleTV VidFast ${server.name.orEmpty()} ${server.description.orEmpty()}".trim(),
                    url = fileUrl,
                    type = fileUrl.toExtractorType()
                ) {
                    this.headers = headers
                    quality = if (stream.`4kAvailable` == true || server.description?.contains("4K", true) == true) {
                        Qualities.P2160.value
                    } else {
                        Qualities.P1080.value
                    }
                }
            )
            found = true
        }

        return found
    }

    // ---- Xpass ----
    // GET play.xpass.top/e/movie|tv/<tmdbId>[/<season>/<episode>] -> page with
    // `var backups=[{name, url}]`; each backup playlist is fetched and its
    // sources emitted.
    private suspend fun invokeXpass(
        data: TmdbStreamData,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val tmdbId = data.tmdbId ?: return false
        val embedUrl = if (data.season == null) {
            "$xpassApi/e/movie/$tmdbId"
        } else {
            "$xpassApi/e/tv/$tmdbId/${data.season}/${data.episode.orEmpty()}"
        }
        val headers = mapOf(
            "User-Agent" to chromeUa,
            "Referer" to "$xpassApi/",
        )

        val page = runCatching {
            app.get(embedUrl, headers = headers).text
        }.getOrNull().orEmpty()
        val backupsJson = page.extractJsonArrayAfter("var backups=") ?: return false
        val backups = tryParseJson<List<XpassBackup>>(backupsJson) ?: emptyList()

        val seen = mutableSetOf<String>()
        var found = false
        backups.forEach { backup ->
            val playlistPath = backup.url?.takeIf { it.isNotBlank() } ?: return@forEach
            val playlistUrl = if (playlistPath.startsWith("http", true)) playlistPath else "$xpassApi$playlistPath"
            val playlist = runCatching {
                app.get(playlistUrl, headers = headers)
                    .parsed<XpassPlaylistResponse>()
            }.getOrNull() ?: return@forEach

            playlist.playlist
                .flatMap { it.sources.orEmpty() }
                .forEach { source ->
                    val file = source.file?.takeIf { it.startsWith("http", true) } ?: return@forEach
                    if (file.contains("/video/error", true) || !seen.add(file)) return@forEach
                    callback(
                        newExtractorLink(
                            source = "ShuttleTV Xpass",
                            name = "ShuttleTV Xpass ${backup.name.orEmpty()} ${source.label.orEmpty()}".trim(),
                            url = file,
                            type = if (source.type?.contains("hls", true) == true || file.contains(".m3u8", true)) {
                                ExtractorLinkType.M3U8
                            } else {
                                file.toExtractorType()
                            }
                        ) {
                            this.headers = headers
                            quality = qualityValue(source.label ?: file)
                        }
                    )
                    found = true
                }
        }

        return found
    }

    // ---- Stremio (HTTPS addons, needs an IMDb id) ----
    // GET <addon>/stream/movie|series/<imdbId>[:<season>:<episode>].json
    private suspend fun invokeStremioHttps(
        data: TmdbStreamData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val imdbId = data.imdbId?.takeIf { it.isNotBlank() } ?: return false
        val streamPath = if (data.season == null) {
            "stream/movie/$imdbId.json"
        } else {
            "stream/series/$imdbId:${data.season}:${data.episode.orEmpty()}.json"
        }

        val seen = mutableSetOf<String>()
        var found = false
        stremioHttpsAddons.forEach { (sourceName, api) ->
            val response = runCatching {
                app.get("$api/$streamPath").parsed<StremioStreamResponse>()
            }.getOrNull() ?: return@forEach

            response.streams
                .asSequence()
                .filterNot { it.isBlockedStremioStream() }
                .mapNotNull { stream ->
                    val streamUrl = stream.url?.takeIf { url ->
                        url.startsWith("http", true) && seen.add(url)
                    } ?: return@mapNotNull null
                    stream to streamUrl
                }
                .forEach { (stream, streamUrl) ->
                    val title = stream.description ?: stream.title ?: stream.name.orEmpty()
                    val label = title.lineSequence().firstOrNull { it.isNotBlank() } ?: sourceName
                    callback(
                        newExtractorLink(
                            source = "ShuttleTV $sourceName",
                            name = "ShuttleTV $sourceName $label".trim(),
                            url = streamUrl,
                            type = streamUrl.stremioExtractorType(sourceName, title)
                        ) {
                            headers = stream.toRequestHeaders()
                            quality = qualityValue(title)
                        }
                    )
                    found = true

                    stream.subtitles.orEmpty().forEach { subtitle ->
                        val subtitleUrl = subtitle.url?.takeIf { it.startsWith("http", true) } ?: return@forEach
                        val language = subtitle.lang ?: "Subtitle"
                        subtitleCallback(SubtitleFile(language, subtitleUrl))
                    }
                }
        }

        return found
    }

    // ---- ProjectFreeTv ----
    // GET projectfreetv.sx/data/browse/?...&keyword=<title>&year=<year> -> {_id}
    // GET projectfreetv.sx/data/watch/?_id=<id> -> streams; each via loadExtractor.
    private suspend fun invokeProjectFreeTv(
        data: TmdbStreamData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val title = data.title?.takeIf { it.isNotBlank() } ?: return false
        val query = if (data.season == null) {
            title
        } else {
            "$title - season ${data.season}"
        }.urlEncode().replace("%20", "+")
        val searchUrl = "$projectFreeTvApi/data/browse/?lang=3&keyword=$query&year=${data.year.orEmpty()}&networks=&rating=&votes=&genre=&country=&cast=&directors=&type=&order_by=&page=1&limit=1"

        val search = runCatching {
            app.get(searchUrl, headers = mapOf("Referer" to projectFreeTvApi))
                .parsed<ProjectFreeTvBrowseResponse>()
        }.getOrNull()
        val id = search?.movies.orEmpty().firstOrNull()?._id ?: return false
        val watch = runCatching {
            app.get(
                "$projectFreeTvApi/data/watch/?_id=$id",
                headers = mapOf("Referer" to projectFreeTvApi)
            ).parsed<ProjectFreeTvWatchResponse>()
        }.getOrNull() ?: return false

        var found = false
        watch.streams
            .orEmpty()
            .filter { stream -> data.episode == null || stream.e == null || stream.e == data.episode }
            .mapNotNull { it.stream?.takeIf { url -> url.startsWith("http", true) } }
            .distinct()
            .take(30)
            .forEach { url ->
                if (loadExtractor(url, subtitleCallback, callback)) found = true
            }

        return found
    }

    // ---- Autoembed ----
    // GET player.autoembed.app/embed/movie|tv/<imdbId>[/<season>/<episode>]
    // -> page with `var embedUrlValue = "<url>";` (or a cloudnestra rcp link);
    // resolve that embed via loadExtractor.
    private suspend fun invokeAutoembed(
        data: TmdbStreamData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val imdbId = data.imdbId?.takeIf { it.isNotBlank() } ?: return false
        val pageUrl = if (data.season == null) {
            "$autoembedApi/embed/movie/$imdbId"
        } else {
            "$autoembedApi/embed/tv/$imdbId/${data.season}/${data.episode.orEmpty()}"
        }
        val headers = mapOf(
            "User-Agent" to chromeUa,
            "Referer" to "$autoembedApi/",
        )

        val page = runCatching {
            app.get(pageUrl, headers = headers).text
        }.getOrNull().orEmpty()
        if (page.isBlank()) return false

        val embedUrl = Regex("""var\s+embedUrlValue\s*=\s*"([^"]+)";""")
            .find(page)
            ?.groupValues
            ?.get(1)
            ?: Regex("""https?:\\?/\\?/cloudnestra\.com/rcp/[^"'<\s]+""")
                .find(page)
                ?.value
                ?.replace("\\/", "/")

        return embedUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { loadExtractor(it, subtitleCallback, callback) }
            ?: false
    }

    // ---- helpers ----

    private fun Any.toJson(): String? {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(this)
        } catch (e: Exception) { null }
    }

    private fun qualityValue(label: String?): Int {
        return when {
            label == null -> Qualities.Unknown.value
            label.contains("2160") || label.contains("4K", true) -> Qualities.P2160.value
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun StremioStream.isBlockedStremioStream(): Boolean {
        val streamUrl = url.orEmpty()
        val label = "${name.orEmpty()} ${title.orEmpty()} ${description.orEmpty()}"
        return streamUrl.isBlank() ||
            streamUrl.contains("github.com", true) ||
            streamUrl.contains("googleusercontent", true) ||
            streamUrl.contains("youtube.com", true) ||
            streamUrl.contains("youtu.be", true) ||
            label.contains("trailer", true) ||
            label.contains("redirecting", true) ||
            listOf("4KHDHub", "Instant Download", "IOSMIRROR", "XDM").any { label.contains(it, true) }
    }

    private fun StremioStream.toRequestHeaders(): Map<String, String> {
        val proxyHeaders = behaviorHints?.proxyHeaders?.request
        val standardHeaders = behaviorHints?.headers.orEmpty()
        return mapOf(
            "User-Agent" to (proxyHeaders?.get("User-Agent")
                ?: proxyHeaders?.get("user-agent")
                ?: standardHeaders["User-Agent"]
                ?: standardHeaders["user-agent"]
                ?: chromeUa),
            "Referer" to (proxyHeaders?.get("Referer")
                ?: proxyHeaders?.get("referer")
                ?: standardHeaders["Referer"]
                ?: standardHeaders["referer"]
                ?: ""),
            "Origin" to (proxyHeaders?.get("Origin")
                ?: proxyHeaders?.get("origin")
                ?: standardHeaders["Origin"]
                ?: standardHeaders["origin"]
                ?: ""),
        ).filterValues { it.isNotBlank() }
    }

    private fun String.stremioExtractorType(sourceName: String, title: String): ExtractorLinkType {
        return when {
            sourceName.equals("NoTorrent", true) -> ExtractorLinkType.M3U8
            contains(".m3u8", true) || contains("/playlist", true) -> ExtractorLinkType.M3U8
            contains("cf-master", true) || endsWith(".txt", true) -> ExtractorLinkType.M3U8
            title.contains("hls", true) || title.contains("m3u8", true) -> ExtractorLinkType.M3U8
            else -> toExtractorType()
        }
    }

    private fun List<VidFastServerInfo>.prioritizedVidFastServers(isTv: Boolean): List<VidFastServerInfo> {
        val priority = if (isTv) vidfastTvServerOrder else vidfastMovieServerOrder
        val verifiedServers = filter { it.name in priority }
        val candidates = verifiedServers.takeIf { it.isNotEmpty() } ?: this
        return candidates.sortedWith(
            compareBy<VidFastServerInfo> { server ->
                val index = priority.indexOf(server.name)
                if (index == -1) Int.MAX_VALUE else index
            }.thenBy { it.name.orEmpty() }
        )
    }

    private fun String.extractJsonArrayAfter(marker: String): String? {
        val start = indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until length) {
            val char = this[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return substring(start, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun String.mediaTypeFromPath(): String {
        return if (contains("/tv") || startsWith("tv")) "tv" else "movie"
    }

    private fun String?.tmdbImage(size: String = "w500"): String? {
        val path = this ?: return null
        return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/$size$path"
    }

    private fun String?.year(): Int? = this?.substringBefore("-")?.toIntOrNull()

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    private fun Int?.orEmpty(): String = this?.toString().orEmpty()
    private fun String?.orEmpty(): String = this ?: ""

    private fun String.toExtractorType(): ExtractorLinkType {
        return when {
            contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            contains(".mp4", ignoreCase = true) || contains(".mkv", ignoreCase = true) -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun String.videoHeaders(): Map<String, String> {
        val headers = streamHeaders.toMutableMap()
        when {
            contains(".m3u8", ignoreCase = true) -> {
                headers["Accept"] = "application/vnd.apple.mpegurl,application/x-mpegURL,*/*"
                headers["Referer"] = "$videasyApi/"
            }
            contains(".mp4", ignoreCase = true) -> {
                headers["Accept"] = "video/mp4,*/*"
                headers["Range"] = "bytes=0-"
            }
            contains(".mkv", ignoreCase = true) -> {
                headers["Accept"] = "video/x-matroska,*/*"
                headers["Range"] = "bytes=0-"
            }
        }
        return headers
    }

    // ---- data models ----

    data class TmdbData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
    )

    data class TmdbStreamData(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
    )

    data class TmdbResults(
        @JsonProperty("results") val results: List<TmdbMedia> = emptyList(),
    )

    data class TmdbMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("media_type") val media_type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val original_title: String? = null,
        @JsonProperty("original_name") val original_name: String? = null,
        @JsonProperty("poster_path") val poster_path: String? = null,
    )

    data class TmdbDetail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val poster_path: String? = null,
        @JsonProperty("backdrop_path") val backdrop_path: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeasonSummary> = emptyList(),
        @JsonProperty("external_ids") val external_ids: TmdbExternalIds? = null,
    )

    data class TmdbSeasonSummary(
        @JsonProperty("season_number") val season_number: Int? = null,
    )

    data class TmdbSeason(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode> = emptyList(),
    )

    data class TmdbEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("episode_number") val episode_number: Int? = null,
        @JsonProperty("still_path") val still_path: String? = null,
        @JsonProperty("air_date") val air_date: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
    )

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdb_id: String? = null,
    )

    data class VideasyDecryptResponse(
        @JsonProperty("result") val result: VideasyResult? = null,
    )

    data class VideasyResult(
        @JsonProperty("sources") val sources: List<VideasySource> = emptyList(),
        @JsonProperty("subtitles") val subtitles: List<VideasySubtitle> = emptyList(),
    )

    data class VideasySource(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class VideasySubtitle(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class VidlinkEncodeResponse(
        @JsonProperty("result") val result: String? = null,
    )

    data class VidlinkResponse(
        @JsonProperty("stream") val stream: VidlinkStream? = null,
    )

    data class VidlinkStream(
        @JsonProperty("qualities") val qualities: Map<String, VidlinkQuality>? = null,
        @JsonProperty("playlist") val playlist: String? = null,
    )

    data class VidlinkQuality(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("requiresProxy") val requiresProxy: Boolean? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
    )

    data class VidFastDecodeResponse(
        @JsonProperty("result") val result: VidFastDecodeResult? = null,
    )

    data class VidFastDecodeResult(
        @JsonProperty("servers") val servers: String? = null,
        @JsonProperty("stream") val stream: String? = null,
        @JsonProperty("token") val token: String? = null,
    )

    data class VidFastServersResponse(
        @JsonProperty("result") val result: List<VidFastServerInfo> = emptyList(),
    )

    data class VidFastServerInfo(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("data") val data: String? = null,
    )

    data class VidFastStreamResponse(
        @JsonProperty("result") val result: VidFastStream? = null,
    )

    data class VidFastStream(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("tracks") val tracks: List<VidFastTrack> = emptyList(),
        @JsonProperty("4kAvailable") val `4kAvailable`: Boolean? = null,
    )

    data class VidFastTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

    data class XpassBackup(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class XpassPlaylistResponse(
        @JsonProperty("playlist") val playlist: List<XpassPlaylistItem> = emptyList(),
    )

    data class XpassPlaylistItem(
        @JsonProperty("sources") val sources: List<XpassSource> = emptyList(),
    )

    data class XpassSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

    data class ProjectFreeTvBrowseResponse(
        @JsonProperty("movies") val movies: List<ProjectFreeTvMovie> = emptyList(),
    )

    data class ProjectFreeTvMovie(
        @JsonProperty("_id") val _id: String? = null,
    )

    data class ProjectFreeTvWatchResponse(
        @JsonProperty("streams") val streams: List<ProjectFreeTvStream> = emptyList(),
    )

    data class ProjectFreeTvStream(
        @JsonProperty("stream") val stream: String? = null,
        @JsonProperty("e") val e: Int? = null,
    )

    data class StremioStreamResponse(
        @JsonProperty("streams") val streams: List<StremioStream> = emptyList(),
    )

    data class StremioStream(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("subtitles") val subtitles: List<StremioSubtitle> = emptyList(),
        @JsonProperty("behaviorHints") val behaviorHints: StremioBehaviorHints? = null,
    )

    data class StremioSubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
    )

    data class StremioBehaviorHints(
        @JsonProperty("proxyHeaders") val proxyHeaders: StremioProxyHeaders? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
    )

    data class StremioProxyHeaders(
        @JsonProperty("request") val request: Map<String, String>? = null,
    )

    companion object {
        private const val chromeUa =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val tmdbApi = "https://api.themoviedb.org/3"
        private const val videasyApi = "https://api.videasy.net"
        private const val vidlinkApi = "https://vidlink.pro"
        private const val vidfastApi = "https://vidfast.pro"
        private const val autoembedApi = "https://player.autoembed.app"
        private const val xpassApi = "https://play.xpass.top"
        private const val projectFreeTvApi = "https://projectfreetv.sx"
        private const val decryptApi = "https://enc-dec.app/api"
        private val stremioHttpsAddons = listOf(
            "Streamvix" to "https://streamvix.hayd.uk",
            "NoTorrent" to "https://addon-osvh.onrender.com",
        )
        // TMDB v4 read-access token (Bearer). Public read-only token used by
        // the community ShuttleTV module for the same catalog calls.
        private const val tmdbReadToken =
            "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI1YjEwYWNhZDFhNjY3ZTQwMDEyMGVjMTc1ZDBjZTFmZCIsIm5iZiI6MTcyNDk1Mjg3MC45NDA4NDcsInN1YiI6IjY2ZDBhOTgyODQ1OWYzM2FmMjBmYjdkNSIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.ScGHs1VZTLGpUKWPG7EA-2T29OPcqW_qpJjKL5Yhrjc"
        private val videasyMovieServers = listOf("cdn")
        private val videasyTvServers = listOf("cdn")
        private val vidfastMovieServerOrder = listOf(
            "Alpha", "vEdge", "vRapid", "Mega", "vFast", "Max", "Beta", "Cobra", "Vodka",
        )
        private val vidfastTvServerOrder = listOf(
            "Alpha", "vEdge", "Mega", "vFast", "Max", "Beta", "Cobra", "Vodka",
        )
    }
}
