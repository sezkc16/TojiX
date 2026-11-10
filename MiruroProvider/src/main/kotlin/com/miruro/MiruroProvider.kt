package com.miruro

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Miruro — free anime streaming (Sub & Dub).
 *
 * Metadata comes from the public AniList GraphQL API (no auth), streams come
 * from the justanime core API (the same megaplay/animegg servers Miruro's own
 * site uses — proven by the Mangayomi Miruro extension, v6.1.15).
 * Miruro's own pipe API is Cloudflare-403 outside a browser, so it is not
 * used. Only megaplay + animegg work (26 other provider names 404,
 * anineko/animepahe 502).
 *
 * Fallback (v4): when justanime returns nothing (the core.justanime.to API
 * is unreachable from some networks — confirmed on the user's phone on both
 * mobile data and Wi-Fi), loadLinks falls back to the Anikoto megaplay
 * chain (anikoto.cz AJAX -> megaplay.buzz embed -> getSourcesNew ->
 * AES-256-CBC decrypt -> master.m3u8), searched by the anime title. The
 * key/IV are the same megaplay pair used by the Anikoto provider.
 *
 * v5 (2026-11-11): the fallback now matches Anikoto v3's stream emission
 * exactly (the pattern proven working on the user's phone, where Anikoto
 * v2's single bare master per server returned "No streams found"):
 * getSources THEN getSourcesNew (?id=<id>&type=sub|dub, Origin:
 * megaplay.buzz), "sources" field first else AES-decrypted "enc"
 * (robust base64 normalize+pad), HMAC-SHA256 ?token= signing of the
 * master, and expansion into one link per quality variant, each carrying
 * Referer + Origin (playback headers) and a #MiruroMegaPlay tag.
 */
class MiruroProvider : MainAPI() {

    override var mainUrl = "https://miruro.tv"
    override var name = "Miruro"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    private val anilistUrl = "https://graphql.anilist.co"
    private val justAnimeApi = "https://core.justanime.to/api/watch"

    // Anikoto fallback (only used when justanime returns nothing).
    private val anikotoUrl = "https://anikoto.cz"
    private val megaKeyStr = "i?LMTAx0Q6,:}50U"
    private val megaIvStr = "W0;27ToaUpl_P%'c"
    private val megaReferer = "https://megaplay.buzz/"
    private val megaHost = "https://megaplay.buzz"
    // HMAC secret for the ?token= signature on the master URL (same value
    // as Anikoto v3 / Phisher's AniKoto v6, MEGAPLAY_TOKEN_SECRET).
    private val megaTokenSecret = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val jsonHeaders = mapOf(
        "User-Agent" to ua,
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    )

    private val jaHeaders = mapOf(
        "User-Agent" to ua,
        "Origin" to "https://justanime.to",
        "Referer" to "https://justanime.to/",
        "Accept" to "application/json",
    )

    // ---- AniList models ----

    data class AniTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
    )

    data class AniCover(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("extraLarge") val extraLarge: String? = null,
    )

    data class AniNext(
        @JsonProperty("episode") val episode: Int? = null,
    )

    data class AniMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: AniTitle? = null,
        @JsonProperty("coverImage") val coverImage: AniCover? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: AniNext? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("seasonYear") val seasonYear: Int? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("averageScore") val averageScore: Int? = null,
    )

    data class AniAiring(
        @JsonProperty("media") val media: AniMedia? = null,
    )

    data class AniPageInfo(
        @JsonProperty("hasNextPage") val hasNextPage: Boolean? = null,
    )

    data class AniPage(
        @JsonProperty("pageInfo") val pageInfo: AniPageInfo? = null,
        @JsonProperty("media") val media: List<AniMedia>? = null,
        @JsonProperty("airingSchedules") val airingSchedules: List<AniAiring>? = null,
    )

    data class AniData(
        @JsonProperty("Page") val Page: AniPage? = null,
        @JsonProperty("Media") val Media: AniMedia? = null,
    )

    data class AniResponse(
        @JsonProperty("data") val data: AniData? = null,
    )

    // ---- justanime models ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JaSource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("isM3U8") val isM3U8: Boolean? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
        @JsonProperty("sig") val sig: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JaTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("default") val default: Boolean? = null,
        @JsonProperty("isDefault") val isDefault: Boolean? = null,
        @JsonProperty("sig") val sig: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JaType(
        @JsonProperty("sources") val sources: List<JaSource>? = null,
        @JsonProperty("subtitles") val subtitles: List<JaTrack>? = null,
        @JsonProperty("tracks") val tracks: List<JaTrack>? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
        @JsonProperty("intro") val intro: Any? = null,
        @JsonProperty("outro") val outro: Any? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JaWatch(
        @JsonProperty("sub") val sub: JaType? = null,
        @JsonProperty("dub") val dub: JaType? = null,
        @JsonProperty("error") val error: String? = null,
        @JsonProperty("exp") val exp: String? = null,
    )

    // ---- Anikoto fallback models ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AkAjaxResult(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AkServerGetResult(
        @JsonProperty("url") val url: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AkServerGetResp(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: AkServerGetResult? = null,
    )

    private data class AkServerEntry(val type: String, val name: String, val linkId: String)

    // Data passed to loadLinks.
    data class EpisodeData(
        @JsonProperty("id") val id: Int,
        @JsonProperty("num") val num: Int,
        @JsonProperty("title") val title: String? = null,
    )

    private fun toJson(data: EpisodeData): String? {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
        } catch (e: Exception) { null }
    }

    private fun preferredTitle(t: AniTitle?): String {
        if (t == null) return "Unknown"
        return t.english?.takeIf { it.isNotEmpty() }
            ?: t.romaji?.takeIf { it.isNotEmpty() }
            ?: t.native?.takeIf { it.isNotEmpty() }
            ?: "Unknown"
    }

    private fun cleanDescription(raw: String?): String? {
        return raw?.replace(Regex("<[^>]+>"), "")?.replace(Regex("\\s+"), " ")?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun qualityFromHeight(height: Int?): Int {
        return when {
            height == null -> Qualities.Unknown.value
            height >= 2160 -> Qualities.P2160.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 480 -> Qualities.P480.value
            height >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityFromLabel(label: String?): Int {
        if (label == null) return Qualities.Unknown.value
        val num = Regex("(\\d{3,4})").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return qualityFromHeight(num)
    }

    private suspend fun anilist(query: String, vars: Map<String, Any?> = emptyMap()): AniResponse? {
        return try {
            app.post(
                anilistUrl,
                headers = jsonHeaders,
                json = mapOf("query" to query, "variables" to vars)
            ).parsed()
        } catch (e: Exception) { null }
    }

    private fun parseItem(m: AniMedia): com.lagradost.cloudstream3.SearchResponse? {
        val id = m.id ?: return null
        val title = preferredTitle(m.title)
        if (title == "Unknown") return null
        val url = "$mainUrl/info/$id"
        val poster = m.coverImage?.extraLarge ?: m.coverImage?.large
        return if (m.format == "MOVIE") {
            newMovieSearchResponse(title, url, TvType.AnimeMovie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
        }
    }

    private fun listQuery(sort: String): String {
        return "query(\$p:Int){Page(page:\$p,perPage:20){pageInfo{hasNextPage}" +
            "media(sort:[$sort],type:ANIME,isAdult:false){id title{romaji english}coverImage{large}format}}}"
    }

    override val mainPage = mainPageOf(
        "trend" to "Trending",
        "pop" to "Popular",
        "score" to "Top Rated",
        "latest" to "Latest Episodes",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list: List<com.lagradost.cloudstream3.SearchResponse>
        var hasNext = false
        when (request.data) {
            "latest" -> {
                val res = anilist(
                    "query(\$p:Int){Page(page:\$p,perPage:40){pageInfo{hasNextPage}" +
                        "airingSchedules(notYetAired:false,sort:[TIME_DESC])" +
                        "{media{id title{romaji english}coverImage{large}format}}}}",
                    mapOf("p" to page)
                )
                val seen = mutableSetOf<Int>()
                val items = mutableListOf<AniMedia>()
                for (a in res?.data?.Page?.airingSchedules ?: emptyList()) {
                    val m = a.media ?: continue
                    val id = m.id ?: continue
                    if (seen.add(id)) items.add(m)
                    if (items.size >= 20) break
                }
                list = items.mapNotNull { parseItem(it) }
                hasNext = res?.data?.Page?.pageInfo?.hasNextPage == true
            }
            else -> {
                val sort = when (request.data) {
                    "pop" -> "POPULARITY_DESC"
                    "score" -> "SCORE_DESC"
                    else -> "TRENDING_DESC"
                }
                val res = anilist(listQuery(sort), mapOf("p" to page))
                list = (res?.data?.Page?.media ?: emptyList()).mapNotNull { parseItem(it) }
                hasNext = res?.data?.Page?.pageInfo?.hasNextPage == true
            }
        }
        return newHomePageResponse(HomePageList(request.name, list), hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val res = anilist(
            "query(\$p:Int,\$n:Int,\$q:String){Page(page:\$p,perPage:\$n){pageInfo{hasNextPage}" +
                "media(search:\$q,type:ANIME,isAdult:false){id title{romaji english}coverImage{large}format}}}",
            mapOf("p" to page, "n" to 20, "q" to query.trim())
        ) ?: return null
        return (res.data?.Page?.media ?: emptyList())
            .mapNotNull { parseItem(it) }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("/info/").substringBefore("/").substringBefore("?").trim()
            .toIntOrNull() ?: return null
        val res = anilist(
            "{Media(id:$id,type:ANIME){id title{romaji english native}" +
                " coverImage{large extraLarge} bannerImage description status episodes" +
                " nextAiringEpisode{episode} genres seasonYear format averageScore}}"
        ) ?: return null
        val m = res.data?.Media ?: return null
        val title = preferredTitle(m.title)
        if (title == "Unknown") return null
        val poster = m.coverImage?.extraLarge ?: m.coverImage?.large
        val plot = cleanDescription(m.description)
        val showStatus = when (m.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        if (m.format == "MOVIE") {
            val dataJson = toJson(EpisodeData(id = id, num = 1, title = title)) ?: return null
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, dataJson) {
                this.posterUrl = poster
                this.plot = plot
                this.year = m.seasonYear
                this.tags = m.genres
            }
        }

        // Episode count rule (same as Mangayomi): airing shows use the next
        // airing episode - 1; finished shows use the episodes field.
        var epCount = 0
        if (m.status == "RELEASING") {
            val next = m.nextAiringEpisode?.episode
            epCount = if (next != null && next > 1) next - 1 else (m.episodes ?: 0)
        } else {
            epCount = m.episodes ?: 0
        }
        if (epCount < 0) epCount = 0

        val episodes = mutableListOf<Episode>()
        for (n in 1..epCount) {
            val dataJson = toJson(EpisodeData(id = id, num = n, title = title)) ?: continue
            episodes.add(newEpisode(dataJson) {
                this.name = "Episode $n"
                this.episode = n
            })
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = m.seasonYear
            this.tags = m.genres
            this.showStatus = showStatus
        }
    }

    // ---- Stream resolution ----

    // Resolve a megaplay master playlist into its quality variants.
    // Variant URLs are relative; the master's ?token= is only needed for the
    // master request itself (verified: variant fetch works without it).
    private suspend fun resolveMasterPlaylist(masterUrl: String, referer: String): List<Pair<String, Int>> {
        return try {
            val body = app.get(masterUrl, headers = mapOf("User-Agent" to ua, "Referer" to referer)).text
            if (!body.contains("#EXT-X-STREAM-INF")) return emptyList()
            val variants = mutableListOf<Pair<String, Int>>()
            val lines = body.split("\n")
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                for (j in i + 1 until lines.size) {
                    val u = lines[j].trim()
                    if (u.isEmpty() || u.startsWith("#")) continue
                    val full = try {
                        URL(URL(masterUrl), u).toString()
                    } catch (e: Exception) {
                        u
                    }
                    variants.add(full to qualityFromHeight(height))
                    break
                }
            }
            variants.sortByDescending { it.second }
            variants
        } catch (e: Exception) { emptyList() }
    }

    // ---- Anikoto fallback (only used when justanime returns nothing) ----

    private fun akPageHeaders(referer: String? = null): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        if (referer != null) h["Referer"] = referer
        return h
    }

    private fun akAjaxHeaders(referer: String? = null): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to ua,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
        if (referer != null) h["Referer"] = referer
        return h
    }

    // AJAX headers for the megaplay getSources calls (Anikoto v3):
    // Origin: megaplay.buzz is part of what the endpoint expects.
    private fun megaAjaxHeaders(embedUrl: String): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to megaHost,
        "Referer" to embedUrl,
    )

    // Playback headers carried on every variant link (Anikoto v3): the
    // segments on *.whereismyloot.site return 403 without this Referer.
    private fun playbackHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Origin" to megaHost,
        "Referer" to megaReferer,
    )

    /**
     * AES-256-CBC decrypt of megaplay's getSources "enc" (base64url).
     * Anikoto v3 version: normalize the base64url alphabet to standard
     * base64 + pad to a multiple of 4 before decoding (more robust than
     * Base64.getUrlDecoder on unpadded input). Returns the decrypted
     * JSON text, or null. Same key/IV as before.
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
            String(cipher.doFinal(ct), Charsets.UTF_8)
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
     * HMAC-SHA256 signature on the master URL (Anikoto v3 / Phisher
     * signMegaPlayUrl). The CDN's signed-URL scheme: payload =
     * "<expiry>|<hash1>/<hash2>" (expiry = now + 90 s; the two 32-hex
     * path segments), token = base64url(payload) + "."
     * + base64url(HMAC-SHA256(payload)). Appended as ?token= (or
     * &token=). URLs already carrying a token, or without the two hex
     * segments, are returned unchanged.
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

    /** Normalize a title for matching (lowercase, strip punctuation). */
    private fun normalizeTitle(raw: String): String {
        return raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Resolve one megaplay embed URL and emit its streams — Anikoto v3's
     * resolveMegaplay, adapted. Tries /stream/getSources THEN
     * /stream/getSourcesNew (?id=<data-id>&type=sub|dub, Origin:
     * megaplay.buzz), reads the "sources" field first, else AES-decrypts
     * "enc" -> master .m3u8 -> SIGNS it -> expands it into one link per
     * quality variant (playback headers on every link). Returns true if
     * at least one link was emitted.
     */
    private suspend fun resolveAnikotoMegaplay(
        embedUrl: String,
        watchUrl: String,
        tag: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        return try {
            val embedHtml = app.get(embedUrl, headers = akPageHeaders(watchUrl)).text
            val embedDoc = Jsoup.parse(embedHtml)
            val player = embedDoc.selectFirst("#megaplay-player") ?: return false
            val fileId = player.attr("data-id").trim()
            val realId = player.attr("data-realid").trim()
            // data-id first (full subtitle tracks); realid as fallback.
            val ids = listOf(fileId, realId).filter { it.isNotEmpty() }.distinct()
            val type = if (tag == "DUB") "dub" else "sub"
            val host = "https://" + try { URL(embedUrl).host } catch (e: Exception) { "megaplay.buzz" }
            val endpoints = if ("megaplay.buzz" in host) {
                listOf("/stream/getSources", "/stream/getSourcesNew")
            } else {
                listOf("/stream/getSourcesNew", "/stream/getSources")
            }
            for (id in ids) {
                for (path in endpoints) {
                    try {
                        val text = app.get(
                            "$host$path?id=$id&type=$type",
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
                            if (enc.isNotEmpty()) {
                                val plain = decryptMegaplay(enc)
                                if (plain != null) file = fileFromJsonText(plain)
                            }
                        }
                        val master = file?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        val signed = signMegaPlayUrl(master)
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
                                    subtitleCallback(SubtitleFile(t.optString("label").ifEmpty { "Unknown" }, f))
                                }
                            }
                        } catch (e: Exception) {
                            // Subtitles must never kill the stream.
                        }
                        val variants = resolveMasterPlaylist(signed, megaReferer)
                        if (variants.isNotEmpty()) {
                            var emitted = false
                            for ((vUrl, vQ) in variants) {
                                try {
                                    val tagged = if ("#" in vUrl) vUrl else "$vUrl#MiruroMegaPlay"
                                    callback(
                                        newExtractorLink(
                                            source = "Miruro",
                                            name = "Miruro MegaPlay [$tag]",
                                            url = tagged,
                                            type = ExtractorLinkType.M3U8,
                                        ) {
                                            this.quality = vQ
                                            this.referer = megaReferer
                                            this.headers = playbackHeaders()
                                        }
                                    )
                                    emitted = true
                                } catch (e: Exception) { continue }
                            }
                            if (emitted) return true
                        }
                        // No variants (or none emitted): emit the master URL directly.
                        try {
                            val tagged = if ("#" in signed) signed else "$signed#MiruroMegaPlay"
                            callback(
                                newExtractorLink(
                                    source = "Miruro",
                                    name = "Miruro MegaPlay [$tag]",
                                    url = tagged,
                                    type = ExtractorLinkType.M3U8,
                                ) {
                                    this.quality = Qualities.P1080.value
                                    this.referer = megaReferer
                                    this.headers = playbackHeaders()
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
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /** Anikoto fallback: search anikoto.cz by title and resolve the episode
     *  through its megaplay chain. Returns true if any stream was emitted. */
    private suspend fun resolveAnikoto(
        title: String,
        num: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        try {
            // 1. Search anikoto.cz by title.
            val searchHtml = app.get(
                "$anikotoUrl/filter?keyword=${URLEncoder.encode(title.trim(), "UTF-8")}",
                headers = akPageHeaders(),
            ).text
            val searchDoc = Jsoup.parse(searchHtml)
            val results = mutableListOf<Pair<String, String>>()
            for (item in searchDoc.select("div#list-items div.item")) {
                val posterA = item.selectFirst("div.ani.poster a[href]") ?: continue
                var href = posterA.attr("href").trim()
                if (href.isEmpty()) continue
                href = href.substringBefore("/ep-")
                val t = item.selectFirst("a.name.d-title")?.text()?.trim()
                    ?: posterA.selectFirst("img")?.attr("alt")?.trim()
                    ?: continue
                if (t.isEmpty()) continue
                results.add(t to href)
            }
            if (results.isEmpty()) return false
            // Prefer a normalized exact match, then a contains match, else first.
            val normQ = normalizeTitle(title)
            val watchHref = results.firstOrNull { normalizeTitle(it.first) == normQ }?.second
                ?: results.firstOrNull {
                    val n = normalizeTitle(it.first)
                    n.contains(normQ) || normQ.contains(n)
                }?.second
                ?: results.first().second
            val watchUrl = if (watchHref.startsWith("http")) watchHref else anikotoUrl + watchHref

            // 2. Watch page -> animeId.
            val watchHtml = app.get(watchUrl, headers = akPageHeaders()).text
            val watchDoc = Jsoup.parse(watchHtml)
            val animeId = watchDoc.selectFirst("[data-anime-id]")?.attr("data-anime-id")?.trim()
                .takeIf { !it.isNullOrEmpty() } ?: return false

            // 3. Episode list -> data-ids token for this episode number.
            val epResult = try {
                app.get(
                    "$anikotoUrl/ajax/episode/list/$animeId",
                    headers = akAjaxHeaders(watchUrl),
                ).parsed<AkAjaxResult>().result
            } catch (e: Exception) {
                null
            } ?: return false
            val epDoc = Jsoup.parse(epResult)
            var ids: String? = null
            for (a in epDoc.select("a[data-id][data-num][data-ids]")) {
                if (a.attr("data-num").toIntOrNull() == num) {
                    ids = a.attr("data-ids").trim().takeIf { it.isNotEmpty() }
                    break
                }
            }
            val token = ids ?: return false

            // 4. Server list -> SUB first (prefer HD-1), else DUB.
            val srvResult = try {
                app.get(
                    "$anikotoUrl/ajax/server/list?servers=${URLEncoder.encode(token, "UTF-8")}",
                    headers = akAjaxHeaders(watchUrl),
                ).parsed<AkAjaxResult>().result
            } catch (e: Exception) {
                null
            } ?: return false
            val srvDoc = Jsoup.parse(srvResult)
            val servers = mutableListOf<AkServerEntry>()
            for (typeDiv in srvDoc.select("div.type[data-type]")) {
                val type = typeDiv.attr("data-type").trim().lowercase()
                for (li in typeDiv.select("li[data-link-id]")) {
                    val linkId = li.attr("data-link-id").trim()
                    if (linkId.isEmpty()) continue
                    servers.add(AkServerEntry(type, li.text().trim(), linkId))
                }
            }
            if (servers.isEmpty()) return false

            // 5. Server get -> megaplay embed -> resolve it. Try servers in
            //    order (SUB first, HD-1 first within SUB) until one resolves.
            val seenEmbeds = mutableSetOf<String>()
            for (wantType in listOf("sub", "dub")) {
                val typed = servers.filter { it.type == wantType }
                val ordered = typed.sortedWith(
                    compareByDescending<AkServerEntry> { it.name.contains("HD-1", ignoreCase = true) }
                        .thenBy { typed.indexOf(it) }
                )
                for (server in ordered) {
                    try {
                        val got = app.get(
                            "$anikotoUrl/ajax/server?get=${URLEncoder.encode(server.linkId, "UTF-8")}",
                            headers = akAjaxHeaders(watchUrl),
                        ).parsed<AkServerGetResp>()
                        val embedUrl = got.result?.url?.trim() ?: continue
                        if (!seenEmbeds.add(embedUrl)) continue
                        val tag = if (wantType == "dub") "DUB" else "SUB"
                        try {
                            if (resolveAnikotoMegaplay(embedUrl, watchUrl, tag, callback, subtitleCallback)) {
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
                if (found) break
            }
        } catch (e: Exception) {
            // Fallback must never throw out of loadLinks.
        }
        return found
    }

    private suspend fun resolveProvider(
        provider: String,
        id: Int,
        num: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        val data: JaWatch = try {
            app.get("$justAnimeApi/$id/episode/$num/$provider", headers = jaHeaders).parsed()
        } catch (e: Exception) { return false }
        if (data.error != null) return false

        val referer = if (provider == "megaplay") "https://megaplay.buzz/" else "https://www.animegg.org/"
        var found = false

        for ((audio, typeData) in listOf("sub" to data.sub, "dub" to data.dub)) {
            if (typeData == null) continue
            val tag = audio.uppercase()

            // Subtitles (captions/subtitles only — skip "thumbnails" tracks).
            // Wrapped in try/catch: a subtitleCallback failure must never kill
            // stream emission (subtitles are best-effort).
            try {
                val tracks = typeData.subtitles ?: typeData.tracks ?: emptyList()
                var hasDefault = false
                for (t in tracks) {
                    val kind = t.kind
                    if (kind != null && kind != "captions" && kind != "subtitles") continue
                    val file = t.file ?: t.src ?: t.url ?: continue
                    val label = t.label ?: "Unknown"
                    val isDefault = t.default == true || t.isDefault == true
                    if (isDefault) hasDefault = true
                    subtitleCallback(SubtitleFile(label, file))
                }
                // (CloudStream picks a default track itself; no marking needed.)
            } catch (e: Exception) {
                // Skip subtitles and keep emitting streams.
            }

            val typeHeaders = typeData.headers ?: emptyMap()
            for (s in typeData.sources ?: emptyList()) {
                val streamUrl = s.url ?: continue
                val srcHeaders = s.headers ?: typeHeaders
                val origin = srcHeaders["Origin"]
                val headers = mutableMapOf("User-Agent" to ua)
                if (origin != null) headers["Origin"] = origin

                val isM3u8 = s.isM3U8 == true || streamUrl.contains(".m3u8")
                if (provider == "megaplay" && isM3u8 && (s.quality == null || s.quality == "auto")) {
                    val variants = resolveMasterPlaylist(streamUrl, referer)
                    if (variants.isNotEmpty()) {
                        for ((vUrl, vQ) in variants) {
                            try {
                                callback(
                                    newExtractorLink(
                                        source = "Miruro",
                                        name = "MegaPlay [$tag]",
                                        url = vUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = vQ
                                        this.referer = referer
                                        this.headers = headers
                                    }
                                )
                            } catch (e: Exception) { continue }
                            found = true
                        }
                        continue
                    }
                }
                val q = qualityFromLabel(s.quality)
                try {
                    callback(
                        newExtractorLink(
                            source = "Miruro",
                            name = if (provider == "megaplay") "MegaPlay [$tag]" else "AnimeGG [$tag]",
                            url = streamUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = q
                            this.referer = referer
                            this.headers = headers
                        }
                    )
                } catch (e: Exception) { continue }
                found = true
            }
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        var found = false
        // megaplay first (HLS, usually 1080p), animegg second (MP4 alternates).
        // Each provider is isolated: one provider's exception must never kill
        // the other provider or propagate out of loadLinks.
        for (provider in listOf("megaplay", "animegg")) {
            try {
                if (resolveProvider(provider, ep.id, ep.num, callback, subtitleCallback)) found = true
            } catch (e: Exception) {
                // Provider failed — move on to the next one.
            }
        }
        // justanime unreachable (blocked on the user's network): fall back to
        // the Anikoto megaplay chain, searched by the anime title.
        if (!found) {
            val t = ep.title?.trim().takeIf { !it.isNullOrBlank() }
            if (t != null) {
                try {
                    if (resolveAnikoto(t, ep.num, subtitleCallback, callback)) found = true
                } catch (e: Exception) {
                    // Fallback failed — loadLinks must not throw.
                }
            }
        }
        return found
    }
}
