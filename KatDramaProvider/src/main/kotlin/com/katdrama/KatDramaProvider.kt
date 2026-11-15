package com.katdrama

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

class KatDramaProvider : MainAPI() {

    override var mainUrl = "https://new2.katdrama.my"
    override var name = "KatDrama"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    // ---- SvelteKit JSON models (keys are quoted by fixJsObject() first) ----

    data class DramaItem(
        @JsonProperty("post_title") val postTitle: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumbnail_image") val thumbnailImage: String? = null,
        @JsonProperty("categories") val categories: List<String>? = null,
        @JsonProperty("created") val created: String? = null,
    )

    data class ListData(
        @JsonProperty("items") val items: List<DramaItem>? = null,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
    )

    // Episode data passed to loadLinks (as JSON string)
    data class EpisodeData(
        @JsonProperty("playId") val playId: String? = null,
        @JsonProperty("f1080") val f1080: String? = null,
        @JsonProperty("f720") val f720: String? = null,
        @JsonProperty("f480") val f480: String? = null,
    )

    // ---- JS object-literal -> JSON helpers ----
    // The site is a SvelteKit SPA. Its data is embedded in the HTML as a JS
    // object literal with UNQUOTED keys, e.g.
    //   items:[{categories:[...],created:"...",slug:"...",thumbnail_image:"..."}]
    // We extract the literal and quote the keys so Jackson can parse it.

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
        // BUT the marker occurs MULTIPLE times per page, and on search
        // pages the FIRST occurrence is the categories array
        // (data:[{id:"complete-season",...}]) — extractBalanced expects
        // '{', finds '[', returns null, and search returned nothing
        // (verified live 2026-11-15: ?q=True Beauty, categories marker at
        // offset 9872, items payload at 15854). Scan every occurrence and
        // take the first one that parses to a ListData.
        val marker = "data:{success:true,data:"
        var from = 0
        while (true) {
            val idx = html.indexOf(marker, from)
            if (idx < 0) return null
            from = idx + marker.length
            val obj = extractBalanced(html.substring(idx), marker, '{', '}') ?: continue
            val parsed = tryParseJson<ListData>(quoteJsKeys(obj)) ?: continue
            return parsed
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
        // Play page payload: data:{_id:"...",name:"...",info:{Love_xxxx:{name:"...",streamtape_res:"...",streamwish_res:"..."},...}}
        val obj = extractBalanced(html, "info:", '{', '}') ?: return emptyMap()
        val parsed = tryParseJson<Map<String, PlayInfo>>(quoteJsKeys(obj)) ?: return emptyMap()
        return parsed
    }

    data class PlayInfo(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("streamtape_res") val streamtapeRes: String? = null,
        @JsonProperty("streamwish_res") val streamwishRes: String? = null,
    )

    // ---- Main page / search ----

