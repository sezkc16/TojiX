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

/**
 * Miruro — free anime streaming (Sub & Dub).
 *
 * Metadata comes from the public AniList GraphQL API (no auth), streams come
 * from the justanime core API (the same megaplay/animegg servers Miruro's own
 * site uses — proven by the Mangayomi Miruro extension, v6.1.15).
 * Miruro's own pipe API is Cloudflare-403 outside a browser, so it is not
 * used. Only megaplay + animegg work (26 other provider names 404,
 * anineko/animepahe 502).
 */
class MiruroProvider : MainAPI() {

    override var mainUrl = "https://miruro.tv"
    override var name = "Miruro"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    private val anilistUrl = "https://graphql.anilist.co"
    private val justAnimeApi = "https://core.justanime.to/api/watch"

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

    // Data passed to loadLinks.
    data class EpisodeData(
        @JsonProperty("id") val id: Int,
        @JsonProperty("num") val num: Int,
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
            val dataJson = toJson(EpisodeData(id = id, num = 1)) ?: return null
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
            val dataJson = toJson(EpisodeData(id = id, num = n)) ?: continue
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
            val base = masterUrl.substringBefore("?").substringBeforeLast("/") + "/"
            val variants = mutableListOf<Pair<String, Int>>()
            val lines = body.split("\n")
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                for (j in i + 1 until lines.size) {
                    val u = lines[j].trim()
                    if (u.isEmpty() || u.startsWith("#")) continue
                    val full = if (u.startsWith("http")) u else base + u
                    variants.add(full to qualityFromHeight(height))
                    break
                }
            }
            variants.sortByDescending { it.second }
            variants
        } catch (e: Exception) { emptyList() }
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
        return found
    }
}
