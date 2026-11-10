package com.anikoto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.jsoup.Jsoup

/**
 * Anikoto — free anime streaming (Sub & Dub), HD.
 *
 * Server-rendered AniWatch-clone theme. Catalogue pages are plain HTML
 * (#list-items .item); the watch page IS the detail page (.binfo/.bmeta).
 *
 * Playback chain (verified end-to-end 2026-11-10, Black Clover ep 1):
 *  1. GET /ajax/episode/list/<animeId> (X-Requested-With header) -> episode
 *     <a> items with data-id / data-num / data-ids (base64 token).
 *  2. GET /ajax/server/list?servers=<data-ids> -> server <li> items
 *     (SUB: HD-1, Vidstream-2, Vidstream-1; DUB: same) with data-link-id.
 *  3. GET /ajax/server?get=<link-id> -> {"result":{"url":"<megaplay embed>"}}.
 *  4. GET the megaplay.buzz embed page -> #megaplay-player data-id (file id)
 *     / data-realid.
 *  5. GET https://megaplay.buzz/stream/getSourcesNew?id=<data-id> (XHR +
 *     Referer = embed URL) -> {"tracks":[...],"enc":"<base64url>"}.
 *  6. AES-256-CBC decrypt enc (key "i?LMTAx0Q6,:}50U" zero-padded to 32B,
 *     IV "W0;27ToaUpl_P%'c", base64url) -> {"file":"<master.m3u8>"} on
 *     fetch.nexabloom.top. The master + segments REQUIRE
 *     Referer: https://megaplay.buzz/ (no Referer = 403) — the ExtractorLink
 *     carries that referer. Segment bytes verified: 0x47 TS sync at
 *     188-byte boundaries.
 * The megaplay key/IV were extracted from megaplay.buzz/lib/e1-player.min.js
 * (string-array entries 50/51); if megaplay rotates the player the key may
 * change and this provider breaks — it cannot be extracted dynamically by
 * an OkHttp client (obfuscated self-defending JS).
 */
class AnikotoProvider : MainAPI() {