    private fun itemsToSearchResponses(items: List<DramaItem>): List<com.lagradost.cloudstream3.SearchResponse> {
        return items.mapNotNull { item ->
            val title = item.postTitle?.replace("&amp;", "&") ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, "$mainUrl/$slug/", TvType.TvSeries) {
                this.posterUrl = item.thumbnailImage
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // request.data is the category slug ("" = homepage / latest)
        val cat = request.data
        val url = if (cat.isNullOrEmpty()) {
            "$mainUrl/?page=$page"
        } else {
            "$mainUrl/category/$cat/?page=$page"
        }
        val html = app.get(url).text
        val data = parseListData(html)
        val items = data?.items ?: emptyList()
        val title = if (cat.isNullOrEmpty()) "Latest" else cat.replace("-", " ").replaceFirstChar { it.uppercase() }
        return newHomePageResponse(
            listOf(HomePageList(title, itemsToSearchResponses(items), true))
        )
    }

    // Categories shown on the homepage
    override val mainPage = mainPageOf(
        "" to "Latest",
        "action" to "Action",
        "comedy" to "Comedy",
        "romance" to "Romance",
        "hindi-dubbed" to "Hindi Dubbed",
        "movies" to "Movies",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val html = app.get("$mainUrl/?q=${query.encodeUri()}").text
        val data = parseListData(html)
        val items = data?.items ?: return null
        return itemsToSearchResponses(items).toNewSearchResponseList()
    }

    // ---- Load (detail page -> episodes) ----

    // Browser-like headers: links.kmhd.me / HubCloud may block the app's
    // default client, so every request to those hosts sends a real UA.
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // A "complete season" post lists packs: links.kmhd.me/pack/<id> per quality.
    // The pack page embeds info:{<fileId>:{name:"... S01E06 ..."}} for every episode.
    // Returns episode number -> file id.
    private suspend fun fetchPackEpisodes(packId: String): Map<Int, String> {
        val html = try { app.get("https://links.kmhd.me/pack/$packId", headers = browserHeaders).text } catch (e: Exception) { return emptyMap() }
        val out = mutableMapOf<Int, String>()
        // Entries look like: True_ac159f13:{name:"True Beauty S01E06 480p ..."}
        val entryRe = Regex("([A-Za-z0-9_]+):\\{name:\"([^\"]+)\"\\}")
        for (m in entryRe.findAll(html)) {
            val fileId = m.groupValues[1]
            val name = m.groupValues[2]
            val epNum = Regex("(?i)S\\d+E(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("(?i)E(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            out[epNum] = fileId
        }
        return out
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

        // Detail-page synopsis: the post body has a "Storyline:" section,
        // <h2>Title (Season 1) (2020–2021) - Storyline:</h2> followed by
        // a <p> with the real plot (verified live 2026-11-15 on True
        // Beauty: "After being bullied and discriminated because of her
        // ugly looks, a high school girl transforms herself into a
        // gorgeous \"goddess\"..."). og:description is only the title here,
        // so it is NOT used.
        var description: String? = null
        for (h2 in cdoc.select("h2")) {
            if (!h2.text().contains("Storyline", ignoreCase = true)) continue
            var sib = h2.nextElementSibling()
            while (sib != null && sib.tagName() != "p") sib = sib.nextElementSibling()
            val text = sib?.text()?.replace(Regex("\\s+"), " ")?.trim()
            if (!text.isNullOrEmpty() && text.length >= 40) {
                description = text
                break
            }
        }

        // Episodes are grouped as: <p>Episode NN</p> then an <h3> with 3 links
        // (1080p HEVC / 720p HEVC / 480p) -> links.kmhd.me/file/<id>
        val episodes = mutableListOf<Episode>()
        // Walk through elements in order, tracking the current episode number
        var currentEp: Int? = null
        var f1080: String? = null
        var f720: String? = null
        var f480: String? = null
        fun flush() {
            if (currentEp != null && (f1080 != null || f720 != null || f480 != null)) {
                val data = EpisodeData(playId, f1080, f720, f480)
                val json = try {
                    com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
                } catch (e: Exception) {
                    ""
                }
                episodes.add(
                    newEpisode(json) {
                        this.name = "Episode $currentEp"
                        this.episode = currentEp
                        this.season = 1
                        this.posterUrl = poster
                    }
                )
                // Only reset after an episode was actually added, so the
                // movie fallback below can still use links found without
                // an "Episode NN" heading.
                f1080 = null; f720 = null; f480 = null
            }
        }
        for (el in cdoc.body().allElements) {
            val text = el.text().trim()
            // Episode heading: "Episode 01" (text is nested in <b><span>)
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

        // Complete-season posts: no "Episode NN" headings, but pack links
        // (links.kmhd.me/pack/<id>) per quality. Fetch each pack and merge
        // episode file ids by episode number. (Runs BEFORE the movie fallback,
        // because the pack file links would otherwise be mistaken for a movie.)
        if (episodes.isEmpty()) {
            // Collect pack links with their quality label
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
                for ((quality, packId) in packs) {
                    val eps = fetchPackEpisodes(packId)
                    for ((epNum, fileId) in eps) {
                        val cur = byEp[epNum] ?: Triple(null, null, null)
                        byEp[epNum] = when (quality) {
                            "1080", "2160" -> cur.copy(first = fileId)
                            "720" -> cur.copy(second = fileId)
                            "480" -> cur.copy(third = fileId)
                            else -> if (cur.first == null) cur.copy(first = fileId) else cur
                        }
                    }
                }
                for ((epNum, triple) in byEp.toSortedMap()) {
                    val (f1080p, f720p, f480p) = triple
                    if (f1080p == null && f720p == null && f480p == null) continue
                    val data = EpisodeData(playId, f1080p, f720p, f480p)
                    val json = try {
                        com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
                    } catch (e: Exception) {
                        ""
                    }
                    episodes.add(
                        newEpisode(json) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                            this.season = 1
                            this.posterUrl = poster
                        }
                    )
                }
            }
        }
        // Movies / single-file posts: no "Episode NN" heading, just quality links
        if (episodes.isEmpty() && (f1080 != null || f720 != null || f480 != null)) {
            // f1080/f720/f480 were collected without an episode heading
            val data = EpisodeData(playId, f1080, f720, f480)
            val json = try {
                com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
            } catch (e: Exception) {
                ""
            }
            episodes.add(
                newEpisode(json) {
                    this.name = title
                    this.episode = 1
                    this.season = 1
                    this.posterUrl = poster
                }
            )
        }
        // Fallback: if no file links were found at all, still offer the Watch Online link
        if (episodes.isEmpty() && playId != null) {
            val data = EpisodeData(playId, null, null, null)
            val json = try {
                com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
            } catch (e: Exception) {
                ""
            }
            episodes.add(
                newEpisode(json) {
                    this.name = title
                    this.episode = 1
                    this.season = 1
                    this.posterUrl = poster
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ---- Load links (episode -> HubCloud 1080p direct, then Streamtape / Streamwish) ----

    // The site's public API gives a file's upload links directly, no unlock
    // page or session cookie needed. Token is the site's own public JWT
    // (embedded in every links.kmhd.me page, expires 2027).
    private val apiToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhZG1pbiIsImV4cCI6MTgwNzQ4NDIzMywiaWF0IjoxNzA3NDg0MjMzfQ.7u5bF9PcMhvClSDZgsd6EU-CQnp1Ec--wsezkDEgiZo"

    // Resolve a file id to its hosts via the API:
    // GET api.dandndn.one/api/v1/file/<id> -> upload_links:{hubdrive_res, gdflix_res, streamtape_res, streamwish_res}
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
        Regex("streamtape_res\":\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)?.let {
            if (it != "None") out["streamtape"] = "https://streamtape.com/e/$it"
        }
        Regex("streamwish_res\":\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)?.let {
            if (it != "None") out["streamwish"] = "https://hglink.to/e/$it"
        }
        return out
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
    // Chain: pixel.hubcloud.pw/?id=<token> -> 302 workers.dev
    // -> 302 cryptonewz.online/dl.php?link=<googleusercontent URL>.
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        val playId = ep.playId
        val fileIds = listOfNotNull(ep.f1080, ep.f720, ep.f480)
        var found = false

        // All hosts come from the API (resolveFileHosts): HubCloud, GDFlix,
        // Streamtape, Streamwish. Try 1080p, then 720p, then 480p file ids.
        // HubCloud direct first (best quality + embedded subtitles).
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
        val html = try { app.get("https://links.kmhd.me/play?id=$playId", headers = browserHeaders).text } catch (e: Exception) { return false }
        val info = parsePlayInfo(html)
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
}
