package com.multimovies

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URL
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbedMeta(
        @JsonProperty("tmdbId") val tmdbId: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("playbackToken") val playbackToken: String? = null,
        @JsonProperty("parent") val parent: String? = null,
        @JsonProperty("orionStreams") val orionStreams: List<OrionStream>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
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
    @JsonIgnoreProperties(ignoreUnknown = true)
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
        // Server buttons: <button class="server-chip" data-url="...">Label</button>.
        // Jsoup unescapes &amp; in the attribute automatically.
        val servers = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        for (btn in doc.select("button.server-chip")) {
            val u = btn.attr("data-url").trim()
            if (u.isEmpty() || !seen.add(u)) continue
            val label = btn.text().trim().takeIf { it.isNotBlank() } ?: "MultiMovies"
            servers.add(u to label)
        }
        if (servers.isEmpty()) return false

        var found = false
        val isTv = linkData.season != null && linkData.episode != null

        // v4: Phisher-style per-host dispatch. Our v3 only resolved
        // Vidrift + Filmu (both blocked from the user's phone) and sent
        // everything else to loadExtractor, which has no extractor for
        // modiplay / vidout / iqsmartgames — so no link was ever emitted.
        // Phisher's resolves modiplay (Cineverse) + vidout, which is
        // where the working streams live (vidhide CDN via EarnVids, and
        // the Watchout2025 GitHub API for vidout).
        for ((u, label) in servers) {
            try {
                val host = hostOf(u)
                when {
                    host.contains("modiplay") -> {
                        if (resolveModiplay(u, label, subtitleCallback, callback)) found = true
                    }
                    host.contains("vidout") -> {
                        if (resolveVidout(u, label, subtitleCallback, callback)) found = true
                    }
                    u.contains("vidrift.net") -> {
                        if (resolveVidrift(u, isTv, subtitleCallback, callback)) found = true
                    }
                    u.contains("filmu.in") || u.contains("vidly.pro") -> {
                        if (resolveFilmu(u, isTv, subtitleCallback, callback)) found = true
                    }
                }
            } catch (e: Exception) {
                // one dead server must not kill the rest
            }
        }
        // Fallback: everything not handled above via loadExtractor, then
        // by scraping .m3u8/.mp4 links out of the embed HTML.
        for ((u, label) in servers) {
            val host = hostOf(u)
            if (host.contains("modiplay") || host.contains("vidout") ||
                u.contains("vidrift.net") || u.contains("filmu.in") || u.contains("vidly.pro")
            ) continue
            try {
                if (loadExtractor(u, subtitleCallback, callback)) {
                    found = true
                    continue
                }
            } catch (e: Exception) {
                // fall through to the HTML scrape
            }
            try {
                val embedHtml = app.get(u, headers = browserHeaders + mapOf("Referer" to "$mainUrl/")).text
                if (extractM3u8Links(embedHtml, u, label, callback)) found = true
            } catch (e: Exception) {
                // best effort
            }
        }
        return found
    }

    // ---- v4: Phisher-style resolution (modiplay + vidout) ----

    private fun hostOf(url: String): String {
        return try {
            java.net.URI(url).host?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun originOf(url: String): String {
        return try {
            val u = java.net.URI(url)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            ""
        }
    }

    private fun absUrl(base: String, url: String): String {
        val u = url.trim()
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        val b = base.trimEnd('/')
        return if (u.startsWith("/")) "$b$u" else "$b/$u"
    }

    // Unescape a URL found in JS/HTML: \/ -> /, \" -> ", &amp; -> &.
    private fun deEsc(s: String): String {
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("&amp;", "&")
    }

    private fun qualityFromName(name: String): Int {
        return when {
            name.contains("2160", ignoreCase = true) || name.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            name.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            name.contains("720", ignoreCase = true) -> Qualities.P720.value
            name.contains("480", ignoreCase = true) -> Qualities.P480.value
            name.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // Dean Edwards JS packer unpacker (port of Phisher's JsPacker).
    // The modiplay sub-embeds (EarnVids etc.) pack their stream URLs in
    // eval(function(p,a,c,k,e,d){...}('payload',36,528,'k1|k2|...'.split('|'))).
    private object JsPacker {
        private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        private fun baseN(num: Int, base: Int): String {
            if (num == 0) return "0"
            var temp = num
            val sb = StringBuilder()
            while (temp > 0) {
                sb.append(CHARS[temp % base])
                temp /= base
            }
            return sb.reverse().toString()
        }

        fun unpack(p: String, a: Int, c: Int, k: List<String>): String {
            var payload = p
            for (i in c - 1 downTo 0) {
                if (i < k.size && k[i].isNotEmpty()) {
                    payload = Regex("\\b" + Regex.escape(baseN(i, a)) + "\\b")
                        .replace(payload) { k[i] }
                }
            }
            return payload
        }

        // Find the packed eval(...) in `html` and unpack it. Returns null
        // when there is no packed JS.
        fun parseAndUnpack(html: String): String? {
            var startIdx = html.indexOf("eval(function(p,a,c,k,e,d)")
            if (startIdx == -1) startIdx = html.indexOf("function(p,a,c,k,e,d)")
            if (startIdx == -1) return null
            val openBraceIdx = html.indexOf("{", startIdx)
            if (openBraceIdx == -1) return null
            var braceCount = 1
            var j = openBraceIdx + 1
            while (j < html.length && braceCount > 0) {
                if (html[j] == '{') braceCount++
                else if (html[j] == '}') braceCount--
                j++
            }
            val argsStartIdx = html.indexOf("(", j - 1)
            if (argsStartIdx == -1) return null
            var argsParenCount = 1
            var kIdx = argsStartIdx + 1
            while (kIdx < html.length && argsParenCount > 0) {
                if (html[kIdx] == '(') argsParenCount++
                else if (html[kIdx] == ')') argsParenCount--
                kIdx++
            }
            val argsStr = html.substring(argsStartIdx + 1, kIdx - 1).trim()
            if (argsStr.isEmpty()) return null
            val startChar = argsStr[0]
            val payload = StringBuilder()
            var i = 1
            while (i < argsStr.length) {
                if (argsStr[i] == startChar) {
                    // An even backslash count means this quote is NOT
                    // escaped, so the payload ends here.
                    var backslashCount = 0
                    var m = i - 1
                    while (m >= 0 && argsStr[m] == '\\') {
                        backslashCount++
                        m--
                    }
                    if (backslashCount % 2 == 0) break
                }
                payload.append(argsStr[i])
                i++
            }
            val unescapedPayload = payload.toString()
                .replace("\\" + startChar, startChar.toString())
                .replace("\\\\", "\\")
            val rest = argsStr.substring(i + 1)
            val restQuoteMatch = Regex("[\"']").find(rest) ?: return null
            val quotePos = restQuoteMatch.range.first
            val restQuoteChar = restQuoteMatch.value
            val ints = Regex("\\b\\d+\\b").findAll(rest.substring(0, quotePos))
                .map { it.value.toIntOrNull() ?: 0 }.toList()
            if (ints.size < 2) return null
            val a = ints[0]
            val c = ints[1]
            val keysStr = StringBuilder()
            var jj = quotePos + 1
            while (jj < rest.length) {
                if (rest[jj].toString() == restQuoteChar) {
                    var backslashCount2 = 0
                    var b = jj - 1
                    while (b >= 0 && rest[b] == '\\') {
                        backslashCount2++
                        b--
                    }
                    if (backslashCount2 % 2 == 0) break
                }
                keysStr.append(rest[jj])
                jj++
            }
            val keys = keysStr.toString()
                .replace("\\" + restQuoteChar, restQuoteChar)
                .replace("\\\\", "\\")
                .split("|")
            return unpack(unescapedPayload, a, c, keys)
        }
    }

    // Resolve ONE modiplay sub-embed (EarnVids, StreamHG, ...) by
    // unpacking its packed JS and emitting the hls2/hls3/hls4 masters.
    // Returns true if at least one link was emitted.
    private suspend fun resolvePackerEmbed(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        referer: String,
    ): Boolean {
        return try {
            val resp = try {
                app.get(embedUrl, headers = browserHeaders + mapOf("Referer" to referer))
            } catch (e: Exception) {
                return false
            }
            val html = resp.text
            // The embed may redirect (minochinos.com -> callistanise.com);
            // the stream's Referer must be the FINAL page's origin.
            val base = originOf(resp.url)
            if (base.isBlank()) return false
            val unpacked = JsPacker.parseAndUnpack(html) ?: return false
            val hls = listOf(
                Regex("\"hls2\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.getOrNull(1),
                Regex("\"hls3\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.getOrNull(1),
                Regex("\"hls4\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.getOrNull(1),
            ).mapNotNull { it?.takeIf { v -> v.isNotBlank() }?.let { v -> deEsc(v) } }
                .map { if (it.startsWith("http")) it else absUrl(base, it) }
                .distinct()
            var found = false
            for (master in hls) {
                try {
                    callback(
                        newExtractorLink(
                            source = "MultiMovies",
                            name = label,
                            url = master,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.quality = qualityFromName(label)
                            this.referer = base
                            this.headers = playbackHeaders(base)
                        }
                    )
                    found = true
                } catch (e: Exception) {
                    // one bad hls must not kill the rest
                }
            }
            // Subtitles: file: "https://..._eng.vtt" in the unpacked JS.
            try {
                val seen = mutableSetOf<String>()
                for (m in Regex("file\\s*:\\s*\"(https?[^\"]+\\.vtt)\"").findAll(unpacked)) {
                    val url = deEsc(m.groupValues[1])
                    if (!seen.add(url)) continue
                    val lang = url.substringAfterLast('_', "").substringBefore('.', "")
                        .takeIf { it.isNotBlank() }?.uppercase() ?: "Subtitle"
                    subtitleCallback(SubtitleFile(lang, url))
                }
            } catch (e: Exception) {
                // subtitles are optional
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    // Modiplay (Cineverse) embed: https://<host>.modiplay.xyz/embed/tmdb/...
    // The page lists its sub-servers as <div class="srv-item">
    // data-url / data-platform / data-pname / data-code. Each sub-embed
    // is resolved via the packer (EarnVids etc. carry the real stream).
    // NOTE: Phisher extracts the servers with a switchServer('...')
    // regex, but the live page uses data-* attributes +
    // switchServerFromEl(this) — that regex matches nothing today, so we
    // read the data-* attributes instead.
    private suspend fun resolveModiplay(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val html = try {
                app.get(embedUrl, headers = browserHeaders + mapOf("Referer" to "$mainUrl/")).text
            } catch (e: Exception) {
                return false
            }
            val servers = Regex(
                "data-url=\"([^\"]+)\"[^>]*?data-platform=\"([^\"]+)\"[^>]*?data-pname=\"([^\"]+)\"[^>]*?data-code=\"([^\"]+)\""
            ).findAll(html).map { m ->
                // (embed, platform, name, code)
                listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            }.distinctBy { it[3] }.toList()
            var found = false
            for (srv in servers) {
                val subEmbed = srv[0].trim()
                val subLabel = srv[2].trim().takeIf { it.isNotBlank() } ?: label
                if (subEmbed.isBlank()) continue
                // Filemoon gives only a code (not a URL) and Bingr a
                // relative proxy.php — the packer cannot fetch those.
                if (!subEmbed.startsWith("http")) continue
                try {
                    if (resolvePackerEmbed(subEmbed, subLabel, subtitleCallback, callback, embedUrl)) {
                        found = true
                    }
                } catch (e: Exception) {
                    // one dead sub-server must not kill the rest
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    // Vidout embed: https://vidout.pages.dev/movie/<id> or
    // /tv/<tmdb>/s<S>/e<E>. The stream comes from a public GitHub API
    // (Watchout2025/api) — no JS, no packer. Falls back to letting
    // CloudStream's extractors try the vidy.st page.
    private suspend fun resolveVidout(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val vidoutReferer = "https://vidout.pages.dev/"
        return try {
            val tvMatch = Regex("""/tv/(\d+)/(?:s(\d+)|(\d+))/(?:e(\d+)|(\d+))""", RegexOption.IGNORE_CASE).find(embedUrl)
            val movieMatch = if (tvMatch == null) {
                Regex("""/movie/(tt\d+|\d+)""", RegexOption.IGNORE_CASE).find(embedUrl)
            } else null
            var streamUrl: String? = null
            var fallback: String? = null
            if (tvMatch != null) {
                val tmdbId = tvMatch.groupValues[1]
                val season = tvMatch.groupValues[2].ifEmpty { tvMatch.groupValues[3] }.toIntOrNull() ?: 1
                val episode = tvMatch.groupValues[4].ifEmpty { tvMatch.groupValues[5] }.toIntOrNull() ?: 1
                fallback = "https://vidy.st/tv/$tmdbId/$season/$episode"
                try {
                    val apiUrl = "https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main/hls/tv/$tmdbId/S$season.json"
                    val body = app.get(apiUrl, headers = browserHeaders).text
                    streamUrl = Regex("\"" + episode + "\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                } catch (e: Exception) {
                    streamUrl = null
                }
            } else if (movieMatch != null) {
                val id = movieMatch.groupValues[1]
                fallback = "https://vidy.st/movie/$id"
                try {
                    val apiUrl = "https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main/hls/movie/$id"
                    val body = app.get(apiUrl, headers = browserHeaders).text.trim()
                    streamUrl = if (body.startsWith("http")) body else null
                } catch (e: Exception) {
                    streamUrl = null
                }
            } else {
                return false
            }
            if (streamUrl != null) {
                try {
                    callback(
                        newExtractorLink(
                            source = "MultiMovies",
                            name = label,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = vidoutReferer
                            this.headers = playbackHeaders(vidoutReferer)
                        }
                    )
                    return true
                } catch (e: Exception) {
                    // fall through to the vidy.st fallback
                }
            }
            // Fallback: let CloudStream's extractors try the vidy.st page.
            if (fallback != null) {
                try {
                    if (loadExtractor(fallback, subtitleCallback, callback)) return true
                } catch (e: Exception) {
                    // best effort
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    // Last-resort fallback: scrape direct .m3u8 / .mp4 URLs out of an
    // embed page and emit them with the embed as Referer.
    private suspend fun extractM3u8Links(
        html: String,
        base: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        val re = Regex("""https?://[^\s"'\\]+\.(?:m3u8|mp4)(?:[^\s"'\\]*)""")
        val seen = mutableSetOf<String>()
        for (m in re.findAll(html)) {
            val url = m.value.trim().trimEnd('.', ',', ';')
            if (url.isBlank() || !seen.add(url)) continue
            val isM3u8 = url.substringBefore("?").lowercase().endsWith(".m3u8")
            try {
                callback(
                    newExtractorLink(
                        source = "MultiMovies",
                        name = label,
                        url = url,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = base
                        this.headers = playbackHeaders(base)
                    }
                )
                found = true
            } catch (e: Exception) {
                // one bad link must not kill the rest
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
            // Vidrift subtitles.json is an object: {"tracks": [{...}]}
            try {
                val obj = ObjectMapper().readValue(
                    json,
                    object : TypeReference<Map<String, Any?>>() {}
                )
                val list = (obj["tracks"] as? List<*>) ?: (obj["subtitles"] as? List<*>)
                list?.filterIsInstance<Map<String, Any?>>()
            } catch (e2: Exception) {
                null
            }
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
            // Subtitle track URLs are relative (/api/orion/.../*.vtt) —
            // resolve them against the embed host like the stream URLs.
            val parsed = parseSubtitleListJson(json)?.map { entry ->
                val u = (entry["url"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (entry["file"] as? String)?.takeIf { it.isNotBlank() }
                if (u != null && !u.startsWith("http")) entry + ("url" to "$vidriftBase$u") else entry
            }
            emitSubtitles(parsed, subtitleCallback)
        } catch (e: Exception) {
            // subtitles are optional
        }
    }

    // v3 (2026-11-11): per-variant links + Referer (Anikoto v3 emission).
    // v2 emitted ONE bare master.m3u8 per orion stream / Filmu HLS link
    // and the phone returned "No streams found" — the exact Anikoto v2
    // bug. Anikoto v3 proved the fix on the phone: expand the master
    // playlist into one link per quality variant (1080p/720p/480p), each
    // carrying the Referer header (the segments 403 without it).
    private fun qualityFromHeight(height: Int?): Int {
        return when {
            height == null -> Qualities.P1080.value
            height >= 2160 -> Qualities.P2160.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 480 -> Qualities.P480.value
            height >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun playbackHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to (browserHeaders["User-Agent"] ?: ""),
        "Referer" to referer,
    )

    /**
     * Fetch a master playlist and emit ONE link per quality variant — the
     * Anikoto v3 expandMaster behaviour. Each variant URL is resolved
     * relative to the master URL and carries the Referer header. Returns
     * true if at least one link was emitted. If the URL is not a master
     * playlist (no #EXT-X-STREAM-INF — e.g. a direct .mp4 or a media
     * playlist), it is emitted as-is as a single link. Never throws.
     */
    private suspend fun expandMaster(
        masterUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val text = app.get(masterUrl, headers = playbackHeaders(referer)).text
            val lines = text.lines()
            var emitted = false
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    val quality = qualityFromHeight(height)
                    var j = i + 1
                    var variant: String? = null
                    while (j < lines.size) {
                        val v = lines[j].trim()
                        if (v.isNotEmpty() && !v.startsWith("#")) {
                            variant = v
                            break
                        }
                        j++
                    }
                    if (variant != null) {
                        val abs = try {
                            URL(URL(masterUrl), variant).toString()
                        } catch (e: Exception) {
                            variant
                        }
                        try {
                            callback(
                                newExtractorLink(
                                    source = "MultiMovies",
                                    name = label,
                                    url = abs,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.quality = quality
                                    this.referer = referer
                                    this.headers = playbackHeaders(referer)
                                }
                            )
                            emitted = true
                        } catch (e: Exception) {
                            // This variant failed — try the next one.
                        }
                        i = j + 1
                        continue
                    }
                }
                i++
            }
            if (!emitted) {
                // Not a master playlist — emit the URL itself as one link.
                try {
                    callback(
                        newExtractorLink(
                            source = "MultiMovies",
                            name = label,
                            url = masterUrl,
                            type = masterUrl.toExtractorType()
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = referer
                            this.headers = playbackHeaders(referer)
                        }
                    )
                    emitted = true
                } catch (e: Exception) {
                    // Nothing could be emitted.
                }
            }
            emitted
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun emitOrionStream(
        stream: OrionStream,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val rawUrl = stream.url?.takeIf { it.isNotBlank() }
            ?: stream.proxyUrl?.takeIf { it.isNotBlank() }
            ?: return false
        // orionStreams URLs are relative (/api/orion/<hash>/master.m3u8) —
        // resolve against the embed host or the player gets an unusable URL.
        val fileUrl = if (rawUrl.startsWith("http")) rawUrl else "$vidriftBase$rawUrl"
        val name = "MultiMovies Vidrift ${stream.name?.trim() ?: "Video"}".trim()
        // Master playlists (.m3u8) expand into one link per quality
        // variant (Anikoto v3); direct files (.mp4) emit as-is — fetching
        // an mp4 as text would download the whole file into memory.
        if (fileUrl.substringBefore("?").lowercase().endsWith(".m3u8")) {
            return expandMaster(fileUrl, name, embedUrl, callback)
        }
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
                try {
                    if (emitOrionStream(stream, embedUrl, callback)) found = true
                } catch (e: Exception) {
                    // one bad stream must not kill the rest
                }
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
                try {
                    if (emitOrionStream(stream, embedUrl, callback)) found = true
                } catch (e: Exception) {
                    // one bad stream must not kill the rest
                }
                fetchOrionSubtitles(stream.subtitleListUrl, embedUrl, subtitleCallback)
            }
            found
        } catch (e: Exception) {
            // CF-403 / network failure on the API — best effort only.
            found
        }
    }

    // Unescape a bridge/stream URL found in page HTML: JSON-escaped
    // \u0026 and HTML-escaped &amp; / &quot; both occur on vidly pages.
    private fun unescapePageUrl(raw: String): String {
        return raw.replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()
    }

    // Emit any Filmu HLS links found in `html` (bridge + streamrip).
    // Returns true if at least one link was emitted. Never throws.
    private suspend fun emitFilmuLinks(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        // Signed HLS bridge URL. Several occurrences can exist (JSON-escaped
        // \u0026 form and HTML-escaped &amp; form); unescape all and keep
        // the longest (most complete) signed URL.
        try {
            val bridgeRe = Regex("""https://dash-hls-bridge\.johndoesdmc\.workers\.dev/(?:\\u0026|[^"\\])*""")
            val bridge = bridgeRe.findAll(html)
                .map { unescapePageUrl(it.value) }
                .filter { it.isNotBlank() }
                .maxByOrNull { it.length }
            if (bridge != null) {
                // The bridge serves an HLS master — expand it into one
                // link per quality variant (Anikoto v3); if it is not a
                // master playlist, expandMaster emits it as-is.
                if (expandMaster(bridge, "MultiMovies Filmu HLS", referer, callback)) {
                    found = true
                }
            }
        } catch (e: Exception) {
            // a bad bridge link must not kill the streamrip fallback
        }

        // streamrip master m3u8 (also checked on TV pages as a fallback).
        try {
            val ripRe = Regex("""https://proxy\.streamrip\.fun/m/[^\s"\\]+""")
            val rip = ripRe.find(html)?.value?.takeIf { it.isNotBlank() }
            if (rip != null) {
                if (expandMaster(rip, "MultiMovies Filmu HLS", referer, callback)) {
                    found = true
                }
            }
        } catch (e: Exception) {
            // best effort
        }
        return found
    }

    // Filmu embed: https://embed.filmu.in/movie|tv/... 301s to vidly.pro.
    // Movie pages contain a proxy.streamrip.fun master m3u8; TV pages bake
    // a SIGNED dash-hls-bridge URL (JSON-escaped \u0026 = & — must be
    // unescaped before use; the unsigned URL 502s). Viruman (movie) also
    // carries a signed bridge URL, so both regexes run on every page.
    private suspend fun resolveFilmu(
        embedUrl: String,
        isTv: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            var found = false
            // 1) Fetch the embed (301s to vidly.pro) and extract links.
            var html: String? = null
            var referer = embedUrl
            try {
                val resp = app.get(embedUrl, headers = browserHeaders + mapOf("Referer" to "$mainUrl/"))
                html = resp.text
                referer = resp.url
            } catch (e: Exception) {
                html = null
            }
            if (html != null) {
                found = emitFilmuLinks(html, referer, callback) || found
            }
            // 2) Fallback: if nothing found, fetch vidly.pro directly and
            // re-run the extraction (the embed 301 may not be followed
            // in-app, or the page may differ).
            if (!found) {
                val vidlyUrl = embedUrl.replace("embed.filmu.in", "vidly.pro")
                if (vidlyUrl != embedUrl) {
                    try {
                        val resp2 = app.get(vidlyUrl, headers = browserHeaders + mapOf("Referer" to "$mainUrl/"))
                        found = emitFilmuLinks(resp2.text, resp2.url, callback) || found
                    } catch (e: Exception) {
                        // best effort
                    }
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }
}