    override var mainUrl = "https://anikoto.cz"
    override var name = "Anikoto"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun pageHeaders(referer: String? = null): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        if (referer != null) h["Referer"] = referer
        return h
    }

    private fun ajaxHeaders(referer: String? = null): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to ua,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
        if (referer != null) h["Referer"] = referer
        return h
    }

    // Megaplay (megaplay.buzz) AES key/IV for the getSources "enc" field.
    private val megaKeyStr = "i?LMTAx0Q6,:}50U"
    private val megaIvStr = "W0;27ToaUpl_P%'c"
    private val megaReferer = "https://megaplay.buzz/"

    // ---- JSON models ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AjaxResult(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerGetResult(
        @JsonProperty("url") val url: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerGetResp(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: ServerGetResult? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GsTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("default") val default: Boolean? = null,
        @JsonProperty("isDefault") val isDefault: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GsResp(
        @JsonProperty("tracks") val tracks: List<GsTrack>? = null,
        @JsonProperty("enc") val enc: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaFile(
        @JsonProperty("file") val file: String? = null,
    )

    // Data passed to loadLinks.
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeData(
        @JsonProperty("ep") val ep: Int? = null,
        @JsonProperty("ids") val ids: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    private data class ServerEntry(val type: String, val name: String, val linkId: String)

    // ---- helpers ----

    private fun toJson(data: EpisodeData): String? {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
        } catch (e: Exception) {
            null
        }
    }

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

    /** AES-256-CBC decrypt of megaplay's getSources "enc" (base64url). */
    private fun decryptMegaplay(enc: String): String? {
        return try {
            val ct = java.util.Base64.getUrlDecoder().decode(enc)
            val keyBytes = megaKeyStr.toByteArray(Charsets.UTF_8).copyOf(32)
            val ivBytes = megaIvStr.toByteArray(Charsets.UTF_8).copyOf(16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes),
            )
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort: read the master playlist's max height for an accurate quality label. */
    private fun masterQuality(masterUrl: String): Int {
        return try {
            val text = app.get(masterUrl, headers = mapOf("User-Agent" to ua, "Referer" to megaReferer)).text
            val heights = Regex("RESOLUTION=\\d+x(\\d+)").findAll(text)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toList()
            qualityFromHeight(heights.maxOrNull())
        } catch (e: Exception) {
            Qualities.P1080.value
        }
    }

    // ---- catalogue ----

    private fun parseListing(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (item in doc.select("div#list-items div.item")) {
            val posterA = item.selectFirst("div.ani.poster a[href]") ?: continue
            var href = posterA.attr("href").trim()
            if (href.isEmpty()) continue
            // Strip the /ep-N suffix — the load URL is the plain watch page.
            href = href.substringBefore("/ep-")
            if (!seen.add(href)) continue
            val title = item.selectFirst("a.name.d-title")?.text()?.trim()
                ?: posterA.selectFirst("img")?.attr("alt")?.trim()
                ?: continue
            if (title.isEmpty()) continue
            val poster = posterA.selectFirst("img")?.attr("src")?.takeIf { it.isNotEmpty() }
            val typeText = item.selectFirst("div.meta div.right")?.text()?.trim().orEmpty()
            val isMovie = typeText.contains("Movie", ignoreCase = true)
            if (isMovie) {
                out.add(newMovieSearchResponse(title, href, TvType.AnimeMovie) { this.posterUrl = poster })
            } else {
                out.add(newTvSeriesSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster })
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data
        val url = when (cat) {
            "" -> "$mainUrl/latest-updated?page=$page"
            "most-viewed" -> "$mainUrl/most-viewed?page=$page"
            "new-release" -> "$mainUrl/new-release?page=$page"
            "currently-airing" -> "$mainUrl/status/currently-airing?page=$page"
            "movies" -> "$mainUrl/type/movie?page=$page"
            else -> "$mainUrl/genre/$cat?page=$page"
        }
        val html = app.get(url, headers = pageHeaders()).text
        val list = parseListing(html)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    override val mainPage = mainPageOf(
        "" to "Latest Updated",
        "most-viewed" to "Most Viewed",
        "new-release" to "New Release",
        "currently-airing" to "Currently Airing",
        "movies" to "Movies",
        "action" to "Action",
        "fantasy" to "Fantasy",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/filter?keyword=${URLEncoder.encode(query.trim(), "UTF-8")}&page=$page"
        val html = app.get(url, headers = pageHeaders()).text
        return parseListing(html).toNewSearchResponseList()
    }

    // ---- detail ----

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = pageHeaders()).text
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.title.d-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - Anikoto")?.substringBefore("Watch Free! ")?.trim()
            ?: return null
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("div.binfo div.poster img")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("div.synopsis div.content")?.text()?.trim()

        var type = "TV"
        var year: Int? = null
        var status: ShowStatus? = null
        var rating: Int? = null
        for (row in doc.select("div.bmeta div.meta > div")) {
            val label = row.ownText().trim()
            val value = row.selectFirst("span")?.text()?.trim().orEmpty()
            val rowText = row.text()
            when {
                label.startsWith("Type:") -> type = value.ifEmpty { "TV" }
                label.startsWith("Aired:") || label.startsWith("Premiered:") -> {
                    if (year == null) year = Regex("(19|20)\\d{2}").find(rowText)?.value?.toIntOrNull()
                }
                label.startsWith("Status:") -> {
                    status = when {
                        rowText.contains("Finished", ignoreCase = true) -> ShowStatus.Completed
                        rowText.contains("Currently", ignoreCase = true) -> ShowStatus.Ongoing
                        else -> null
                    }
                }
                label.startsWith("MAL:") -> {
                    val score = Regex("(\\d+(\\.\\d+)?)").find(value)?.value?.toDoubleOrNull()
                    if (score != null) rating = (score * 10).toInt()
                }
            }
        }
        val genres = doc.select("div.bmeta a[href^=/genre/]").map { it.text().trim() }.filter { it.isNotEmpty() }

        val animeId = doc.selectFirst("[data-anime-id]")?.attr("data-anime-id")?.trim() ?: return null
        val epHtml = try {
            app.get("$mainUrl/ajax/episode/list/$animeId", headers = ajaxHeaders(url)).parsed<AjaxResult>().result
        } catch (e: Exception) {
            null
        } ?: return null
        val epDoc = Jsoup.parse(epHtml)
        val episodes = mutableListOf<Episode>()
        var firstEpData: String? = null
        for (a in epDoc.select("a[data-id][data-num][data-ids]")) {
            val num = a.attr("data-num").toIntOrNull() ?: continue
            val ids = a.attr("data-ids").trim()
            if (ids.isEmpty()) continue
            val dataJson = toJson(EpisodeData(ep = num, ids = ids, url = url)) ?: continue
            if (firstEpData == null) firstEpData = dataJson
            episodes.add(newEpisode(dataJson) {
                this.name = "Episode $num"
                this.episode = num
            })
        }
        if (episodes.isEmpty()) return null
        episodes.sortBy { it.episode }

        val isMovie = type.equals("Movie", ignoreCase = true)
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, firstEpData ?: return null) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres.ifEmpty { null }
                if (rating != null) this.rating = rating
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres.ifEmpty { null }
            this.showStatus = status
            if (rating != null) this.rating = rating
        }
    }

    // ---- streams ----

    /** Resolve one megaplay embed URL to its master.m3u8 and emit it. */
    private suspend fun resolveMegaplay(
        embedUrl: String,
        watchUrl: String,
        tag: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        return try {
            val embedHtml = app.get(embedUrl, headers = pageHeaders(watchUrl)).text
            val embedDoc = Jsoup.parse(embedHtml)
            val player = embedDoc.selectFirst("#megaplay-player") ?: return false
            val fileId = player.attr("data-id").trim()
            val realId = player.attr("data-realid").trim()
            // data-id first (full subtitle tracks); realid as fallback.
            val ids = listOf(fileId, realId).filter { it.isNotEmpty() }.distinct()
            for (id in ids) {
                try {
                    val gs = app.get(
                        "https://megaplay.buzz/stream/getSourcesNew?id=$id",
                        headers = ajaxHeaders(embedUrl),
                    ).parsed<GsResp>()
                    val enc = gs.enc ?: continue
                    val plain = decryptMegaplay(enc) ?: continue
                    val master = tryParseJson<MegaFile>(plain)?.file?.trim() ?: continue
                    if (master.isEmpty()) continue
                    // Subtitles (best-effort: mysubsplay.com 403s from some networks).
                    try {
                        for (t in gs.tracks ?: emptyList()) {
                            val kind = t.kind
                            if (kind != null && kind != "captions" && kind != "subtitles") continue
                            val file = t.file ?: t.src ?: t.url ?: continue
                            subtitleCallback(SubtitleFile(t.label ?: "Unknown", file))
                        }
                    } catch (e: Exception) {
                        // Subtitles must never kill the stream.
                    }
                    try {
                        callback(
                            newExtractorLink(
                                source = "Anikoto",
                                name = "MegaPlay [$tag]",
                                url = master,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.quality = masterQuality(master)
                                this.referer = megaReferer
                                this.headers = mapOf(
                                    "Referer" to megaReferer,
                                    "User-Agent" to ua,
                                )
                            }
                        )
                        return true
                    } catch (e: Exception) {
                        continue
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        val ids = ep.ids ?: return false
        val watchUrl = ep.url ?: mainUrl
        var found = false
        try {
            val srv = app.get(
                "$mainUrl/ajax/server/list?servers=${URLEncoder.encode(ids, "UTF-8")}",
                headers = ajaxHeaders(watchUrl),
            ).parsed<AjaxResult>()
            val srvDoc = Jsoup.parse(srv.result ?: return false)
            val servers = mutableListOf<ServerEntry>()
            for (typeDiv in srvDoc.select("div.type[data-type]")) {
                val type = typeDiv.attr("data-type").trim().lowercase()
                for (li in typeDiv.select("li[data-link-id]")) {
                    val linkId = li.attr("data-link-id").trim()
                    if (linkId.isEmpty()) continue
                    servers.add(ServerEntry(type, li.text().trim(), linkId))
                }
            }
            // One server per audio type is enough (all point at the same file);
            // try the servers of each type in order until one resolves.
            val seenEmbeds = mutableSetOf<String>()
            for (wantType in listOf("sub", "dub")) {
                val typed = servers.filter { it.type == wantType }
                for (server in typed) {
                    try {
                        val got = app.get(
                            "$mainUrl/ajax/server?get=${URLEncoder.encode(server.linkId, "UTF-8")}",
                            headers = ajaxHeaders(watchUrl),
                        ).parsed<ServerGetResp>()
                        val embedUrl = got.result?.url?.trim() ?: continue
                        if (!seenEmbeds.add(embedUrl)) continue
                        val tag = if (wantType == "dub") "Dub" else "Sub"
                        try {
                            if (resolveMegaplay(embedUrl, watchUrl, tag, callback, subtitleCallback)) {
                                found = true
                            }
                        } catch (e: Exception) {
                            // This server failed — try the next one.
                        }
                        if (found) break
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        } catch (e: Exception) {
            // loadLinks must not throw.
        }
        return found
    }
}
