package com.netmirror

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
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
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * NetMirror — OTT mirror/aggregator (Netflix, Prime Video, JioHotstar, Apple TV+,
 * Sony LIV, Zee5, MX Player, Crunchyroll catalogues in one searchable library).
 *
 * Chain (verified end-to-end 2026-11-11):
 *  1. Catalogue API on the live .cc domain (TMDB-backed, clean JSON, no auth):
 *     /api/catalog/trending, /api/catalog/search-hybrid?q=, /api/catalog/title/<type>/<tmdbId>
 *  2. Stream resolver: /api/embed-tmdb/<tmdbId>?type=<type>&se=<se>&ep=<ep> returns
 *     mode:"proxy" + streams[] = direct signed MP4s (480p + 1080p) on
 *     bcdnxw.hakunaymatata.com, plus captions (.srt on cacdn.hakunaymatata.com).
 *  3. TV seasons/episodes: aoneroom BFF
 *     https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath=<slug>
 *     -> data.resource.seasons = [{se, maxEp}].
 *
 * Domain rotation: the streaming domain changes every few weeks
 * (net22.cc -> net27.cc -> net79.cc). The plugin resolves the live domain at
 * runtime by probing a candidate list (cheap /api/catalog/trending call) and
 * caches it. Do NOT hardcode a single .cc domain as the only option.
 *
 * Honest caveat (2026-11-11): the video MP4 bytes could not be fetched from
 * this VM — the CDN + the Cloudflare Workers proxy return HTTP 429 (rate
 * limit) for this VM's shared egress IP. The subtitle file from the same CDN
 * infrastructure DID return HTTP 200 with real content, and the embed API
 * returns structurally valid signed MP4 URLs. The plugin runs on the user's
 * phone (different IP). Phone test is the final proof for video playback.
 */
class NetMirrorProvider : MainAPI() {

    override var mainUrl = "https://net79.cc"
    override var name = "NetMirror"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    // ------------------------------------------------------------------
    // Dynamic domain resolution (cached in-memory for the session).
    // ------------------------------------------------------------------
    private var resolvedBase: String? = null

    private suspend fun baseUrl(): String {
        resolvedBase?.let { return it }
        // Most recent known live domain first, then older ones.
        val candidates = listOf(
            "https://net79.cc",
            "https://net27.cc",
            "https://net22.cc",
        )
        for (c in candidates) {
            try {
                val r = app.get(
                    "$c/api/catalog/trending",
                    headers = mapOf("User-Agent" to ua, "Referer" to "$c/"),
                )
                if (r.isSuccessful && r.text.contains("\"ok\":true")) {
                    resolvedBase = c
                    return c
                }
            } catch (e: Exception) {
                // try next candidate
            }
        }
        // Fallback to the most recent known domain.
        resolvedBase = "https://net79.cc"
        return resolvedBase!!
    }

    private suspend fun headers(): Map<String, String> {
        val b = baseUrl()
        return mapOf("User-Agent" to ua, "Referer" to "$b/")
    }

    private suspend fun watchHeaders(tmdbId: Int): Map<String, String> {
        val b = baseUrl()
        return mapOf("User-Agent" to ua, "Referer" to "$b/watch-tmdb/$tmdbId")
    }

