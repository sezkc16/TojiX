package com.multimovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class MultiMoviesProvider : MainAPI() {

    override var mainUrl = "https://m1.multimovies.courses"
    override var name = "MultiMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val vidriftBase = "https://embed.vidrift.net"

    // ---- Data classes ----

    // Episode data passed to loadLinks: the detail/episode page URL to
    // re-fetch (server buttons are embedded per page / per episode).
    data class LinkData(
        @JsonProperty("u") val url: String,
        @JsonProperty("s") val season: Int? = null,
        @JsonProperty("e") val episode: Int? = null,
    )

    // Vidrift embed page: var embedMeta = {...} (JSON). orionStreams is a
    // list of direct mp4s for movies, null for TV (streams come from
    // /api/source instead).
    data class EmbedMeta(
        @JsonProperty("tmdbId") val tmdbId: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("playbackToken") val playbackToken: String? = null,
        @JsonProperty("parent") val parent: String? = null,
        @JsonProperty("orionStreams") val orionStreams: List<OrionStream>? = null,
    )

    data class OrionStream(
        @JsonProperty("index") val index: Int? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("proxyUrl") val proxyUrl: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("subtitleListUrl") val subtitleListUrl: String? = null,
    )

    // Vidrift /api/source response: {streams: [...], subtitles: [...]}
    data class ApiSourceResponse(
        @JsonProperty("streams") val streams: List<OrionStream>? = null,
        @JsonProperty("subtitles") val subtitles: List<Map<String, Any?>>? = null,
    )

    // ---- Helpers ----

    private fun toJson(data: LinkData): String? {
        return try {
            ObjectMapper().writeValueAsString(data)
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    private fun fixUrl(href: String?): String? {
        if (href.isNullOrEmpty()) return null
        return if (href.startsWith("http")) href else mainUrl + href
    }

    private fun String.toExtractorType(): ExtractorLinkType {
        val clean = substringBefore("?").lowercase()
        return when {
            clean.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            clean.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    // Extract a balanced JSON object starting at the first '{' at or after
    // `from` in `html` (respects strings and backslash escapes).
    private fun extractBalancedJson(html: String, from: Int): String? {
        var i = html.indexOf('{', from)
        if (i < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        val start = i
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    // Parse a listing page (catalog / genre / trending / search) into
    // search responses. Cards are <article class="poster-card"> with
    // <a class="poster-art" href="/movie/..."> + <img> + caption link.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.poster-card")) {
            val a = article.selectFirst("a.poster-art") ?: continue
            val href = a.attr("href").trim()
            if (href.isEmpty()) continue
            val url = fixUrl(href) ?: continue
            if (!seen.add(url)) continue
            val img = article.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            var title = cleanTitle(article.selectFirst(".poster-caption a")?.text() ?: "")
            if (title.isEmpty()) title = cleanTitle(a.attr("aria-label"))
            if (title.isEmpty()) title = cleanTitle(img?.attr("alt") ?: "")
            if (title.isEmpty()) continue
            if (href.startsWith("/series/")) {
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        "catalog?type=movie" to "Latest Movies",
        "catalog?type=tv" to "Latest TV",
        "trending" to "Trending",
        "genre/bollywood-movies" to "Bollywood",
        "genre/hollywood" to "Hollywood",
        "genre/anime-hindi" to "Anime Hindi",
        "genre/k-drama" to "K-Drama",
        "genre/south-indian" to "South Indian",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.trim().trim('/')
        val sep = if (data.contains("?")) "&" else "?"
        val url = if (page <= 1) "$mainUrl/$data" else "$mainUrl/$data${sep}page=$page"
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(html)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val html = app.get("$mainUrl/search?q=$q", headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("title")?.text()?.trim() ?: ""
        val title = cleanTitle(rawTitle)
            .replace(Regex(" - MultiMovies 2\\.0$"), "")
            .replace(Regex("\\s*\\(S\\d+ E\\d+\\)$"), "")
            .trim()
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("article.poster-card img, .poster img")?.attr("src")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotEmpty() }
        // Year: first element whose whole text is a 4-digit year.
        val year = doc.select("*").firstOrNull {
            it.ownText().trim().matches(Regex("(19|20)\\d{2}"))
        }?.ownText()?.trim()?.toIntOrNull()

        val isSeries = url.contains("/series/")

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seen = mutableSetOf<String>()
            val epRe = Regex("""/series/[^/]+/season/(\d+)/episode/(\d+)""")
            for (a in doc.select("a[href*=season/][href*=episode/]")) {
                val href = a.attr("href").trim()
                val m = epRe.find(href) ?: continue
                val season = m.groupValues[1].toIntOrNull() ?: continue
                val ep = m.groupValues[2].toIntOrNull() ?: continue
                val epUrl = fixUrl(href) ?: continue
                if (!seen.add("$season-$ep")) continue
                val dataJson = toJson(LinkData(url = epUrl, season = season, episode = ep)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Episode $ep"
                    this.season = season
                    this.episode = ep
                })
            }
            if (episodes.isEmpty()) return null
            episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val dataJson = toJson(LinkData(url = url)) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // ---- Link resolution ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = tryParseJson<LinkData>(data) ?: return false
        val pageUrl = linkData.url
        val html = try {
            app.get(pageUrl, headers = browserHeaders).text
        } catch (e: Exception) {
            return false
        }
        val doc = Jsoup.parse(html)
        // Server buttons: <button class="server-chip" data-url="...">.
        // Jsoup unescapes &amp; in the attribute automatically.
        val serverUrls = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (btn in doc.select("button.server-chip")) {
            val u = btn.attr("data-url").trim()
            if (u.isEmpty() || !seen.add(u)) continue
            serverUrls.add(u)
        }
        if (serverUrls.isEmpty()) return false

        var found = false
        val isTv = linkData.season != null && linkData.episode != null

        // Vidrift first (verified direct mp4s), then Filmu (verified HLS),
        // then everything else via loadExtractor (best effort).
        for (u in serverUrls) {
            when {
                u.contains("vidrift.net") -> {
                    if (resolveVidrift(u, isTv, subtitleCallback, callback)) found = true
                }
                u.contains("filmu.in") || u.contains("vidly.pro") -> {
                    if (resolveFilmu(u, isTv, subtitleCallback, callback)) found = true
                }
            }
        }
        for (u in serverUrls) {
            if (u.contains("vidrift.net") || u.contains("filmu.in") || u.contains("vidly.pro")) continue
            try {
                if (loadExtractor(u, subtitleCallback, callback)) found = true
            } catch (e: Exception) {
                // best effort — one dead server must not kill the rest
            }
        }
        return found
    }

    // Emit subtitles from a parsed subtitle list: objects with url/file +
    // label/lang/name keys. Unknown shapes are skipped silently.
    private fun emitSubtitles(list: List<Map<String, Any?>>?, subtitleCallback: (SubtitleFile) -> Unit) {
        list.orEmpty().forEach { entry ->
            val file = (entry["url"] as? String)?.takeIf { it.isNotBlank() }
                ?: (entry["file"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val label = (entry["label"] as? String)?.takeIf { it.isNotBlank() }
                ?: (entry["lang"] as? String)?.takeIf { it.isNotBlank() }
                ?: (entry["name"] as? String)?.takeIf { it.isNotBlank() }
                ?: "Subtitle"
            subtitleCallback(SubtitleFile(label, file))
        }
    }

    private fun parseSubtitleListJson(json: String): List<Map<String, Any?>>? {
        return try {
            ObjectMapper().readValue(
                json,
                object : TypeReference<List<Map<String, Any?>>>() {}
            )
        } catch (e: Exception) {
            null
        }
    }

    // Fetch a Vidrift subtitleListUrl (relative to embed.vidrift.net) and
    // emit its tracks. Best effort only.
    private suspend fun fetchOrionSubtitles(
        subtitleListUrl: String?,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val path = subtitleListUrl?.takeIf { it.isNotBlank() } ?: return
        val full = if (path.startsWith("http")) path else "$vidriftBase$path"
        try {
            val json = app.get(full, headers = browserHeaders + mapOf("Referer" to referer)).text
            emitSubtitles(parseSubtitleListJson(json), subtitleCallback)
        } catch (e: Exception) {
            // subtitles are optional
        }
    }

    private fun emitOrionStream(
        stream: OrionStream,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val fileUrl = stream.url?.takeIf { it.isNotBlank() }
            ?: stream.proxyUrl?.takeIf { it.isNotBlank() }
            ?: return false
        val name = "MultiMovies Vidrift ${stream.name?.trim() ?: "Video"}".trim()
        callback(
            newExtractorLink(
                source = "MultiMovies Vidrift",
                name = name,
                url = fileUrl,
                type = fileUrl.toExtractorType()
            ) {
                this.headers = mapOf(
                    "User-Agent" to (browserHeaders["User-Agent"] ?: ""),
                    "Referer" to embedUrl,
                )
                quality = Qualities.Unknown.value
            }
        )
        return true
    }

    // Vidrift embed: https://embed.vidrift.net/embed/movie/<tmdb> or
    // /embed/tv/<tmdb>/<S>/<E>. Movie pages bake orionStreams (direct mp4s)
    // in embedMeta; TV pages have orionStreams: null and need
    // GET /api/source/tv/<tmdb>/<S>/<E>?token=...&provider=orion&parent=...
    // (CF-403 from some IPs — best effort, never fatal).
    private suspend fun resolveVidrift(
        embedUrl: String,
        isTv: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = try {
            app.get(embedUrl, headers = browserHeaders + mapOf("Referer" to "$mainUrl/")).text
        } catch (e: Exception) {
            return false
        }
        val metaIdx = html.indexOf("var embedMeta")
        if (metaIdx < 0) return false
        val metaJson = extractBalancedJson(html, metaIdx) ?: return false
        val meta = tryParseJson<EmbedMeta>(metaJson) ?: return false

        var found = false
        val warm = meta.orionStreams.orEmpty()
        if (warm.isNotEmpty()) {
            for (stream in warm) {
                if (emitOrionStream(stream, embedUrl, callback)) found = true
                fetchOrionSubtitles(stream.subtitleListUrl, embedUrl, subtitleCallback)
            }
            return found
        }

        // No baked streams: resolve via /api/source (TV, and as a fallback
        // for movies too). Best effort — this endpoint is CF-protected
        // from some IPs (403) and must not fail the whole loadLinks.
        val tmdbId = meta.tmdbId
            ?: run {
                // Fall back to the tmdb id in the embed URL path.
                val m = Regex("""/embed/(?:movie|tv)/(\d+)""").find(embedUrl)
                m?.groupValues?.get(1)
            } ?: return false
        val season = meta.season ?: 1
        val episode = meta.episode ?: 1
        val token = meta.playbackToken ?: return false
        val parent = meta.parent?.takeIf { it.isNotBlank() } ?: "m1.multimovies.courses"
        val typePath = if (isTv || meta.type == "tv") {
            "tv/$tmdbId/$season/$episode"
        } else {
            "movie/$tmdbId"
        }
        val apiUrl = "$vidriftBase/api/source/$typePath" +
            "?token=${java.net.URLEncoder.encode(token, "UTF-8")}" +
            "&provider=orion&parent=${java.net.URLEncoder.encode(parent, "UTF-8")}"
        return try {
            val resp = app.get(
                apiUrl,
                headers = browserHeaders + mapOf("Referer" to embedUrl),
            ).text
            val parsed = tryParseJson<ApiSourceResponse>(resp) ?: return false
            emitSubtitles(parsed.subtitles, subtitleCallback)
            for (stream in parsed.streams.orEmpty()) {
                if (emitOrionStream(stream, embedUrl, callback)) found = true
                fetchOrionSubtitles(stream.subtitleListUrl, embedUrl, subtitleCallback)
            }
            found
        } catch (e: Exception) {
            // CF-403 / network failure on the API — best effort only.
            found
        }
    }

    // Filmu embed: https://embed.filmu.in/movie|tv/... 301s to vidly.pro.
    // Movie pages contain a proxy.streamrip.fun master m3u8; TV pages bake
    // a SIGNED dash-hls-bridge URL (JSON-escaped \u0026 = & — must be
    // unescaped before use; the unsigned URL 502s).
    private suspend fun resolveFilmu(
        embedUrl: String,
        isTv: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resp = try {
            app.get(embedUrl, headers = browserHeaders + mapOf("Referer" to "$mainUrl/"))
        } catch (e: Exception) {
            return false
        }
        val html = resp.text
        val finalUrl = resp.url

        var found = false

        // TV: signed HLS bridge URL (use the full signed URL as baked).
        val bridgeRe = Regex("""https://dash-hls-bridge\.johndoesdmc\.workers\.dev/(?:\\u0026|[^"\\])*""")
        val bridge = bridgeRe.find(html)?.value
            ?.replace("\\u0026", "&")
            ?.takeIf { it.isNotBlank() }
        if (bridge != null) {
            callback(
                newExtractorLink(
                    source = "MultiMovies Filmu",
                    name = "MultiMovies Filmu HLS",
                    url = bridge,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "User-Agent" to (browserHeaders["User-Agent"] ?: ""),
                        "Referer" to finalUrl,
                    )
                    quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        // Movie: streamrip master m3u8 (also checked on TV pages as a
        // fallback — harmless if absent).
        val ripRe = Regex("""https://proxy\.streamrip\.fun/m/[^\s"\\]+""")
        val rip = ripRe.find(html)?.value?.takeIf { it.isNotBlank() }
        if (rip != null) {
            callback(
                newExtractorLink(
                    source = "MultiMovies Filmu",
                    name = "MultiMovies Filmu HLS",
                    url = rip,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "User-Agent" to (browserHeaders["User-Agent"] ?: ""),
                        "Referer" to finalUrl,
                    )
                    quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        return found
    }
}
