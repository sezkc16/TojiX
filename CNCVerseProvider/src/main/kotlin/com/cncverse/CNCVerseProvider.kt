package com.cncverse

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode as CloudEpisode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * CNC Verse — Netflix provider (ott "nf").
 *
 * Chain verified live (2026-11-11):
 *  catalogue  net52.cc/mobile/home, /mobile/search.php, /mobile/post.php,
 *             /mobile/episodes.php  (t_hash_t cookie from the verify.php
 *             bypass + ott=nf + hd=on)
 *  streams    NewTV API — resolveApiUrl() -> otp.php -> player.php?id=<id>
 *             -> a signed HLS master on tv.imgcdn.kim
 *             (verified playable: The Odyssey movie + Stranger Things S5E1,
 *              1080p/720p/480p variants)
 *
 * v1 ships Netflix ONLY. Hotstar (hs) and Prime Video (pv) return a
 * video_link from player.php but their masters were empty/incomplete when
 * verified (0 variants), so they are left out rather than shipping broken
 * streams. They can be added as a v2 if the API starts serving complete
 * masters for them.
 */
class CNCVerseProvider : MainAPI() {

    override var mainUrl = "https://net52.cc"
    override var name = "Netflix"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    /** OTT code sent to the catalogue cookies and the NewTV API. */
    private val ott = "nf"

