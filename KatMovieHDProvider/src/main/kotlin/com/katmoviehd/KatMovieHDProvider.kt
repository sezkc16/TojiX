package com.katmoviehd

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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class KatMovieHDProvider : MainAPI() {

    override var mainUrl = "https://new.katmoviehd.top"
    override var name = "KatMovieHD"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    // ---- SvelteKit JSON models (keys are quoted by quoteJsKeys() first) ----
    // Same ecosystem as KatDrama: SvelteKit SPA, data embedded as a JS object
    // literal after </html>:
    //   __sveltekit_<hash>.resolve({id:1,data:{success:true,data:{items:[...]}}})
    // The __sveltekit_ suffix hash changes per build — parsing never depends on it.

    data class MovieItem(
        @JsonProperty("post_title") val postTitle: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumbnail_image") val thumbnailImage: String? = null,
        @JsonProperty("categories") val categories: List<String>? = null,
        @JsonProperty("created") val created: String? = null,
    )

    data class ListData(
        @JsonProperty("items") val items: List<MovieItem>? = null,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
    )

    // Episode data for series episodes (passed to loadLinks as a JSON string)
    data class EpisodeData(
        @JsonProperty("playId") val playId: String? = null,
        @JsonProperty("f1080") val f1080: String? = null,
        @JsonProperty("f720") val f720: String? = null,
        @JsonProperty("f480") val f480: String? = null,
    )

    // One download link on a movie detail page (HDHub4u v3 LIST pattern:
    // every quality variant stays visible with its full label + size).
    data class LinkEntry(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("host") val host: String? = null,   // "kmhd" | "hubcloud" | "gdflix"
        @JsonProperty("id") val id: String? = null,
    )

    // Movie data passed to loadLinks (as a JSON string)
    data class MovieData(
        @JsonProperty("playId") val playId: String? = null,
        @JsonProperty("links") val links: List<LinkEntry>? = null,
    )

    // ---- JS object-literal -> JSON helpers (same as KatDrama) ----

    private fun extractBalanced(text: String, startMarker: String, open: Char, close: Char): String? {
        val start = text.indexOf(startMarker)
        if (start < 0) return null
        var idx = start + startMarker.length
        // skip whitespace
        while (idx < text.length && text[idx].isWhitespace()) idx++
        if (idx >= text.length || text[idx] != open) return null
        var depth = 0
        var inStr = false
        var esc = false
        val begin = idx
        while (idx < text.length) {
            val c = text[idx]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                if (c == '"') inStr = true
                else if (c == open) depth++
                else if (c == close) {
                    depth--
                    if (depth == 0) return text.substring(begin, idx + 1)
                }
            }
            idx++
        }
        return null
    }

    private fun quoteJsKeys(js: String): String {
        // Quote unquoted object keys, skipping string contents.
        val out = StringBuilder()
        var i = 0
        var inStr = false
        var esc = false
        while (i < js.length) {
            val c = js[i]
            if (inStr) {
                out.append(c)
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                i++
            } else {
                if (c == '"') {
                    inStr = true
                    out.append(c)
                    i++
                } else if (c == '{' || c == ',') {
                    // look ahead for an unquoted key
                    var j = i + 1
                    while (j < js.length && js[j].isWhitespace()) j++
                    val kStart = j
                    while (j < js.length && (js[j].isLetterOrDigit() || js[j] == '_' )) j++
                    var kEnd = j
                    while (kEnd < js.length && js[kEnd].isWhitespace()) kEnd++
                    if (j > kStart && kEnd < js.length && js[kEnd] == ':') {
                        out.append(c)
                        out.append('"').append(js.substring(kStart, j)).append('"').append(':')
                        i = kEnd + 1
                    } else {
                        out.append(c)
                        i++
                    }
                } else {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun parseListData(html: String): ListData? {
        // The list payload lives in: __sveltekit_*.resolve({id:1,data:{success:true,data:{items:[...],page:1,...}}})
        // The object right after "data:{success:true,data:" IS the list data:
        // {items:[...],page:1,perPage:12,totalItems:...,totalPages:...}
        val marker = "data:{success:true,data:"
        if (!html.contains(marker)) return null
        val obj = extractBalanced(html, marker, '{', '}') ?: return null
        return tryParseJson<ListData>(quoteJsKeys(obj))
    }

    private fun parsePostContent(html: String): String? {
        // Detail page payload: data:{success:true,data:{post_content:"<escaped html>",...}}
        val marker = "post_content:\""
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        var i = idx + marker.length
        val sb = StringBuilder()
        var esc = false
        while (i < html.length) {
            val c = html[i]
            if (esc) {
                sb.append(c)
                esc = false
            } else if (c == '\\') {
                sb.append(c)
                esc = true
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
            }
            i++
        }
        // sb is the JSON-escaped HTML string; unescape it via Jackson
        val decoded = tryParseJson<String>("\"" + sb.toString() + "\"") ?: return null
        return org.jsoup.parser.Parser.unescapeEntities(decoded, false)
    }

    private fun parsePlayInfo(html: String): Map<String, PlayInfo> {
        // Play page payload: data:{_id:"...",name:"...",info:{Other_xxxx:{name:"...",streamtape_res:"...",streamwish_res:"..."},...}}
        val obj = extractBalanced(html, "info:", '{', '}') ?: return emptyMap()
        val parsed = tryParseJson<Map<String, PlayInfo>>(quoteJsKeys(obj)) ?: return emptyMap()
        return parsed
    }

    data class PlayInfo(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("streamtape_res") val streamtapeRes: String? = null,
        @JsonProperty("streamwish_res") val streamwishRes: String? = null,
    )

    private fun toJsonString(value: Any): String {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value)
        } catch (e: Exception) {
            ""
        }
    }

    private fun qualityFromLabel(label: String?): Int {
        val l = label ?: return Qualities.Unknown.value
        return when {
            l.contains("2160", true) || l.contains("4k", true) -> Qualities.P2160.value
            l.contains("1080", true) -> Qualities.P1080.value
            l.contains("720", true) -> Qualities.P720.value
            l.contains("480", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    // ---- Main page / search ----

    private fun isSeriesItem(item: MovieItem): Boolean {
        val title = item.postTitle ?: ""
        if (title.contains("Season", true)) return true
        val cats = item.categories ?: return false
        return cats.any {
            it.equals("tv-series-dubbed", true) || it.equals("series", true) ||
                it.equals("tv-series", true) || it.equals("web-series", true)
        }
    }

    private fun itemsToSearchResponses(items: List<MovieItem>): List<com.lagradost.cloudstream3.SearchResponse> {
        return items.mapNotNull { item ->
            val title = item.postTitle?.replace("&amp;", "&") ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            // No trailing slash: the site 308s trailing-slash URLs to no-slash
            val url = "$mainUrl/$slug"
            if (isSeriesItem(item)) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = item.thumbnailImage
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = item.thumbnailImage
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // request.data is the category slug ("" = homepage / latest)
        // Note: category URLs use NO trailing slash ("?page=" directly after
        // the slug) so the 308 redirect never drops the page parameter
        // (verified: /category/hindi-dubbed?page=1 vs ?page=2 differ).
        val cat = request.data
        val url = if (cat.isNullOrEmpty()) {
            "$mainUrl/?page=$page"
        } else {
            "$mainUrl/category/$cat?page=$page"
        }
        val html = app.get(url).text
        val data = parseListData(html)
        val items = data?.items ?: emptyList()
        val title = if (cat.isNullOrEmpty()) "Latest" else cat.replace("-", " ").replaceFirstChar { it.uppercase() }
        return newHomePageResponse(
            listOf(HomePageList(title, itemsToSearchResponses(items), true))
        )
    }

    // Categories shown on the homepage (slugs verified against categories.txt
    // and live: /category/<slug> returns 200 for each of these).
    override val mainPage = mainPageOf(
        "" to "Latest",
        "hindi-dubbed" to "Hindi Dubbed",
        "dual-audio" to "Dual Audio",
        "bollywood" to "Bollywood",
        "hollywood-eng" to "Hollywood",
        "tv-series-dubbed" to "TV Series",
        "korean-drama" to "Korean Drama",
        "anime-dubbed" to "Anime",
        "south" to "South",
        "4k-uhd" to "4K UHD",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val html = app.get("$mainUrl/?q=${query.encodeUri()}").text
        val data = parseListData(html)
        val items = data?.items ?: return null
        return itemsToSearchResponses(items).toNewSearchResponseList()
    }

    // ---- Load (detail page -> movie links / series episodes) ----

    // Browser-like headers: links.kmhd.me / HubCloud may block the app's
    // default client, so every request to those hosts sends a real UA.
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    data class PackResult(val episodes: Map<Int, String>, val season: Int?)

    // A "complete season" post lists packs: links.kmhd.me/pack/<id> per quality.
    // The pack page embeds info:{<fileId>:{name:"... S04E01 ..."}} for every episode.
    // Returns episode number -> file id, plus the season parsed from the names.
    private suspend fun fetchPackEpisodes(packId: String): PackResult {
        val html = try { app.get("https://links.kmhd.me/pack/$packId", headers = browserHeaders).text } catch (e: Exception) { return PackResult(emptyMap(), null) }
        val out = mutableMapOf<Int, String>()
        var season: Int? = null
        // Entries look like: House_6b70e88c:{name:"House of Cards S04E01 480p ..."}
        val entryRe = Regex("([A-Za-z0-9_]+):\\{name:\"([^\"]+)\"\\}")
        for (m in entryRe.findAll(html)) {
            val fileId = m.groupValues[1]
            val name = m.groupValues[2]
            val seMatch = Regex("(?i)S(\\d+)E(\\d+)").find(name)
            val epNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("(?i)E(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            if (season == null) season = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            out[epNum] = fileId
        }
        return PackResult(out, season)
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("title")?.text()?.replace("&amp;", "&") ?: return null

        // Poster: og:image first, else the first image in the post content
        val contentForPoster = parsePostContent(html)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: contentForPoster?.let { Jsoup.parse(it).selectFirst("img")?.attr("src") }

        // "Watch Online" play id: https://links.kmhd.me/play?id=XXXX
        val playId = Regex("links\\.kmhd\\.me/play\\?id=([a-zA-Z0-9]+)")
            .find(html)?.groupValues?.getOrNull(1)

        val content = contentForPoster ?: return null
        val cdoc = Jsoup.parse(content)

        val seasonFromTitle = Regex("(?i)Season\\s+(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val isSeriesTitle = title.contains("Season", true) || url.contains("season", true)

        val episodes = mutableListOf<Episode>()

        // ---- 1) Pack pages (series) — MUST run before the movie fallback,
        // because the truncated pack file links would otherwise be mistaken
        // for a movie's download links. ----
        val packs = mutableListOf<Pair<String, String>>() // (quality, packId)
        for (el in cdoc.select("a")) {
            val href = el.attr("href")
            if (href.contains("links.kmhd.me/pack/")) {
                val packId = href.substringAfterLast("/").trim()
                val label = el.text().trim()
                val quality = when {
                    label.contains("2160", true) -> "2160"
                    label.contains("1080", true) -> "1080"
                    label.contains("720", true) -> "720"
                    label.contains("480", true) -> "480"
                    else -> "?"
                }
                if (packId.isNotEmpty()) packs.add(quality to packId)
            }
        }
        if (packs.isNotEmpty()) {
            // episode -> (f1080, f720, f480)
            val byEp = mutableMapOf<Int, Triple<String?, String?, String?>>()
            var packSeason: Int? = null
            for ((quality, packId) in packs) {
                val res = fetchPackEpisodes(packId)
                if (packSeason == null) packSeason = res.season
                for ((epNum, fileId) in res.episodes) {
                    val cur = byEp[epNum] ?: Triple(null, null, null)
                    byEp[epNum] = when (quality) {
                        "1080", "2160" -> cur.copy(first = fileId)
                        "720" -> cur.copy(second = fileId)
                        "480" -> cur.copy(third = fileId)
                        else -> if (cur.first == null) cur.copy(first = fileId) else cur
                    }
                }
            }
            val season = packSeason ?: seasonFromTitle ?: 1
            for ((epNum, triple) in byEp.toSortedMap()) {
                val (f1080p, f720p, f480p) = triple
                if (f1080p == null && f720p == null && f480p == null) continue
                val data = EpisodeData(playId, f1080p, f720p, f480p)
                episodes.add(
                    newEpisode(toJsonString(data)) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = season
                        this.posterUrl = poster
                    }
                )
            }
        }

        // ---- 2) "Episode NN" headings (KatDrama pattern) ----
        // Episodes are grouped as: <p>Episode NN</p> then links
        // (1080p / 720p / 480p) -> links.kmhd.me/file/<id>
        if (episodes.isEmpty()) {
            var currentEp: Int? = null
            var f1080: String? = null
            var f720: String? = null
            var f480: String? = null
            fun flush() {
                if (currentEp != null && (f1080 != null || f720 != null || f480 != null)) {
                    val data = EpisodeData(playId, f1080, f720, f480)
                    episodes.add(
                        newEpisode(toJsonString(data)) {
                            this.name = "Episode $currentEp"
                            this.episode = currentEp
                            this.season = seasonFromTitle ?: 1
                            this.posterUrl = poster
                        }
                    )
                    f1080 = null; f720 = null; f480 = null
                }
            }
            for (el in cdoc.body().allElements) {
                val text = el.text().trim()
                // Episode heading: "Episode 01" (text may be nested in <b><span>)
                val epMatch = Regex("(?i)^Episode\\s+(\\d+)").find(text)
                if (el.tagName() == "p" && epMatch != null) {
                    flush()
                    currentEp = epMatch.groupValues[1].toIntOrNull()
                    continue
                }
                if (el.tagName() == "a") {
                    val href = el.attr("href")
                    if (href.contains("links.kmhd.me/file/")) {
                        val fileId = href.substringAfterLast("/").trim()
                        // Skip the truncated pack file links (end with "-")
                        if (fileId.isEmpty() || fileId.endsWith("-")) continue
                        val label = el.text().trim()
                        when {
                            label.contains("1080", true) -> f1080 = fileId
                            label.contains("720", true) -> f720 = fileId
                            label.contains("480", true) -> f480 = fileId
                            else -> if (f1080 == null) f1080 = fileId
                        }
                    }
                }
            }
            flush()
        }

        // ---- 3) Movie download links (every quality variant + size) ----
        // Each quality is an <h2> holding ONE <a>: the label (e.g.
        // "480p Links [337MB]") is the anchor text, the href is the link.
        // Hosts: links.kmhd.me/file/<id>, hubcloud.pw/drive/<code>,
        // gdflix.vip/file/<id>. The ": DOWNLOAD LINKS :" anchor points to
        // kmhd.net/directlink (ad page) and is ignored, as are screenshots.
        val movieLinks = mutableListOf<LinkEntry>()
        for (el in cdoc.select("a")) {
            val href = el.attr("href")
            val label = el.text().trim()
            when {
                href.contains("links.kmhd.me/file/") -> {
                    val fileId = href.substringAfterLast("/").trim()
                    // The pack file links are TRUNCATED in the source HTML
                    // itself (end with a dash) — they are broken, skip them.
                    if (fileId.isEmpty() || fileId.endsWith("-")) continue
                    if (label.isNotEmpty()) movieLinks.add(LinkEntry(label, "kmhd", fileId))
                }
                href.contains("hubcloud.pw/drive/") -> {
                    val code = href.substringAfterLast("/").trim()
                    if (code.isNotEmpty() && label.isNotEmpty()) movieLinks.add(LinkEntry(label, "hubcloud", code))
                }
                href.contains("gdflix.vip/file/") -> {
                    val id = href.substringAfterLast("/").trim()
                    if (id.isNotEmpty() && label.isNotEmpty()) movieLinks.add(LinkEntry(label, "gdflix", id))
                }
            }
        }

        if (episodes.isEmpty() && movieLinks.isNotEmpty()) {
            if (isSeriesTitle) {
                // Series with per-quality file links but no episode structure:
                // a single episode carrying the 3 quality file ids.
                var f1080: String? = null
                var f720: String? = null
                var f480: String? = null
                for (entry in movieLinks) {
                    if (entry.host != "kmhd") continue
                    val label = entry.label ?: ""
                    when {
                        label.contains("1080", true) -> if (f1080 == null) f1080 = entry.id
                        label.contains("720", true) -> if (f720 == null) f720 = entry.id
                        label.contains("480", true) -> if (f480 == null) f480 = entry.id
                        else -> if (f1080 == null) f1080 = entry.id
                    }
                }
                if (f1080 != null || f720 != null || f480 != null) {
                    val data = EpisodeData(playId, f1080, f720, f480)
                    episodes.add(
                        newEpisode(toJsonString(data)) {
                            this.name = title
                            this.episode = 1
                            this.season = seasonFromTitle ?: 1
                            this.posterUrl = poster
                        }
                    )
                }
            } else {
                // Movie: one response carrying the full link list
                val data = MovieData(playId, movieLinks)
                return newMovieLoadResponse(title, url, TvType.Movie, toJsonString(data)) {
                    this.posterUrl = poster
                }
            }
        }

        // ---- 4) Watch Online only fallback ----
        if (episodes.isEmpty() && playId != null) {
            return if (isSeriesTitle) {
                val data = EpisodeData(playId, null, null, null)
                val eps = listOf(
                    newEpisode(toJsonString(data)) {
                        this.name = title
                        this.episode = 1
                        this.season = seasonFromTitle ?: 1
                        this.posterUrl = poster
                    }
                )
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                    this.posterUrl = poster
                }
            } else {
                val data = MovieData(playId, emptyList())
                newMovieLoadResponse(title, url, TvType.Movie, toJsonString(data)) {
                    this.posterUrl = poster
                }
            }
        }

        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    // ---- Load links (episode/movie -> hosts) ----

    // The site's public API gives a file's upload links directly, no unlock
    // page or session cookie needed. Token is the site's own public JWT
    // (embedded in every links.kmhd.me page, expires 2027) — the same
    // token KatDrama uses, since both sites share the links.kmhd.me API.
    private val apiToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhZG1pbiIsImV4cCI6MTgwNzQ4NDIzMywiaWF0IjoxNzA3NDg0MjMzfQ.7u5bF9PcMhvClSDZgsd6EU-CQnp1Ec--wsezkDEgiZo"

    // Resolve a file id to its hosts via the API:
    // GET api.dandndn.one/api/v1/file/<id> ->
    // upload_links:{hubdrive_res, gdflix_res, katdrive_res, streamtape_res, streamwish_res, ...}
    private suspend fun resolveFileHosts(fileId: String): Map<String, String> {
        val json = try {
            app.get(
                "https://api.dandndn.one/api/v1/file/$fileId",
                headers = mapOf("Authorization" to "Bearer $apiToken") + browserHeaders
            ).text
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = mutableMapOf<String, String>()
        Regex("hubdrive_res\":\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)?.let {
            if (it != "None") out["hubcloud"] = "https://hubcloud.ist/drive/$it"
        }
        Regex("gdflix_res\":\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)?.let {
            if (it != "None") out["gdflix"] = "https://gd.kmhd.me/file/$it"
        }
        Regex("katdrive_res\":\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)?.let {
            if (it != "None") out["katdrive"] = "https://katdrive.click/file/$it"
        }
        Regex("streamtape_res\":\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)?.let {
            if (it != "None") out["streamtape"] = "https://streamtape.com/e/$it"
        }
        Regex("streamwish_res\":\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)?.let {
            if (it != "None") out["streamwish"] = "https://hglink.to/e/$it"
        }
        return out
    }

    // Resolve a HubCloud file id all the way to the direct video file.
    // Chain: hubcloud.ist/drive/<code> -> gamerxyt.php link (in the page)
    // -> direct file. The gamerxyt page gives EITHER a Cloudflare R2 .mkv
    // URL or Pixeldrain links (page URL, NOT playable) -> convert those to
    // the Pixeldrain direct API URL (pixeldrain.dev/api/file/<id>), which
    // IS a playable video file. NOTE: the page lists several Pixeldrain
    // links and some are DEAD (404, e.g. "negn6f" appears on many pages),
    // so validate each with a HEAD request and return the first that
    // actually exists. Returns the direct URL or null.
    private suspend fun resolveHubCloudDirect(hubCode: String): String? {
        val hubUrl = "https://hubcloud.ist/drive/$hubCode"
        val hubHtml = try { app.get(hubUrl, headers = browserHeaders).text } catch (e: Exception) { return null }
        val gamerUrl = Regex("https?://gamerxyt\\.com/hubcloud\\.php\\?host=hubcloud&id=[A-Za-z0-9]+&token=[A-Za-z0-9]+")
            .find(hubHtml)?.value ?: return null
        val gamerHtml = try { app.get(gamerUrl, headers = browserHeaders).text } catch (e: Exception) { return null }
        // 1) Direct file: https://<hash>.r2.cloudflarestorage.com/hub/<hash>?X-Amz-...
        Regex("https?://[^\"' ]+\\.r2\\.cloudflarestorage\\.com/[^\"' ]+")
            .find(gamerHtml)?.value?.let { return it }
        // 2) Pixeldrain page links -> direct API file URLs; try each until
        // one actually exists (HEAD 200), because some are dead (404).
        val pdIds = Regex("https?://pixeldrain\\.dev/u/([A-Za-z0-9]+)")
            .findAll(gamerHtml).map { it.groupValues[1] }.distinct().toList()
        for (id in pdIds) {
            val apiUrl = "https://pixeldrain.dev/api/file/$id"
            val ok = try {
                app.head(apiUrl, headers = browserHeaders).code == 200
            } catch (e: Exception) { false }
            if (ok) return apiUrl
        }
        return null
    }

    // Fallback: Streamtape / Streamwish via the play page (when the
    // episode has no file ids, e.g. Watch Online only)
    private suspend fun loadLinksPlayFallback(
        playId: String,
        fileIds: List<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try { app.get("https://links.kmhd.me/play?id=$playId", headers = browserHeaders).text } catch (e: Exception) { return false }
        val info = parsePlayInfo(html)
        var found = false
        for (fileId in fileIds) {
            val entry = info[fileId] ?: continue
            val st = entry.streamtapeRes
            if (!st.isNullOrEmpty()) {
                if (loadExtractor("https://streamtape.com/e/$st", subtitleCallback, callback)) {
                    found = true
                }
            }
            val sw = entry.streamwishRes
            if (!sw.isNullOrEmpty()) {
                if (loadExtractor("https://hglink.to/e/$sw", subtitleCallback, callback)) {
                    found = true
                }
            }
            if (found) break
        }
        return found
    }

    // Movie path: every LinkEntry (quality variant + size) is resolved and
    // emitted — the user wants all variants visible so they can pick the
    // highest. HubCloud direct first (best quality + embedded subtitles).
    private suspend fun loadLinksMovie(
        data: MovieData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        for (entry in data.links.orEmpty()) {
            val label = entry.label ?: ""
            val quality = qualityFromLabel(label)
            val id = entry.id ?: continue
            when (entry.host) {
                "kmhd" -> {
                    val hosts = resolveFileHosts(id)
                    if (hosts.isEmpty()) continue
                    val hub = hosts["hubcloud"]
                    if (!hub.isNullOrEmpty()) {
                        val hubCode = hub.substringAfterLast("/").trim()
                        val direct = resolveHubCloudDirect(hubCode)
                        if (!direct.isNullOrEmpty()) {
                            callback(
                                newExtractorLink(
                                    source = "HubCloud",
                                    name = "HubCloud $label",
                                    url = direct,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.referer = "https://hubcloud.pw/"
                                }
                            )
                            found = true
                        } else if (loadExtractor(hub, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                    val gd = hosts["gdflix"]
                    if (!gd.isNullOrEmpty()) {
                        if (loadExtractor(gd, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                    val kd = hosts["katdrive"]
                    if (!kd.isNullOrEmpty()) {
                        if (loadExtractor(kd, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                    val st = hosts["streamtape"]
                    if (!st.isNullOrEmpty()) {
                        if (loadExtractor(st, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                    val sw = hosts["streamwish"]
                    if (!sw.isNullOrEmpty()) {
                        if (loadExtractor(sw, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
                "hubcloud" -> {
                    val direct = resolveHubCloudDirect(id)
                    if (!direct.isNullOrEmpty()) {
                        callback(
                            newExtractorLink(
                                source = "HubCloud",
                                name = "HubCloud $label",
                                url = direct,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.referer = "https://hubcloud.pw/"
                            }
                        )
                        found = true
                    } else if (loadExtractor("https://hubcloud.pw/drive/$id", subtitleCallback, callback)) {
                        found = true
                    }
                }
                "gdflix" -> {
                    if (loadExtractor("https://gdflix.vip/file/$id", subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }
        if (!found && data.playId != null) {
            val kmhdIds = data.links.orEmpty().mapNotNull { if (it.host == "kmhd") it.id else null }
            found = loadLinksPlayFallback(data.playId, kmhdIds, subtitleCallback, callback)
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Movie data carries a non-empty links list; series episodes carry
        // f1080/f720/f480 file ids (KatDrama EpisodeData).
        val movieData = tryParseJson<MovieData>(data)
        if (movieData != null && !movieData.links.isNullOrEmpty()) {
            return loadLinksMovie(movieData, subtitleCallback, callback)
        }

        val ep = tryParseJson<EpisodeData>(data) ?: return false
        val playId = ep.playId
        val fileIds = listOfNotNull(ep.f1080, ep.f720, ep.f480)
        var found = false

        // All hosts come from the API (resolveFileHosts): HubCloud, GDFlix,
        // KatDrive, Streamtape, Streamwish. Try 1080p, then 720p, then 480p
        // file ids. HubCloud direct first (best quality + embedded subtitles).
        for ((idx, fileId) in fileIds.withIndex()) {
            val hosts = resolveFileHosts(fileId)
            if (hosts.isEmpty()) continue
            val quality = when (idx) {
                0 -> Qualities.P1080.value
                1 -> Qualities.P720.value
                else -> Qualities.P480.value
            }
            val qName = when (idx) {
                0 -> "1080p"
                1 -> "720p"
                else -> "480p"
            }
            val hub = hosts["hubcloud"]
            if (!hub.isNullOrEmpty()) {
                val hubCode = hub.substringAfterLast("/").trim()
                val direct = resolveHubCloudDirect(hubCode)
                if (!direct.isNullOrEmpty()) {
                    callback(
                        newExtractorLink(
                            source = "HubCloud",
                            name = "HubCloud $qName",
                            url = direct,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.referer = "https://hubcloud.pw/"
                        }
                    )
                    found = true
                } else if (loadExtractor(hub, subtitleCallback, callback)) {
                    found = true
                }
            }
            val gd = hosts["gdflix"]
            if (!gd.isNullOrEmpty()) {
                if (loadExtractor(gd, subtitleCallback, callback)) {
                    found = true
                }
            }
            val kd = hosts["katdrive"]
            if (!kd.isNullOrEmpty()) {
                if (loadExtractor(kd, subtitleCallback, callback)) {
                    found = true
                }
            }
            val st = hosts["streamtape"]
            if (!st.isNullOrEmpty()) {
                if (loadExtractor(st, subtitleCallback, callback)) {
                    found = true
                }
            }
            val sw = hosts["streamwish"]
            if (!sw.isNullOrEmpty()) {
                if (loadExtractor(sw, subtitleCallback, callback)) {
                    found = true
                }
            }
            if (found) break
        }
        if (found) return true

        // Fallback: Streamtape / Streamwish via the play page (when the
        // episode has no file ids, e.g. Watch Online only)
        if (playId == null) return false
        return loadLinksPlayFallback(playId, fileIds, subtitleCallback, callback)
    }
}
