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
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    // ---- Load links (episode -> Streamtape / Streamwish) ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        val playId = ep.playId ?: return false
        // Fetch the play page: it contains info:{<fileId>:{streamtape_res, streamwish_res}}
        val html = app.get("https://links.kmhd.me/play?id=$playId").text
        val info = parsePlayInfo(html)
        // Try 1080p, then 720p, then 480p file ids
        val fileIds = listOfNotNull(ep.f1080, ep.f720, ep.f480)
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
}