    /** WebView-style headers for the catalogue (mobile endpoints). */
    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
        "X-Requested-With" to "XMLHttpRequest"
    )

    private fun cookies(cookieValue: String): Map<String, String> =
        mapOf("t_hash_t" to cookieValue, "ott" to ott, "hd" to "on")

    private fun unixTime(): Long = System.currentTimeMillis() / 1000

    private fun poster(id: String): String = "https://imgcdn.kim/poster/v/$id.jpg"

    private fun posterHeaders(): Map<String, String> =
        mapOf("Referer" to "$mainUrl/home")

    /** Serializes a small data object (Id / LoadData) to JSON for a card/episode URL. */
    private fun toJson(data: Any): String {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
        } catch (_: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------------ home

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val cookieValue = CNCVerseUtils.getCookie(mainUrl)
        val doc = app.get(
            "$mainUrl/mobile/home?app=1",
            headers = headers,
            cookies = cookies(cookieValue)
        ).document
        val lists = doc.select(".tray-container, #top10").mapNotNull { tray ->
            val name = tray.selectFirst("h2, span")?.text()?.trim().orEmpty()
            val items = tray.select("article, .top10-post").mapNotNull { toSearchResult(it) }
            if (items.isEmpty()) null else HomePageList(name, items)
        }
        return newHomePageResponse(lists)
    }

    /**
     * Home cards are poster-only: the site's home HTML carries no title text
     * (just a poster image), so the name is left empty like the original.
     */
    private fun toSearchResult(element: Element): SearchResponse? {
        val id = element.selectFirst("a")?.attr("data-post")
            ?: element.attr("data-post")
        if (id.isBlank()) return null
        return newAnimeSearchResponse("", toJson(Id(id)), fix = false) {
            this.posterUrl = poster(id)
            this.posterHeaders = posterHeaders()
        }
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val cookieValue = CNCVerseUtils.getCookie(mainUrl)
        val url = "$mainUrl/mobile/search.php?s=${URLEncoder.encode(query, "UTF-8")}&t=${unixTime()}"
        val data = app.get(url, headers = headers, cookies = cookies(cookieValue))
            .parsed<SearchData>()
        return data.searchResult?.mapNotNull { result ->
            val id = result.id ?: return@mapNotNull null
            newAnimeSearchResponse(result.t ?: "", toJson(Id(id)), fix = false) {
                this.posterUrl = poster(id)
                this.posterHeaders = posterHeaders()
            }
        } ?: emptyList()
    }

    // ----------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val id = tryParseJson<Id>(url)?.id ?: return null
        val cookieValue = CNCVerseUtils.getCookie(mainUrl)
        val postUrl = "$mainUrl/mobile/post.php?id=$id&t=${unixTime()}"
        val data = app.get(postUrl, headers = headers, cookies = cookies(cookieValue))
            .parsed<PostData>()
        val title = data.title ?: return null

        val genre = data.genre
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val cast = data.cast
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val rating = data.match?.replace("IMDb ", "")
        val runTime = CNCVerseUtils.convertRuntimeToMinutes(data.runtime.orEmpty())
        val suggest = data.suggest?.mapNotNull { s ->
            val sid = s.id ?: return@mapNotNull null
            newAnimeSearchResponse("", toJson(Id(sid)), fix = false) {
                this.posterUrl = poster(sid)
                this.posterHeaders = posterHeaders()
            }
        }

        val episodes = mutableListOf<CloudEpisode>()
        // A movie comes back with a single null episode entry.
        if (data.episodes.isNullOrEmpty() || data.episodes.firstOrNull() == null) {
            episodes.add(newEpisode(LoadData(title, id)) { this.name = title })
            return newTvSeriesLoadResponse(title, url, TvType.Movie, episodes) {
                this.posterUrl = poster(id)
                this.backgroundPosterUrl = poster(id)
                this.posterHeaders = posterHeaders()
                this.plot = data.desc
                this.year = data.year?.toIntOrNull()
                this.tags = genre
                this.actors = cast.map { ActorData(actor = Actor(it)) }
                this.score = Score.from10(rating)
                this.duration = runTime
                this.contentRating = data.ua
                this.recommendations = suggest
            }
        }

        // A TV show: episodes from post.php, plus extra pages/seasons.
        episodes.addAll(data.episodes.filterNotNull().map { ep ->
            toCloudEpisode(title, ep, "https://imgcdn.kim/nf/v/200/${ep.id}.jpg")
        })
        if (data.nextPageShow == 1 && !data.nextPageSeason.isNullOrBlank()) {
            episodes.addAll(getEpisodes(title, id, data.nextPageSeason, 2))
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster(id)
            this.backgroundPosterUrl = poster(id)
            this.posterHeaders = posterHeaders()
            this.plot = data.desc
            this.year = data.year?.toIntOrNull()
            this.tags = genre
            this.actors = cast.map { ActorData(actor = Actor(it)) }
            this.score = Score.from10(rating)
            this.duration = runTime
            this.contentRating = data.ua
            this.recommendations = suggest
        }
    }

    /** Episode JSON entry -> CloudStream episode. posterBase is the per-episode poster URL. */
    private fun toCloudEpisode(title: String, ep: Episode, posterUrl: String): CloudEpisode {
        return newEpisode(LoadData(ep.t ?: title, ep.id ?: "")) {
            this.name = ep.t
            this.episode = ep.ep?.removePrefix("E")?.toIntOrNull()
            this.season = ep.s?.removePrefix("S")?.toIntOrNull()
            this.posterUrl = posterUrl
            this.runTime = ep.time?.removeSuffix("m")?.toIntOrNull()
        }
    }

    /**
     * Fetches one more page/season of episodes:
     *   GET $mainUrl/mobile/episodes.php?s=<season id>&pg=<page>
     * Recurses while the API says there is another page (nextPageShow == 1).
     * Depth is capped so a misbehaving API cannot recurse forever.
     */
    private suspend fun getEpisodes(
        title: String,
        eid: String,
        sid: String,
        page: Int,
        depth: Int = 0
    ): List<CloudEpisode> {
        if (depth > 20) return emptyList()
        val cookieValue = CNCVerseUtils.getCookie(mainUrl)
        val data = runCatching {
            app.get(
                "$mainUrl/mobile/episodes.php?s=$sid&pg=$page",
                headers = headers,
                cookies = cookies(cookieValue)
            ).parsed<EpisodesData>()
        }.getOrNull() ?: return emptyList()
        val list = data.episodes?.filterNotNull()?.map { ep ->
            toCloudEpisode(title, ep, "https://imgcdn.kim/epimg/150/${ep.id}.jpg")
        }.orEmpty()
        if (data.nextPageShow == 1 && !data.nextPageSeason.isNullOrBlank()) {
            return list + getEpisodes(
                title,
                eid,
                data.nextPageSeason,
                data.nextPage ?: (page + 1),
                depth + 1
            )
        }
        return list
    }

    // ------------------------------------------------------------ load links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<LoadData>(data) ?: return false
        val id = loadData.id?.takeIf { it.isNotBlank() } ?: return false
        val title = loadData.title.orEmpty()

        val apiBase = CNCVerseUtils.resolveApiUrl(app)
        if (apiBase.isBlank()) return false
        val token = CNCVerseUtils.getNewTvUserToken(app, apiBase, ott)
        val playerHeaders = CNCVerseUtils.buildNewTvHeaders(
            ott,
            mapOf("Usertoken" to token)
        )
        val res = runCatching {
            app.get("$apiBase/newtv/player.php?id=$id", headers = playerHeaders)
                .parsed<NewTvPlayerResponse>()
        }.getOrNull() ?: return false
        val videoLink = res.video_link
        if (videoLink.isNullOrBlank()) return false

        callback.invoke(
            newExtractorLink(title, title, videoLink, ExtractorLinkType.M3U8) {
                this.referer = res.referer ?: apiBase
            }
        )
        return true
    }
}
