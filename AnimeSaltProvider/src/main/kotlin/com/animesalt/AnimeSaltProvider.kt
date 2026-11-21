package com.animesalt

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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

class AnimeSaltProvider : MainAPI() {

    // WordPress + Torofilm theme, same family as WatchAnimeWorld
    // (watchanimeworld.pro). Unlike watchanimeworld.one, animesalt.cx
    // does NOT Cloudflare-block plain HTTP clients (verified live
    // 2026-11-22: home, /series/, /movies/, series, episode and movie
    // pages all return HTTP 200 from this VM).
    override var mainUrl = "https://animesalt.cx"
    override var name = "AnimeSalt"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // FirePlayer/VPlayer getVideo response: the HLS master playlist URL.
    // Verified live from this VM (ravok.buzz, Psyren 1x1):
    // POST returns {"hls":true,"videoSource":"https://ravok.buzz/cdn/hls/
    // <id>/master.m3u8?md5=...&expires=...","securedLink":"<same>"}.
    data class FirePlayerResponse(
        @JsonProperty("securedLink") val securedLink: String? = null,
        @JsonProperty("videoSource") val videoSource: String? = null,
    )

    // One language entry from the self-hosted Plyr player data blob:
    // [{"language":"Hindi","link":"https://player.abyssplayer.com/<id>"}, ...]
    data class LangLink(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("link") val link: String? = null,
    )

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> "$mainUrl/$url"
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    private fun parseYear(text: String?): Int? {
        return text?.let { Regex("\\((19|20)\\d{2}\\)").find(it)?.value?.trim('(', ')')?.toIntOrNull() }
    }

