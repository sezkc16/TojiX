package com.anikoto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
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
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Anikoto — free anime streaming (Sub & Dub), HD.
 *
 * Server-rendered AniWatch-clone theme. Catalogue pages are plain HTML
 * (#list-items .item); the watch page IS the detail page (.binfo/.bmeta).
 *
 * v2 (2026-11-10): episode list now matches the working Anikoto plugins:
 *  - newAnimeLoadResponse + addEpisodes(DubStatus.Subbed/Dubbed) -> the
 *    Sub/Dub toggle (the flat TvSeries list had none).
 *  - Real episode titles / scores / synopses from Jikan (MAL id = data-mal
 *    on the episode list; best-effort — falls back to "Episode N").
 *  - Episode poster = anime poster (the site has no per-episode stills;
 *    v1 left posterUrl unset -> broken-image placeholder).
 *  - Episode date from the site's data-timestamp (unix; verified Oct 5/12/
 *    19/26 2026 for Psyren), runTime from the page's Duration.
 *  - loadLinks emits one link PER server (v1 stopped after the first
 *    success, leaving a single fallback) + a fast path that builds the
 *    megaplay embed straight from the catalog API's episode_embed_id
 *    (anikotoapi.site/series/<id>), and links are named after the server
 *    ("HD-1 [Sub]") so the source picker lists them all.
 *
 * Playback chain (verified end-to-end 2026-11-10, Black Clover ep 1 AND
 * Psyren ep 1):
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

    // anikoto's public catalog API — gives mal/ani ids + per-episode embed ids
    // (direct megaplay URLs), so loadLinks has a fast path that skips the
    // server-list round-trip. Verified 2026-11-10 (Psyren, animeId 9050).
    private val apiBase = "https://anikotoapi.site"

    // Jikan (MyAnimeList) — best-effort episode metadata. The site's own
    // episode list only has "Episode N"; the real titles / scores / synopses
    // (the ones the working plugins show) come from Jikan via the MAL id
    // (data-mal on the episode list). If Jikan is unreachable the plugin
    // falls back to the site data — this must never break load().
    private val jikanBase = "https://api.jikan.moe/v4"

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

    // Data passed to loadLinks. embed = episode_embed_id from anikotoapi.site
    // (fast path); lang = "sub"/"dub" (the DubStatus the user tapped).
    // Old v1 data ({ep, ids, url}) still parses: embed/lang default to null
    // (lang -> "sub", fast path skipped).
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeData(
        @JsonProperty("ep") val ep: Int? = null,
        @JsonProperty("ids") val ids: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("lang") val lang: String? = null,
    )

    // Jikan episode metadata (best-effort). Response:
    // {"pagination":{"has_next_page":bool},"data":[{"mal_id":1,"title":"...",
    // "aired":"2026-10-05T00:00:00+00:00","score":5.5,"synopsis":"..."}]}
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JikanEp(
        @JsonProperty("mal_id") val malId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("aired") val aired: String? = null,
        @JsonProperty("score") val score: Double? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JikanPagination(
        @JsonProperty("has_next_page") val hasNextPage: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JikanEpResp(
        @JsonProperty("data") val data: List<JikanEp>? = null,
        @JsonProperty("pagination") val pagination: JikanPagination? = null,
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
    private suspend fun masterQuality(masterUrl: String): Int {
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

    /**
     * Best-effort: anikotoapi.site series endpoint.
     * Returns (malId, episode number -> episode_embed_id).
     * Verified live (Psyren 9050: mal 63098, ep1 embed 203428, embed_url
     * sub/dub megaplay URLs). Never throws — returns empty on any failure.
     */
    private suspend fun fetchApiSeries(animeId: String): Pair<String, Map<Int, String>> {
        return try {
            val res = app.get(
                "$apiBase/series/$animeId",
                headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
            )
            if (!res.isSuccessful) return "" to emptyMap()
            val json = JSONObject(res.text)
            if (!json.optBoolean("ok", false)) return "" to emptyMap()
            val data = json.optJSONObject("data") ?: return "" to emptyMap()
            val anime = data.optJSONObject("anime")
            val malId = anime?.optString("mal_id").orEmpty()
            val embedByNum = mutableMapOf<Int, String>()
            val eps = data.optJSONArray("episodes")
            if (eps != null) {
                for (i in 0 until eps.length()) {
                    val ep = eps.optJSONObject(i) ?: continue
                    val num = ep.optInt("number", 0)
                    val embedId = ep.optString("episode_embed_id").trim()
                    if (num > 0 && embedId.isNotEmpty()) embedByNum[num] = embedId
                }
            }
            malId to embedByNum
        } catch (e: Exception) {
            "" to emptyMap()
        }
    }

    /**
     * Best-effort: Jikan per-episode metadata (real titles, scores, synopses).
     * Returns episode number -> JikanEp. Never throws — returns empty on any
     * failure (Jikan unreachable / rate-limited / unknown MAL id).
     * NOTE: Jikan is unreachable from this VM (verified 2026-11-10), so this
     * enrichment is verified by code review only; on the phone it either
     * fills in the real titles or no-ops and the site data is used.
     */
    private suspend fun fetchJikanEpisodes(malId: String): Map<Int, JikanEp> {
        val out = mutableMapOf<Int, JikanEp>()
        if (malId.isBlank()) return out
        try {
            var page = 1
            while (page <= 3) {
                val resp = try {
                    app.get(
                        "$jikanBase/anime/$malId/episodes?page=$page",
                        headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
                    ).parsed<JikanEpResp>()
                } catch (e: Exception) {
                    break
                }
                for (e in resp.data ?: emptyList()) {
                    val n = e.malId ?: continue
                    if (n > 0) out[n] = e
                }
                if (resp.pagination?.hasNextPage != true) break
                page++
            }
        } catch (e: Exception) {
            // Best-effort only.
        }
        return out
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
        var durationMin: Int? = null
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
                label.startsWith("Duration:") -> {
                    if (durationMin == null) durationMin = Regex("(\\d+)\\s*m").find(rowText)?.groupValues?.get(1)?.toIntOrNull()
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
        // Site episode data: the <a> carries data-num / data-ids / data-dub /
        // data-mal (MAL id) / data-timestamp (unix air date). It has NO real
        // title / rating / description / poster — those come from Jikan below.
        data class SiteEp(val num: Int, val ids: String, val ts: Long?, val hasDub: Boolean, val mal: String)
        val siteEps = mutableListOf<SiteEp>()
        for (a in epDoc.select("a[data-id][data-num][data-ids]")) {
            val num = a.attr("data-num").toIntOrNull() ?: continue
            val ids = a.attr("data-ids").trim()
            if (ids.isEmpty()) continue
            val ts = a.attr("data-timestamp").toLongOrNull()
            val hasDub = a.attr("data-dub").trim() == "1"
            val mal = a.attr("data-mal").trim()
            siteEps.add(SiteEp(num, ids, ts, hasDub, mal))
        }
        if (siteEps.isEmpty()) return null
        siteEps.sortBy { it.num }

        // Fast-path embed ids + MAL id from the catalog API (best-effort).
        val (apiMal, embedByNum) = fetchApiSeries(animeId)
        val malId = siteEps.firstOrNull { it.mal.isNotEmpty() }?.mal ?: apiMal

        // Real episode titles / scores / synopses from Jikan (best-effort).
        val jikanByNum = fetchJikanEpisodes(malId)

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        var firstEpData: String? = null
        for (se in siteEps) {
            val jk = jikanByNum[se.num]
            // Real title from Jikan; the site only has "Episode N".
            val epName = jk?.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Episode ${se.num}"
            val epScore = jk?.score?.let { Score.from10(it) }
            val epDesc = jk?.synopsis?.trim()?.takeIf { it.isNotEmpty() }
            // Air date: the site's data-timestamp (verified: 1791211001 ->
            // Oct 5, 2026, matching the working plugin); Jikan `aired` fallback.
            val tsMs = se.ts?.let { it * 1000L }
            val embedId = embedByNum[se.num]
            val subData = toJson(EpisodeData(ep = se.num, ids = se.ids, url = url, embed = embedId, lang = "sub")) ?: continue
            if (firstEpData == null) firstEpData = subData
            subEps.add(newEpisode(subData) {
                this.name = epName
                this.episode = se.num
                this.season = 1
                // The site has no per-episode stills; use the anime poster so
                // the card is never the broken-image placeholder.
                this.posterUrl = poster
                this.score = epScore
                this.description = epDesc
                if (tsMs != null) {
                    this.date = tsMs
                } else {
                    this.addDate(jk?.aired)
                }
                this.runTime = durationMin
            })
            if (se.hasDub) {
                val dubData = toJson(EpisodeData(ep = se.num, ids = se.ids, url = url, embed = embedId, lang = "dub")) ?: continue
                dubEps.add(newEpisode(dubData) {
                    this.name = "$epName (Dub)"
                    this.episode = se.num
                    this.season = 1
                    this.posterUrl = poster
                    this.score = epScore
                    this.description = epDesc
                    if (tsMs != null) {
                        this.date = tsMs
                    } else {
                        this.addDate(jk?.aired)
                    }
                    this.runTime = durationMin
                })
            }
        }
        if (subEps.isEmpty()) return null

        val isMovie = type.equals("Movie", ignoreCase = true)
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, firstEpData ?: return null) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres.ifEmpty { null }
                this.duration = durationMin
            }
        }
        // AnimeLoadResponse + DubStatus gives the Sub/Dub toggle (the working
        // plugin has it; the flat TvSeries list did not).
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres.ifEmpty { null }
            this.showStatus = status
            this.duration = durationMin
            addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    // ---- streams ----

    /** Resolve one megaplay embed URL to its master.m3u8 and emit it.
     *  The link is named after the server ("HD-1 [Sub]") so the source
     *  picker lists one entry per server, like the working plugin. */
    private suspend fun resolveMegaplay(
        embedUrl: String,
        watchUrl: String,
        serverName: String,
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
                    // Quality is read in suspend context BEFORE newExtractorLink —
                    // the builder lambda must only assign plain values (Miruro pattern).
                    val quality = masterQuality(master)
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
                                name = "$serverName [$tag]",
                                url = master,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.quality = quality
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
        val ids = ep.ids
        val watchUrl = ep.url ?: mainUrl
        val lang = (ep.lang ?: "sub").lowercase()
        val embedId = ep.embed
        var found = false
        val seenEmbeds = mutableSetOf<String>()

        // Fast path: direct megaplay embed from the catalog API's episode
        // embed id (skips the server-list round-trip). Verified live (Psyren
        // ep1: s-2/203428/sub -> same master as the HD-1 server).
        if (!embedId.isNullOrBlank()) {
            try {
                val fastEmbed = "https://megaplay.buzz/stream/s-2/$embedId/$lang"
                if (seenEmbeds.add(fastEmbed)) {
                    if (resolveMegaplay(fastEmbed, watchUrl, "MegaPlay", if (lang == "dub") "Dub" else "Sub", callback, subtitleCallback)) {
                        found = true
                    }
                }
            } catch (e: Exception) {
                // Fall through to the site server list.
            }
        }

        // Site server list — try EVERY server and emit one link per server
        // (the working plugin lists ~16 sources; v1 stopped after the first
        // success, leaving the player with a single fallback).
        if (!ids.isNullOrBlank()) {
            try {
                val srv = app.get(
                    "$mainUrl/ajax/server/list?servers=${URLEncoder.encode(ids, "UTF-8")}",
                    headers = ajaxHeaders(watchUrl),
                ).parsed<AjaxResult>()
                val srvDoc = Jsoup.parse(srv.result ?: "")
                val servers = mutableListOf<ServerEntry>()
                for (typeDiv in srvDoc.select("div.type[data-type]")) {
                    val type = typeDiv.attr("data-type").trim().lowercase()
                    for (li in typeDiv.select("li[data-link-id]")) {
                        val linkId = li.attr("data-link-id").trim()
                        if (linkId.isEmpty()) continue
                        servers.add(ServerEntry(type, li.text().trim(), linkId))
                    }
                }
                // Requested audio first, then the other audio as fallback.
                val ordered = servers.filter { it.type == lang } + servers.filter { it.type != lang }
                for (server in ordered) {
                    try {
                        val got = app.get(
                            "$mainUrl/ajax/server?get=${URLEncoder.encode(server.linkId, "UTF-8")}",
                            headers = ajaxHeaders(watchUrl),
                        ).parsed<ServerGetResp>()
                        val embedUrl = got.result?.url?.trim() ?: continue
                        if (embedUrl.isEmpty() || !seenEmbeds.add(embedUrl)) continue
                        val tag = if (server.type == "dub") "Dub" else "Sub"
                        try {
                            if (resolveMegaplay(embedUrl, watchUrl, server.name.ifEmpty { "Server" }, tag, callback, subtitleCallback)) {
                                found = true
                            }
                        } catch (e: Exception) {
                            // This server failed — try the next one.
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            } catch (e: Exception) {
                // loadLinks must not throw.
            }
        }
        return found
    }
}
