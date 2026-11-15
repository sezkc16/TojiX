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
        @JsonProperty("host") val host: String? = null,   // "kmhd" | "hubcloud" | "gdflix" | "bbupload" | "gofile"
        @JsonProperty("id") val id: String? = null,
    )

    // Movie data passed to loadLinks (as a JSON string)
    data class MovieData(
        @JsonProperty("playId") val playId: String? = null,
        @JsonProperty("links") val links: List<LinkEntry>? = null,
    )

    // ---- JS object-literal -> JSON helpers (same as KatDrama) ----

    private fun extractBalanced(text: String, startMarker: String, open: Char, close: Char, startFrom: Int = 0): String? {
        val start = text.indexOf(startMarker, startFrom)
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
        // BUT the page carries TWO resolve payloads: on SEARCH pages the
        // FIRST one is the categories array ([{name:"Action",...}]), NOT
        // the items object — the old indexOf-based parse hit that array,
        // extractBalanced expected '{' but found '[', and search() returned
        // null ("returned nothing") for EVERY query. Scan every occurrence
        // of the marker and take the first one that parses as a ListData
        // with items. (Home/category pages are unaffected: their first
        // payload already is the items object.)
        val marker = "data:{success:true,data:"
        var from = 0
        while (true) {
            val at = html.indexOf(marker, from)
            if (at < 0) return null
            val obj = extractBalanced(html, marker, '{', '}', at)
            if (obj != null) {
                val parsed = tryParseJson<ListData>(quoteJsKeys(obj))
                if (parsed?.items != null) return parsed
            }
            from = at + marker.length
        }
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

    // Plot: movie pages have <h3>... Plot:</h3><p>synopsis</p>, series
    // pages have <h2>... - Storyline:</h2><p>synopsis</p> (both verified
    // live: Hai Jawani Toh Ishq Hona Hai 2026 movie, MobLand S2 series).
    // The heading's next <p> sibling is the synopsis; the "DESCRIPTION:"
    // paragraph is SEO junk and is never used. Applied to the post-content
    // doc (cdoc), not the page doc.
    private fun parsePlot(cdoc: org.jsoup.nodes.Document): String? {
        for (h in cdoc.select("h2, h3")) {
            val t = h.text()
            if (!t.contains("Plot:", true) && !t.contains("Storyline:", true)) continue
            val sib = h.nextElementSibling() ?: continue
            if (sib.tagName() != "p") continue
            val text = sib.text().replace(Regex("\\s+"), " ").trim()
            if (text.length >= 40 && !text.startsWith("N/A")) return text
        }
        return null
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
        val plot = parsePlot(cdoc)

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
        // gdflix.vip/file/<id>. Some posts (e.g. Hai Jawani Toh Ishq
        // Hona Hai 2026) have NO kmhd/hubcloud/gdflix links at all — only
        // "WEBRip Direct Link" rows, one <h2> per quality holding TWO
        // anchors: <a href="https://download.bbupload.to/download?v=<v>">
        // 1080pDL</a> | <a href="https://gofile.io/d/<id>">GoFILE</a>.
        // The ": DOWNLOAD LINKS :" anchor points to
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
                href.contains("bbupload.to/download") -> {
                    // ?v=<code> — resolved in loadLinks via the host's own
                    // ajax.php endpoint (see resolveBbUpload).
                    val v = href.substringAfter("v=").substringBefore("&").trim()
                    if (v.isNotEmpty() && label.isNotEmpty()) movieLinks.add(LinkEntry(label, "bbupload", v))
                }
                href.contains("gofile.io/d/") -> {
                    val id = href.substringAfterLast("/").trim()
                    if (id.isEmpty()) continue
                    // The GoFILE anchor's own label has no quality
                    // ("GoFILE") — the sibling bbupload anchor in the
                    // same <h2> carries it ("1080pDL" / "720pDL" /
                    // "480pDL"), so read the row text for the quality.
                    val rowText = el.parent()?.text() ?: ""
                    val q = when {
                        rowText.contains("2160", true) || rowText.contains("4k", true) -> "2160p"
                        rowText.contains("1080", true) -> "1080p"
                        rowText.contains("720", true) -> "720p"
                        rowText.contains("480", true) -> "480p"
                        else -> ""
                    }
                    val entryLabel = if (q.isNotEmpty()) "$q GoFILE" else "GoFILE"
                    movieLinks.add(LinkEntry(entryLabel, "gofile", id))
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
                    this.plot = plot
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
                    this.plot = plot
                }
            } else {
                val data = MovieData(playId, emptyList())
                newMovieLoadResponse(title, url, TvType.Movie, toJsonString(data)) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        }

        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
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

    // bbupload.to (ninjashare-style host): the download page's own JS
    // calls GET /ajax.php?action=getdownload&data=<v> and gets
    // {status:"ok",data:{downloadUrl:"https://superduperload.site/get/<b64>",
    // FileName:"...mkv",filesize:"..."}}. The downloadUrl IS the direct
    // file — verified live 2026-11-15: a ranged GET returns real
    // Matroska bytes (EBML 1a 45 df a3) for all 3 qualities of Hai
    // Jawani Toh Ishq Hona Hai 2026, no session cookie needed. The host
    // mislabels Content-Type as image/png — the bytes are video, so the
    // player must not trust the header.
    private suspend fun resolveBbUpload(v: String): String? {
        val json = try {
            app.get(
                "https://download.bbupload.to/ajax.php?action=getdownload&data=$v",
                headers = browserHeaders
            ).text
        } catch (e: Exception) { return null }
        val raw = Regex("downloadUrl\":\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1) ?: return null
        val url = raw.replace("\\/", "/")
        return url.takeIf { it.startsWith("https://") }
    }

    // One resolved HubCloud server: the actual server name (FSL, FSLv2,
    // PixelDrain, 10G) + its direct playable file URL. The gamerxyt page
    // lists one button per server; each becomes its own source in the app
    // so the user sees WHICH server a link is instead of "HubCloud" on
    // every row.
    data class HubServer(
        val server: String,
        val url: String,
    )

    // FSL/FSLv2 button href: usually the R2 file URL (playable as-is).
    // When it is NOT an R2 URL, the page's own JS appends '1' + the
    // current minute to the href before the user clicks — mimic that.
    private fun fslUrl(href: String): String {
        if (href.contains("r2.cloudflarestorage.com")) return href
        val minute = java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE)
        return href + "1" + minute
    }

    // Resolve the 10Gbps pixel link to the direct Google video file.
    // Chain: pixel.hubcloud.mov (or gpdl.hubcloud.mov)/?id=<token>
    // -> 302 workers.dev -> 302 cryptonewz.online/dl.php?link=<googleusercontent URL>.
    // The workers.dev hop is flaky (HTTP 500) — any failure just skips
    // 10G for this file, it never breaks the other servers.
    private suspend fun resolvePixel10G(pixelUrl: String): String? {
        return try {
            val hop1 = app.get(pixelUrl, headers = browserHeaders, allowRedirects = false)
            val workersUrl = hop1.headers["Location"] ?: hop1.headers["location"] ?: return null
            if (!workersUrl.startsWith("http")) return null
            val hop2 = app.get(workersUrl, headers = browserHeaders, allowRedirects = false)
            val dlUrl = hop2.headers["Location"] ?: hop2.headers["location"] ?: return null
            val linkParam = Regex("[?&]link=([^&]+)").find(dlUrl)?.groupValues?.getOrNull(1) ?: return null
            val gu = java.net.URLDecoder.decode(linkParam, "UTF-8").trim()
            if (!gu.startsWith("https://video-downloads.googleusercontent.com/")) return null
            val head = try { app.head(gu, headers = browserHeaders) } catch (e: Exception) { return null }
            if (head.code != 200) return null
            val ct = head.headers["Content-Type"] ?: head.headers["content-type"] ?: ""
            if (ct.isNotEmpty() && !ct.startsWith("video/") && !ct.startsWith("application/octet-stream")) return null
            gu
        } catch (e: Exception) { null }
    }

    // Resolve a HubCloud file id to one entry PER SERVER on the gamerxyt
    // page (FSL, FSLv2, PixelDrain, 10G), each with its direct file URL.
    // Buttons look like: <a href="..." ...>Download [FSL Server]</a>.
    // - FSL / FSLv2: the button href IS the R2 file URL (playable).
    //   R2 is NOT HEAD-checked: HEAD returns 403, but ranged GETs play.
    // - PixelServer: pixeldrain.dev/u/<id> -> api/file/<id>, HEAD-checked
    //   because some Pixeldrain links are DEAD (404).
    // - 10Gbps: pixel chain -> Google video file (see resolvePixel10G).
    // - Buzz Server (fuckingfast.net) is a download page, not a stream.
    // If no PixelDrain came from a button, the first working Pixeldrain
    // mirror from the page is added (the button's file is often dead
    // while another mirror is alive). FALLBACK: if no server at all
    // parsed, the old behaviour — first R2 URL as a single "HubCloud"
    // entry — so playback never breaks.
    private suspend fun resolveHubCloudServers(hubCode: String): List<HubServer> {
        val hubUrl = "https://hubcloud.ist/drive/$hubCode"
        val hubHtml = try { app.get(hubUrl, headers = browserHeaders).text } catch (e: Exception) { return emptyList() }
        val gamerUrl = Regex("https?://gamerxyt\\.com/hubcloud\\.php\\?host=hubcloud&id=[A-Za-z0-9]+&token=[A-Za-z0-9]+")
            .find(hubHtml)?.value ?: return emptyList()
        val gamerHtml = try { app.get(gamerUrl, headers = browserHeaders).text } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<HubServer>()
        // Dedup on (server, url): FSL and FSLv2 sometimes point at the
        // same file URL — with URL-only dedup the second one was dropped
        // and FSLv2 never showed. The same URL under a different server
        // name is a different source row, so it must be kept.
        val seen = mutableSetOf<Pair<String, String>>()
        fun add(server: String, url: String) {
            val u = url.trim()
            if (u.isNotEmpty() && seen.add(server to u)) out.add(HubServer(server, u))
        }
        val btnRe = Regex("<a\\s+href=\"([^\"]+)\"[^>]*>\\s*(?:<i[^>]*></i>\\s*)?Download \\[([^\\]]+)\\]")
        for (m in btnRe.findAll(gamerHtml)) {
            val href = m.groupValues[1].trim()
            val label = m.groupValues[2].trim()
            when {
                label.contains("FSLv2", ignoreCase = true) -> add("FSLv2", fslUrl(href))
                label.contains("FSL", ignoreCase = true) -> add("FSL", fslUrl(href))
                label.contains("Pixel", ignoreCase = true) -> {
                    val id = Regex("pixeldrain\\.dev/u/([A-Za-z0-9]+)").find(href)?.groupValues?.getOrNull(1) ?: continue
                    val apiUrl = "https://pixeldrain.dev/api/file/$id"
                    val ok = try {
                        app.head(apiUrl, headers = browserHeaders).code == 200
                    } catch (e: Exception) { false }
                    if (ok) add("PixelDrain", apiUrl)
                }
                label.contains("10G", ignoreCase = true) -> {
                    resolvePixel10G(href)?.let { add("10G", it) }
                }
                else -> { /* Buzz Server and unknown hosts: not a direct stream, skip */ }
            }
        }
        // The PixelServer button can point at a DEAD Pixeldrain file while
        // another mirror on the same page is alive — if no PixelDrain came
        // from a button, take the first working one from the page.
        if (out.none { it.server == "PixelDrain" }) {
            val pdIds = Regex("https?://pixeldrain\\.dev/u/([A-Za-z0-9]+)")
                .findAll(gamerHtml).map { it.groupValues[1] }.distinct().toList()
            for (id in pdIds) {
                val apiUrl = "https://pixeldrain.dev/api/file/$id"
                val ok = try {
                    app.head(apiUrl, headers = browserHeaders).code == 200
                } catch (e: Exception) { false }
                if (ok) { add("PixelDrain", apiUrl); break }
            }
        }
        if (out.isNotEmpty()) return out
        // Fallback: old single-link behaviour (first R2 URL as "HubCloud").
        // Pixeldrain is already covered above (as "PixelDrain").
        Regex("https?://[^\"' ]+\\.r2\\.cloudflarestorage\\.com/[^\"' ]+")
            .find(gamerHtml)?.value?.let { add("HubCloud", it); return out }
        return out
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
                        // One source per HubCloud server (FSL, FSLv2,
                        // PixelDrain, 10G) so the app shows WHICH server
                        // a link is.
                        val servers = resolveHubCloudServers(hubCode)
                        if (servers.isNotEmpty()) {
                            for (server in servers) {
                                callback(
                                    newExtractorLink(
                                        source = "HubCloud",
                                        name = "HubCloud ${server.server} $label",
                                        url = server.url,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = quality
                                        this.referer = "https://hubcloud.pw/"
                                    }
                                )
                                found = true
                            }
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
                    // One source per HubCloud server (FSL, FSLv2,
                    // PixelDrain, 10G) so the app shows WHICH server a
                    // link is.
                    val servers = resolveHubCloudServers(id)
                    if (servers.isNotEmpty()) {
                        for (server in servers) {
                            callback(
                                newExtractorLink(
                                    source = "HubCloud",
                                    name = "HubCloud ${server.server} $label",
                                    url = server.url,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.referer = "https://hubcloud.pw/"
                                }
                            )
                            found = true
                        }
                    } else if (loadExtractor("https://hubcloud.pw/drive/$id", subtitleCallback, callback)) {
                        found = true
                    }
                }
                "gdflix" -> {
                    if (loadExtractor("https://gdflix.vip/file/$id", subtitleCallback, callback)) {
                        found = true
                    }
                }
                "bbupload" -> {
                    val direct = resolveBbUpload(id)
                    if (!direct.isNullOrEmpty()) {
                        callback(
                            newExtractorLink(
                                source = "BbUpload",
                                name = "BbUpload $label",
                                url = direct,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.referer = "https://download.bbupload.to/"
                            }
                        )
                        found = true
                    }
                }
                "gofile" -> {
                    // Built-in GoFile extractor (same pattern as
                    // Movies4u/GokuHD "extractor" links). NOTE 2026-11-15:
                    // this movie's folders (Rxgbc2/apBj6Q/7jhsF8) return
                    // {"status":"error-notPremium"} from GoFile's own API
                    // even with a valid guest token, so they will not play
                    // here — bbupload carries the movie. Non-premium
                    // folders still resolve through the extractor.
                    if (loadExtractor("https://gofile.io/d/$id", subtitleCallback, callback)) {
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
                // One source per HubCloud server (FSL, FSLv2, PixelDrain,
                // 10G) so the app shows WHICH server a link is.
                val servers = resolveHubCloudServers(hubCode)
                if (servers.isNotEmpty()) {
                    for (server in servers) {
                        callback(
                            newExtractorLink(
                                source = "HubCloud",
                                name = "HubCloud ${server.server} $qName",
                                url = server.url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.referer = "https://hubcloud.pw/"
                            }
                        )
                        found = true
                    }
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
