package com.anikoto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
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
 * v3 (2026-11-11): stream resolution now matches Phisher's AniKoto v6
 * (the working plugin on the user's phone — decompiled from its .cs3).
 * The v2 chain (megaplay getSourcesNew -> AES decrypt -> raw master.m3u8
 * emitted as ONE link per server) returned "No streams found" on the
 * phone even though every step works from a VM. The working plugin
 * resolves the same megaplay embeds differently:
 *  - tries /stream/getSources THEN /stream/getSourcesNew, with
 *    ?id=<data-id>&type=sub|dub, and reads the "sources" field first,
 *    falling back to the AES-decrypted "enc" (same key/IV as v2).
 *  - SIGNS the master URL (HMAC-SHA256 ?token=, 90 s expiry) before
 *    use, the way the nexabloom CDN's signed-URL scheme expects.
 *  - EXPANDS the signed master into ONE LINK PER QUALITY VARIANT
 *    (1080p/720p/480p) instead of emitting the bare master — the
 *    player then gets "Vidstream-2 [Sub] · 1080p" style sources
 *    (Source 1 of 16 on the working plugin) with playback headers
 *    (Referer + Origin: megaplay.buzz) on every link. Segments on
 *    *.whereismyloot.site return 403 without that Referer.
 *  - Extra backend: the nekostream mapper
 *    (mapper.nekostream.site/api/mal/<mal>/<slug>/<ts>) as a fallback
 *    — direct .m3u8/.mp4 downloads and extra embeds per source.
 * Metadata (v2) is untouched: newAnimeLoadResponse + addEpisodes
 * Sub/Dub, Jikan enrichment, posters, dates.
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

    // AJAX headers for the megaplay getSources calls (Phisher's ajaxHeaders):
    // Origin: megaplay.buzz is part of what the endpoint expects.
    private fun megaAjaxHeaders(embedUrl: String): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to megaHost,
        "Referer" to embedUrl,
    )

    // Megaplay (megaplay.buzz) AES key/IV for the getSources "enc" field.
    // Identical in Phisher's AniKoto v6 (verified from its decompiled .cs3).
    private val megaKeyStr = "i?LMTAx0Q6,:}50U"
    private val megaIvStr = "W0;27ToaUpl_P%'c"
    private val megaReferer = "https://megaplay.buzz/"
    private val megaHost = "https://megaplay.buzz"
    // HMAC secret for the ?token= signature appended to the master URL
    // (Phisher's AniKoto v6, AnikotoExtractorsKt.MEGAPLAY_TOKEN_SECRET).
    private val megaTokenSecret = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

    // nekostream mapper — second stream backend (fallback).
    private val mapperBase = "https://mapper.nekostream.site"

    private val ua149 =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    // CDN host remap used for #-hash direct links (Phisher PROXY_MAP):
    // alternate player hosts -> the host that actually serves the file.
    private val proxyMap = mapOf(
        "vibeplayer.site" to "nanobyte.bigdreamsmalldih.site",
        "vault-01.uwucdn.top" to "uwu1.bigdreamsmalldih.site",
        "vault-02.uwucdn.top" to "uwu2.bigdreamsmalldih.site",
        "vault-03.uwucdn.top" to "uwu3.bigdreamsmalldih.site",
        "vault-04.uwucdn.top" to "uwu4.bigdreamsmalldih.site",
        "vault-05.uwucdn.top" to "uwu5.bigdreamsmalldih.site",
        "vault-06.uwucdn.top" to "uwu6.bigdreamsmalldih.site",
        "vault-07.uwucdn.top" to "uwu7.bigdreamsmalldih.site",
        "vault-08.uwucdn.top" to "uwu8.bigdreamsmalldih.site",
        "vault-09.uwucdn.top" to "uwu9.bigdreamsmalldih.site",
        "vault-10.uwucdn.top" to "uwu10.bigdreamsmalldih.site",
        "vault-11.uwucdn.top" to "uwu11.bigdreamsmalldih.site",
        "vault-12.uwucdn.top" to "uwu12.bigdreamsmalldih.site",
        "vault-13.uwucdn.top" to "uwu13.bigdreamsmalldih.site",
        "vault-14.uwucdn.top" to "uwu14.bigdreamsmalldih.site",
        "vault-15.uwucdn.top" to "uwu15.bigdreamsmalldih.site",
        "vault-16.uwucdn.top" to "uwu16.bigdreamsmalldih.site",
        "vault-99.uwucdn.top" to "uwu17.bigdreamsmalldih.site",
        "vault-10.owocdn.top" to "10.bigdreamsmalldih.site",
        "vault-11.owocdn.top" to "11.bigdreamsmalldih.site",
        "vault-12.owocdn.top" to "12.bigdreamsmalldih.site",
        "vault-13.owocdn.top" to "13.bigdreamsmalldih.site",
        "vault-14.owocdn.top" to "14.bigdreamsmalldih.site",
        "vault-15.owocdn.top" to "15.bigdreamsmalldih.site",
        "vault-16.owocdn.top" to "16.bigdreamsmalldih.site",
        "vault-99.owocdn.top" to "99.bigdreamsmalldih.site",
    )

    private fun playbackHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Origin" to megaHost,
        "Referer" to megaReferer,
    )

    private fun serverTypeLabel(type: String): String = when (type.lowercase()) {
        "hsub" -> "HSub"
        "dub" -> "Dub"
        else -> "Sub"
    }

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

    // Data passed to loadLinks. embed = episode_embed_id from anikotoapi.site
    // (fast path); lang = "sub"/"dub" (the DubStatus the user tapped).
    // mal/slug/ts = the episode's data-mal / data-slug / data-timestamp, for
    // the nekostream mapper fallback. Old v1 data ({ep, ids, url}) still
    // parses: embed/lang/mal/slug/ts default to null (lang -> "sub",
    // fast path + mapper skipped).
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeData(
        @JsonProperty("ep") val ep: Int? = null,
        @JsonProperty("ids") val ids: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("mal") val mal: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("ts") val ts: String? = null,
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

    /**
     * AES-256-CBC decrypt of megaplay's getSources "enc" (base64url).
     * Phisher's version: normalize the base64url alphabet to standard
     * base64 + pad to a multiple of 4 before decoding (more robust than
     * Base64.getUrlDecoder on unpadded input). Returns the decrypted
     * "file" URL (the master .m3u8), or null. Same key/IV as v2.
     */
    private fun decryptMegaplay(enc: String): String? {
        return try {
            val normalized = enc.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val ct = java.util.Base64.getDecoder().decode(padded)
            val keyBytes = megaKeyStr.toByteArray(Charsets.UTF_8).copyOf(32)
            val ivBytes = megaIvStr.toByteArray(Charsets.UTF_8).copyOf(16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes),
            )
            val json = String(cipher.doFinal(ct), Charsets.UTF_8)
            fileFromJsonText(json)
        } catch (e: Exception) {
            null
        }
    }

    /** Pull the "file" (master .m3u8) out of a getSources JSON text. */
    private fun fileFromJsonText(json: String): String? {
        return try {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                if (arr.length() == 0) return null
                arr.optJSONObject(0)?.optString("file")?.takeIf { it.isNotEmpty() }
            } else {
                JSONObject(trimmed).optString("file").takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * HMAC-SHA256 signature on the master URL (Phisher signMegaPlayUrl).
     * The CDN's signed-URL scheme: payload = "<expiry>|<hash1>/<hash2>"
     * (expiry = now + 90 s; the two 32-hex path segments), token =
     * base64url(payload) + "." + base64url(HMAC-SHA256(payload)).
     * Appended as ?token= (or &token=). URLs already carrying a token,
     * or without the two hex segments, are returned unchanged.
     */
    private fun signMegaPlayUrl(url: String): String {
        return try {
            if ("token=" in url) return url
            val m = Regex("/([a-f0-9]{32})/([a-f0-9]{32})/", RegexOption.IGNORE_CASE).find(url)
                ?: return url
            val payload =
                "${System.currentTimeMillis() / 1000 + 90}|${m.groupValues[1]}/${m.groupValues[2]}"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(megaTokenSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val sig = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            val enc = java.util.Base64.getUrlEncoder().withoutPadding()
            val token = enc.encodeToString(payload.toByteArray(Charsets.UTF_8)) + "." +
                enc.encodeToString(sig)
            url + (if ("?" in url) "&" else "?") + "token=$token"
        } catch (e: Exception) {
            url
        }
    }

    /** CDN host remap for #-hash direct links (Phisher proxyPlayerHost). */
    private fun proxyPlayerHost(url: String): String {
        var out = url
        for ((old, new) in proxyMap) out = out.replace(old, new)
        return out
    }

    /**
     * A #-hash direct link: the fragment after the first '#' is a base64
     * m3u8 URL (host-remapped). Returns it, or null if not a valid one.
     */
    private fun getHashM3u8(url: String): String? {
        return try {
            val encoded = url.substringAfter("#", "").substringBefore("#").trim()
            if (encoded.isEmpty()) return null
            val decoded = try {
                String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            } catch (e: Exception) {
                val normalized = encoded.replace('-', '+').replace('_', '/')
                val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
                String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
            }
            val it = proxyPlayerHost(decoded)
            if (it.startsWith("http") && ".m3u8" in it) it else null
        } catch (e: Exception) {
            null
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
        data class SiteEp(val num: Int, val ids: String, val ts: Long?, val hasDub: Boolean, val mal: String, val slug: String)
        val siteEps = mutableListOf<SiteEp>()
        for (a in epDoc.select("a[data-id][data-num][data-ids]")) {
            val num = a.attr("data-num").toIntOrNull() ?: continue
            val ids = a.attr("data-ids").trim()
            if (ids.isEmpty()) continue
            val ts = a.attr("data-timestamp").toLongOrNull()
            val hasDub = a.attr("data-dub").trim() == "1"
            val mal = a.attr("data-mal").trim()
            val slug = a.attr("data-slug").trim().ifEmpty { num.toString() }
            siteEps.add(SiteEp(num, ids, ts, hasDub, mal, slug))
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
            val subData = toJson(EpisodeData(ep = se.num, ids = se.ids, url = url, embed = embedId, lang = "sub", mal = se.mal, slug = se.slug, ts = se.ts?.toString())) ?: continue
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
                val dubData = toJson(EpisodeData(ep = se.num, ids = se.ids, url = url, embed = embedId, lang = "dub", mal = se.mal, slug = se.slug, ts = se.ts?.toString())) ?: continue
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

    // ---- streams ----

    /**
     * Resolve one megaplay/vidwish/vidtube embed URL and emit its streams.
     * Phisher's extractMegaPlayUrl, adapted: fetch the embed page ->
     * #megaplay-player data-id -> /stream/getSources THEN
     * /stream/getSourcesNew (?id=<data-id>&type=sub|dub) -> read the
     * "sources" field first, else AES-decrypt "enc" -> master .m3u8 ->
     * SIGN it -> expand it into one link per quality variant. Returns
     * true if at least one link was emitted.
     */
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
            val streamId = player.attr("data-id").trim()
            if (streamId.isEmpty()) return false
            val type = if ("/dub" in embedUrl) "dub" else "sub"
            val host = "https://" + try { URL(embedUrl).host } catch (e: Exception) { "megaplay.buzz" }
            // megaplay.buzz tries getSources first; other hosts getSourcesNew first.
            val endpoints = if ("megaplay.buzz" in host) {
                listOf("/stream/getSources", "/stream/getSourcesNew")
            } else {
                listOf("/stream/getSourcesNew", "/stream/getSources")
            }
            for (path in endpoints) {
                try {
                    val text = app.get(
                        "$host$path?id=$streamId&type=$type",
                        headers = megaAjaxHeaders(embedUrl),
                    ).text
                    val json = JSONObject(text)
                    // "sources" first (object {"file":...} or array [{file:...}]).
                    var file: String? = null
                    when (val sources = json.opt("sources")) {
                        is JSONObject -> file = sources.optString("file").takeIf { it.isNotEmpty() }
                        is org.json.JSONArray -> {
                            if (sources.length() > 0) {
                                file = sources.optJSONObject(0)?.optString("file")?.takeIf { it.isNotEmpty() }
                            }
                        }
                    }
                    if (file.isNullOrBlank()) {
                        val enc = json.optString("enc")
                        if (enc.isNotEmpty()) file = decryptMegaplay(enc)
                    }
                    val master = file?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val signed = signMegaPlayUrl(master)
                    val label = "$serverName [$tag]"
                    // Subtitles (best-effort: mysubsplay.com 403s from some networks).
                    try {
                        val tracks = json.optJSONArray("tracks")
                        if (tracks != null) {
                            for (i in 0 until tracks.length()) {
                                val t = tracks.optJSONObject(i) ?: continue
                                val kind = t.optString("kind")
                                if (kind != "captions" && kind != "subtitles") continue
                                val f = t.optString("file").ifEmpty { t.optString("src") }
                                    .ifEmpty { t.optString("url") }
                                if (f.isEmpty()) continue
                                val trackUrl = if (f.startsWith("http")) f else host + "/" + f.removePrefix("/")
                                subtitleCallback(SubtitleFile(t.optString("label").ifEmpty { "Unknown" }, trackUrl))
                            }
                        }
                    } catch (e: Exception) {
                        // Subtitles must never kill the stream.
                    }
                    if (expandMaster(signed, label, callback)) return true
                } catch (e: Exception) {
                    continue
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fetch a (signed) master playlist and emit ONE link per quality
     * variant — the working plugin's M3u8Helper.generateM3u8 behaviour.
     * Each variant URL is resolved relative to the master URL and tagged
     * with #<serverTag> (server name, spaces stripped). Returns true if
     * at least one link was emitted. If the URL is not a master playlist
     * (no #EXT-X-STREAM-INF), it is emitted as-is as a single link.
     */
    private suspend fun expandMaster(
        masterUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val text = app.get(masterUrl, headers = playbackHeaders()).text
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
                        val serverTag = label.substringBefore(" [").replace(" ", "")
                        val tagged = if ("#" in abs) abs else "$abs#$serverTag"
                        try {
                            callback(
                                newExtractorLink(
                                    source = "Anikoto",
                                    name = label,
                                    url = tagged,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.quality = quality
                                    this.referer = megaReferer
                                    this.headers = playbackHeaders()
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
                    val serverTag = label.substringBefore(" [").replace(" ", "")
                    val tagged = if ("#" in masterUrl) masterUrl else "$masterUrl#$serverTag"
                    callback(
                        newExtractorLink(
                            source = "Anikoto",
                            name = label,
                            url = tagged,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = megaReferer
                            this.headers = playbackHeaders()
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

    /**
     * Resolve one embed URL from the site server list or the mapper.
     * Phisher's resolveEmbed: a #-hash URL is a direct base64 m3u8 link
     * (emitted as-is); megaplay/vidwish/vidtube go through the megaplay
     * resolver; anything else goes through the generic extractor.
     */
    private suspend fun resolveEmbed(
        embedUrlRaw: String,
        referer: String,
        serverName: String,
        serverType: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        return try {
            val normalized = when {
                embedUrlRaw.startsWith("//") -> "https:$embedUrlRaw"
                embedUrlRaw.startsWith("/") -> mainUrl + embedUrlRaw
                else -> embedUrlRaw
            }
            val typeLabel = serverTypeLabel(serverType)
            // #-hash direct link.
            val hashM3u8 = getHashM3u8(normalized)
            if (hashM3u8 != null) {
                val serverTag = serverName.replace(" ", "")
                val tagged = if ("#" in hashM3u8) hashM3u8 else "$hashM3u8#$serverTag"
                return try {
                    callback(
                        newExtractorLink(
                            source = "Anikoto",
                            name = "$serverName [$typeLabel]",
                            url = tagged,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = normalized
                            this.headers = playbackHeaders()
                        }
                    )
                    true
                } catch (e: Exception) {
                    false
                }
            }
            val domain = try { URL(normalized).host } catch (e: Exception) { "" }
            val isMega = "megaplay" in domain || "vidwish" in domain || "vidtube" in domain
            if (isMega) {
                return resolveMegaplay(normalized, referer, serverName, typeLabel, callback, subtitleCallback)
            }
            // Generic extractor for anything else.
            return try {
                loadExtractor(normalized, subtitleCallback, callback)
            } catch (e: Exception) {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * nekostream mapper fallback (Phisher's resolveMapperServers).
     * GET mapper.nekostream.site/api/mal/<mal>/<slug>/<ts> — for each
     * source, resolve its embed URL and emit any direct .m3u8/.mp4
     * downloads. Returns true if at least one link was emitted.
     */
    private suspend fun resolveMapperServers(
        malId: String?,
        slug: String?,
        timestamp: String?,
        audioType: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        if (malId.isNullOrBlank() || slug.isNullOrBlank() || timestamp.isNullOrBlank()) return false
        return try {
            val text = app.get(
                "$mapperBase/api/mal/$malId/$slug/$timestamp",
                headers = mapOf(
                    "User-Agent" to ua149,
                    "Accept" to "application/json",
                ),
            ).text
            val root = JSONObject(text)
            var found = false
            val keys = root.keys()
            while (keys.hasNext()) {
                val sourceKey = keys.next()
                if (sourceKey == "status") continue
                val sourceObj = root.optJSONObject(sourceKey) ?: continue
                try {
                    var audioObj = sourceObj.optJSONObject(audioType)
                    if (audioObj == null) audioObj = sourceObj.optJSONObject("sub")
                    if (audioObj == null) continue
                    val sName = when (sourceKey.lowercase().trim()) {
                        "vibe-stream" -> "Vibe-Stream"
                        "gogoanime" -> "Vidstream"
                        "kiwi" -> "Kiwi-Stream"
                        else -> sourceKey.replaceFirstChar { it.uppercase() }
                    }
                    val embedUrl = audioObj.optString("url").takeIf { it.isNotEmpty() }
                    if (embedUrl != null) {
                        try {
                            if (resolveEmbed(embedUrl, referer, sName, audioType, callback, subtitleCallback)) {
                                found = true
                            }
                        } catch (e: Exception) {
                            // This source failed — try the next one.
                        }
                    }
                    val downloads = audioObj.optJSONObject("download")
                    if (downloads != null) {
                        val dkeys = downloads.keys()
                        while (dkeys.hasNext()) {
                            val resKey = dkeys.next()
                            val dlUrl = downloads.optString(resKey)
                            if (dlUrl.isEmpty()) continue
                            val typeLabel = serverTypeLabel(audioType)
                            val dlName = "$sName [$resKey] [$typeLabel]"
                            try {
                                if (".m3u8" in dlUrl) {
                                    callback(
                                        newExtractorLink(
                                            source = "Anikoto",
                                            name = dlName,
                                            url = dlUrl,
                                            type = ExtractorLinkType.M3U8,
                                        ) {
                                            this.referer = megaReferer
                                            this.headers = playbackHeaders()
                                        }
                                    )
                                    found = true
                                } else if (".mp4" in dlUrl) {
                                    callback(
                                        newExtractorLink(
                                            source = "Anikoto",
                                            name = dlName,
                                            url = dlUrl,
                                            type = ExtractorLinkType.VIDEO,
                                        ) {
                                            this.referer = megaReferer
                                            this.headers = playbackHeaders()
                                        }
                                    )
                                    found = true
                                }
                            } catch (e: Exception) {
                                // This download failed — try the next one.
                            }
                        }
                    }
                } catch (e: Exception) {
                    // This source failed — try the next one.
                }
            }
            found
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

        // Site server list — try EVERY server (requested audio first,
        // hsub grouped with sub) and resolve each embed. The working
        // plugin lists ~16 sources because every server expands into
        // per-variant links; v2 emitted one bare master per server.
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
                val ordered = servers.filter { it.type == lang || (lang == "sub" && it.type == "hsub") } +
                    servers.filter { it.type != lang && !(lang == "sub" && it.type == "hsub") }
                for (server in ordered) {
                    try {
                        val got = app.get(
                            "$mainUrl/ajax/server?get=${URLEncoder.encode(server.linkId, "UTF-8")}",
                            headers = ajaxHeaders(watchUrl),
                        ).parsed<ServerGetResp>()
                        val embedUrl = got.result?.url?.trim() ?: continue
                        if (embedUrl.isEmpty() || !seenEmbeds.add(embedUrl)) continue
                        try {
                            if (resolveEmbed(embedUrl, watchUrl, server.name.ifEmpty { "Server" }, server.type, callback, subtitleCallback)) {
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

        // Mapper fallback (second backend, independent of anikoto.cz).
        try {
            if (resolveMapperServers(ep.mal, ep.slug, ep.ts, lang, watchUrl, callback, subtitleCallback)) {
                found = true
            }
        } catch (e: Exception) {
            // Mapper failure must not kill the site-server links.
        }
        return found
    }
}