    // ------------------------------------------------------------------
    // JSON models (ignoreUnknown — the API adds fields freely).
    // ------------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatItem(
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("streamable") val streamable: Boolean? = null,
        @JsonProperty("cta") val cta: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatListResp(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("items") val items: List<CatItem>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GenreItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CastItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("photo") val photo: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatCatalog(
        @JsonProperty("streamable") val streamable: Boolean? = null,
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("detailPath") val detailPath: String? = null,
        @JsonProperty("audioLangs") val audioLangs: List<String>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DetailResp(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("genres") val genres: List<GenreItem>? = null,
        @JsonProperty("cast") val cast: List<CastItem>? = null,
        @JsonProperty("catalog") val catalog: CatCatalog? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbedStream(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: Int? = null,
        @JsonProperty("size") val size: Long? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbedCaption(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbedResp(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("mode") val mode: String? = null,
        @JsonProperty("embedUrl") val embedUrl: String? = null,
        @JsonProperty("embedKind") val embedKind: String? = null,
        @JsonProperty("streams") val streams: List<EmbedStream>? = null,
        @JsonProperty("captions") val captions: List<EmbedCaption>? = null,
        @JsonProperty("noSource") val noSource: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BffSeason(
        @JsonProperty("se") val se: Int? = null,
        @JsonProperty("maxEp") val maxEp: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BffResource(
        @JsonProperty("seasons") val seasons: List<BffSeason>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BffData(
        @JsonProperty("resource") val resource: BffResource? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BffResp(
        @JsonProperty("data") val data: BffData? = null,
    )

    // Data passed from load() to loadLinks (as a JSON string in the URL).
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LinkData(
        @JsonProperty("tmdbId") val tmdbId: Int = 0,
        @JsonProperty("type") val type: String = "movie",
        @JsonProperty("se") val se: Int = 1,
        @JsonProperty("ep") val ep: Int = 1,
    )

    private fun toJson(data: LinkData): String? {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
        } catch (e: Exception) {
            null
        }
    }

    private fun qualityFromResolution(res: Int?): Int {
        return when (res) {
            2160 -> Qualities.P2160.value
            1440 -> Qualities.P1440.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun langName(code: String?): String {
        return when (code?.lowercase()) {
            "en" -> "English"
            "hi" -> "Hindi"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "bn" -> "Bengali"
            "ml" -> "Malayalam"
            "kn" -> "Kannada"
            "mr" -> "Marathi"
            "pa" -> "Punjabi"
            "gu" -> "Gujarati"
            "it" -> "Italian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "zh" -> "Chinese"
            "ar" -> "Arabic"
            "tr" -> "Turkish"
            "id" -> "Indonesian"
            "th" -> "Thai"
            "vi" -> "Vietnamese"
            else -> code?.uppercase() ?: "Subtitle"
        }
    }

    /** Decode a caption URL. Captions come as `/api/proxy/video?url=<url-encoded .srt>`.
     *  The direct CDN URL is verified to work (200, real .srt), so decode it;
     *  fall back to the proxy path (prefixed with the live base) if decoding fails. */
    private suspend fun decodeCaptionUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("/api/proxy/video?")) {
            return try {
                val q = trimmed.substringAfter("?", "")
                val params = q.split("&")
                var found: String? = null
                for (p in params) {
                    val kv = p.split("=", limit = 2)
                    if (kv.size == 2 && kv[0] == "url") {
                        found = URLDecoder.decode(kv[1], "UTF-8")
                        break
                    }
                }
                if (!found.isNullOrBlank() && found.startsWith("http")) found
                else baseUrl() + trimmed
            } catch (e: Exception) {
                baseUrl() + trimmed
            }
        }
        if (trimmed.startsWith("http")) return trimmed
        return baseUrl() + trimmed
    }

    private fun toSearchResponse(item: CatItem): com.lagradost.cloudstream3.SearchResponse? {
        val tid = item.tmdbId ?: return null
        val title = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val type = (item.type ?: "movie").lowercase()
        val dataJson = toJson(LinkData(tmdbId = tid, type = type)) ?: return null
        val poster = item.poster
        return if (type == "tv") {
            newTvSeriesSearchResponse(title, dataJson, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, dataJson, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ------------------------------------------------------------------
    // Home page.
    // ------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "Trending" to "/api/catalog/trending",
        "Discover" to "/api/catalog/discover",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val b = baseUrl()
        val url = "$b${request.data}" + if (request.data.contains("?")) "&page=$page" else "?page=$page"
        val resp = try {
            app.get(url, headers = headers()).parsed<CatListResp>()
        } catch (e: Exception) {
            null
        }
        val items = resp?.items?.mapNotNull { toSearchResponse(it) } ?: emptyList()
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Search.
    // ------------------------------------------------------------------
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val b = baseUrl()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val resp = try {
            app.get(
                "$b/api/catalog/search-hybrid?q=$q&page=$page",
                headers = headers(),
            ).parsed<CatListResp>()
        } catch (e: Exception) {
            null
        } ?: return null
        val results = resp.items?.mapNotNull { toSearchResponse(it) } ?: return null
        return results.toNewSearchResponseList()
    }

    // ------------------------------------------------------------------
    // Detail (load). Movies -> MovieLoadResponse. TV -> TvSeriesLoadResponse
    // with episodes from the aoneroom BFF (data.resource.seasons = [{se,maxEp}]).
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val ld = tryParseJson<LinkData>(url) ?: return null
        val b = baseUrl()
        val detail = try {
            app.get(
                "$b/api/catalog/title/${ld.type}/${ld.tmdbId}",
                headers = headers(),
            ).parsed<DetailResp>()
        } catch (e: Exception) {
            null
        } ?: return null
        val title = detail.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val type = (detail.type ?: ld.type).lowercase()
        val poster = detail.poster
        val plot = detail.overview?.trim()
        val year = detail.year?.toIntOrNull()
        val score = detail.rating?.let { Score.from10(it) }
        val tags = detail.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
        val duration = detail.runtime
        val streamable = detail.catalog?.streamable
        val plotWithNote = if (streamable == false) {
            val base = plot ?: ""
            if (base.isEmpty()) "Coming Soon on NetMirror."
            else "$base\n\n(Coming Soon on NetMirror.)"
        } else {
            plot
        }
        val dataJson = toJson(LinkData(tmdbId = ld.tmdbId, type = type)) ?: return null

        if (type == "tv") {
            // Episodes: prefer the aoneroom BFF seasons (verified for MobLand,
            // Black Doves, Tulsa King). Fallback: probe the embed API for S1.
            val episodes = mutableListOf<Episode>()
            val seasons = fetchSeasons(detail.catalog?.detailPath)
            if (seasons.isNotEmpty()) {
                for (s in seasons) {
                    val se = s.first
                    val maxEp = s.second
                    for (ep in 1..maxEp) {
                        val epJson = toJson(LinkData(tmdbId = ld.tmdbId, type = "tv", se = se, ep = ep))
                            ?: continue
                        episodes.add(
                            newEpisode(epJson) {
                                this.name = "Episode $ep"
                                this.episode = ep
                                this.season = se
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            } else {
                // Fallback: probe S1 episodes via the embed API (stop at first
                // episode with no source). Capped at 15 to bound API calls.
                for (ep in 1..15) {
                    val has = try {
                        val e = app.get(
                            "$b/api/embed-tmdb/${ld.tmdbId}?type=tv&se=1&ep=$ep",
                            headers = watchHeaders(ld.tmdbId),
                        ).parsed<EmbedResp>()
                        e?.mode != "none" && e?.noSource != true && !e?.streams.isNullOrEmpty()
                    } catch (ex: Exception) {
                        false
                    }
                    if (!has) break
                    val epJson = toJson(LinkData(tmdbId = ld.tmdbId, type = "tv", se = 1, ep = ep))
                        ?: continue
                    episodes.add(
                        newEpisode(epJson) {
                            this.name = "Episode $ep"
                            this.episode = ep
                            this.season = 1
                            this.posterUrl = poster
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plotWithNote
                this.year = year
                this.score = score
                this.tags = tags
                this.duration = duration
            }
        }

        return newMovieLoadResponse(title, dataJson, TvType.Movie, dataJson) {
            this.posterUrl = poster
            this.plot = plotWithNote
            this.year = year
            this.score = score
            this.tags = tags
            this.duration = duration
        }
    }

    /** Fetch TV seasons from the aoneroom BFF. Returns a list of (season, maxEp).
     *  Returns emptyList() on any failure (404 for CAM/weird titles, network error). */
    private suspend fun fetchSeasons(detailPath: String?): List<Pair<Int, Int>> {
        val dp = detailPath?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return try {
            val enc = URLEncoder.encode(dp, "UTF-8")
            val resp = app.get(
                "https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath=$enc",
                headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
            ).parsed<BffResp>()
            val seasons = resp?.data?.resource?.seasons ?: return emptyList()
            val out = mutableListOf<Pair<Int, Int>>()
            for (s in seasons) {
                val se = s.se ?: continue
                val maxEp = s.maxEp ?: continue
                if (se > 0 && maxEp > 0) out.add(Pair(se, maxEp))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Streams (loadLinks). The embed API returns direct signed MP4s.
    // ------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ld = tryParseJson<LinkData>(data) ?: return false
        val b = baseUrl()
        val embed = try {
            app.get(
                "$b/api/embed-tmdb/${ld.tmdbId}?type=${ld.type}&se=${ld.se}&ep=${ld.ep}",
                headers = watchHeaders(ld.tmdbId),
            ).parsed<EmbedResp>()
        } catch (e: Exception) {
            null
        } ?: return false
        if (embed.ok != true) return false
        if (embed.mode == "none" || embed.noSource == true) return false

        // Embed mode (Server 1 / vidfast iframe): hand it to the extractor.
        // The primary mode is "proxy" (direct MP4s) — this is only a fallback.
        if (embed.mode == "embed") {
            val eu = embed.embedUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            return try {
                loadExtractor(eu, subtitleCallback, callback)
            } catch (e: Exception) {
                false
            }
        }

        val streams = embed.streams
        if (streams.isNullOrEmpty()) return false

        // Subtitles first (decoded direct .srt URLs; verified 200).
        try {
            for (cap in embed.captions.orEmpty()) {
                try {
                    val subUrl = decodeCaptionUrl(cap.url) ?: continue
                    subtitleCallback.invoke(
                        SubtitleFile(langName(cap.lang), subUrl)
                    )
                } catch (e: Exception) {
                    // one bad subtitle must not kill the rest
                }
            }
        } catch (e: Exception) {
            // subtitles are best-effort
        }

        var found = false
        for (s in streams) {
            try {
                val streamUrl = s.url?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val res = s.resolution ?: 0
                val label = if (res > 0) "${res}p" else "NetMirror"
                callback.invoke(
                    newExtractorLink(
                        source = "NetMirror",
                        name = "NetMirror $label",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = qualityFromResolution(res)
                        this.referer = "$b/"
                    }
                )
                found = true
            } catch (e: Exception) {
                // one bad stream must not kill the rest
            }
        }
        return found
    }
}