    // Torofilm listing: <article class="post ..."> with
    // <h2 class="entry-title">Title</h2>, a TMDB poster
    // <img data-src="//image.tmdb.org/t/p/w500/..."> and the detail
    // link <a class="lnk-blk" href="/series/<slug>/">. The article
    // class ("movies"/"series") is unreliable — the URL decides type.
    // Home (/) is JS-rendered templates, so the main page uses the
    // server-rendered /series/ and /movies/ archives instead.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.post")) {
            val link = article.selectFirst("a.lnk-blk[href]")
                ?: article.selectFirst("a[href*=/series/]")
                ?: article.selectFirst("a[href*=/movies/]")
                ?: continue
            val href = link.attr("href")
            if (href.isEmpty() || href.contains("/page/")) continue
            val url = fixUrl(href)
            if (!seen.add(url)) continue
            val title = cleanTitle(article.selectFirst("h2.entry-title")?.text().orEmpty())
            if (title.isEmpty()) continue
            val img = article.selectFirst("img[data-src]") ?: article.selectFirst("img[src]")
            val poster = img?.attr("data-src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            val posterUrl = poster?.let { fixUrl(it) }
            if (url.contains("/series/")) {
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = posterUrl })
            } else if (url.contains("/movies/")) {
                out.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = posterUrl })
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data
        val url = if (page <= 1) "$mainUrl/$cat/" else "$mainUrl/$cat/page/$page/"
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(html)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    override val mainPage = mainPageOf(
        "series" to "Anime Series",
        "movies" to "Anime Movies",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // WordPress ?s= is server-rendered (verified live 2026-11-22).
        // Pagination is untested, so search is page 1 only.
        if (page > 1) return emptyList<com.lagradost.cloudstream3.SearchResponse>().toNewSearchResponseList()
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    // ---- episodes ----

    // One episode row on a series page: <article class="post ... episodes">
    // with <h2 class="entry-title">PSYREN 1x1</h2>, a thumbnail
    // <img data-src="https://img.animesalt.cx/images-unified/thumb_<post>_s<season>e<ep>.jpg">
    // and the link /episode/<slug>-<season>x<ep>/.
    private fun parseEpisodeBlocks(doc: Document): List<Episode> {
        val out = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.episodes, article.post")) {
            val link = article.selectFirst("a[href*=/episode/]") ?: continue
            val href = link.attr("href")
            if (href.isEmpty()) continue
            val url = fixUrl(href)
            if (!seen.add(url)) continue
            val m = Regex("/episode/[a-z0-9-]+-(\\d+)x(\\d+)/?$").find(url) ?: continue
            val season = m.groupValues[1].toIntOrNull() ?: 1
            val epNum = m.groupValues[2].toIntOrNull() ?: continue
            val name = cleanTitle(article.selectFirst("h2.entry-title")?.text().orEmpty())
                .takeIf { it.isNotEmpty() } ?: "Episode $epNum"
            val img = article.selectFirst("img[data-src]") ?: article.selectFirst("img[src]")
            val thumb = img?.attr("data-src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: img?.attr("src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            out.add(newEpisode(url) {
                this.name = name
                this.episode = epNum
                this.season = season
                this.posterUrl = thumb?.let { fixUrl(it) }
            })
        }
        return out
    }

    // The series page HTML only carries the latest season's episodes.
    // The theme's season switcher is the admin-ajax endpoint
    // POST /wp-admin/admin-ajax.php with
    // action=action_select_season&season=<N>&post=<postId> (post ID from
    // data-post="N" on the page, seasons from data-season="N").
    private suspend fun fetchSeasonEpisodes(seriesUrl: String, postId: String, season: String): List<Episode> {
        return try {
            val body = FormBody.Builder()
                .add("action", "action_select_season")
                .add("season", season)
                .add("post", postId)
                .build()
            val html = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = browserHeaders + ("Referer" to seriesUrl),
                requestBody = body
            ).text
            parseEpisodeBlocks(Jsoup.parse(html))
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val title = cleanTitle(
            doc.selectFirst("title")?.text()?.substringBefore(" - Anime Salt").orEmpty()
        ).takeIf { it.isNotEmpty() }
            ?: cleanTitle(doc.selectFirst("h1")?.text().orEmpty())
        if (title.isEmpty()) return null
        // Series cover: the 14rem TMDB poster img on the detail page.
        val poster = doc.selectFirst("img[style*=14rem]")?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: doc.select("img[data-src*=image.tmdb.org]").firstOrNull()
                ?.attr("data-src")?.takeIf { it.isNotEmpty() }
        val posterUrl = poster?.let { fixUrl(it) }
        // Real synopsis from the page's own meta description.
        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val year = parseYear(title)
        // The site has NO cast block and NO related-titles block on
        // detail pages, so actors and recommendations stay unset rather
        // than faked — the Cast and More Like This rows will simply not
        // appear for this plugin.

        if (url.contains("/series/")) {
            val episodes = mutableListOf<Episode>()
            val seen = mutableSetOf<String>()
            fun addAll(list: List<Episode>) {
                for (ep in list) {
                    if (seen.add(ep.data)) episodes.add(ep)
                }
            }
            addAll(parseEpisodeBlocks(doc))
            val postId = Regex("data-post=\"(\\d+)\"").find(html)?.groupValues?.getOrNull(1)
            val seasons = Regex("data-season=\"(\\d+)\"").findAll(html)
                .map { it.groupValues[1] }.distinct().toList()
            if (postId != null) {
                for (season in seasons) {
                    addAll(fetchSeasonEpisodes(url, postId, season))
                }
            }
            episodes.sortWith(compareBy({ it.season }, { it.episode }))
            if (episodes.isEmpty()) return null
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = null
                this.year = year
            }
        } else if (url.contains("/movies/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
            }
        }
        return null
    }

    // ---- stream resolution ----

    // FirePlayer/VPlayer embed (ravok.buzz etc.): the embed HTML NEVER
    // contains the video URL — the player fetches it via:
    //   POST {origin}/player/index.php?data={hash}&do=getVideo
    //   body (form): hash={hash}&r={site base URL}
    //   -> JSON { securedLink | videoSource } = HLS master playlist URL.
    // Method from the working Nuvio provider for this exact site
    // (mayurgj/nama providers/animesalt.js v1.2.1, fpGetMasterUrl), and
    // the POST itself was verified live from this VM (ravok.buzz,
    // Psyren 1x1 -> HTTP 200 with securedLink/videoSource).
    private suspend fun resolveFirePlayer(embedUrl: String): String? {
        val m = Regex("""//([a-z0-9.-]+)/video/([a-f0-9]{32})""").find(embedUrl) ?: return null
        val origin = "https://" + m.groupValues[1]
        val hash = m.groupValues[2]
        return try {
            val body = FormBody.Builder()
                .add("hash", hash)
                .add("r", "$mainUrl/")
                .build()
            val res = app.post(
                "$origin/player/index.php?data=$hash&do=getVideo",
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$origin/video/$hash",
                    "Origin" to origin,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                ),
                requestBody = body
            )
            val parsed = tryParseJson<FirePlayerResponse>(res.text)
            (parsed?.securedLink ?: parsed?.videoSource)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    private fun decodePlayerData(b64: String): List<LangLink> {
        return try {
            val json = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            tryParseJson<List<LangLink>>(json) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun directMediaUrl(url: String): String? {
        val path = url.substringBefore("#").substringBefore("?")
        return if (path.endsWith(".m3u8", true) || path.endsWith(".mp4", true) ||
            path.endsWith(".mkv", true) || path.endsWith(".mov", true)
        ) url else null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data
        val html = try {
            app.get(pageUrl, headers = browserHeaders).text
        } catch (e: Exception) { return false }
        val doc = Jsoup.parse(html)
        var found = false

        // ---- Server 1 (default): FirePlayer/VPlayer embed (ravok.buzz) ----
        // The iframe carries both src and data-src (same URL); read
        // src first, data-src as fallback (some pages lazy-load it).
        // The master .m3u8 from getVideo is handed to the player as one
        // stream (the player picks quality); referer is the embed origin.
        val embedUrl = doc.selectFirst("#options-0 iframe[src]")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("#options-0 iframe[data-src]")?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("iframe[src*=/video/]")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("iframe[data-src*=/video/]")?.attr("data-src")?.takeIf { it.isNotEmpty() }
        if (!embedUrl.isNullOrEmpty()) {
            val fullEmbed = fixUrl(embedUrl)
            val master = resolveFirePlayer(fullEmbed)
            if (!master.isNullOrEmpty()) {
                callback(
                    newExtractorLink(
                        source = "AnimeSalt",
                        name = "Server 1",
                        url = master,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = fullEmbed.substringBefore("/video/")
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        // ---- Server 2 (alt): self-hosted multi-language Plyr player ----
        // iframe data-src="/wp-content/themes/torofilm/public/plyr/player.php
        // ?data=<b64 JSON>" — the JSON maps each audio language to a
        // player.abyssplayer.com link. Some pages only have Server 1,
        // so this block is optional.
        val playerData = doc.selectFirst("#options-1 iframe[data-src]")?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("iframe[data-src*=player.php]")?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("iframe[src*=player.php]")?.attr("src")?.takeIf { it.isNotEmpty() }
        val langLinks = playerData
            ?.substringAfter("data=", "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { decodePlayerData(it) }
            ?: emptyList()
        for (entry in langLinks) {
            val link = entry.link ?: continue
            val lang = entry.language ?: "Multi-Audio"
            val direct = directMediaUrl(link)
            if (direct != null) {
                callback(
                    newExtractorLink(
                        source = "AnimeSalt",
                        name = "Server 2 $lang",
                        url = direct,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            } else if (loadExtractor(link, subtitleCallback, callback)) {
                found = true
            }
        }

        return found
    }
}
