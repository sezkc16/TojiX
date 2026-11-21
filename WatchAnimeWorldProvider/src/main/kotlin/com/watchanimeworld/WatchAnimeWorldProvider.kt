package com.watchanimeworld

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
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
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64

class WatchAnimeWorldProvider : MainAPI() {

    // NOTE: the user's domain watchanimeworld.one Cloudflare-blocks plain
    // HTTP clients (HTTP 403 even with full browser headers, verified
    // 2026-11-22), while watchanimeworld.pro serves the IDENTICAL site
    // (same titles, players, sitemaps) over plain HTTP. The plugin uses
    // the .pro mirror so it works from CloudStream's plain HTTP client.
    override var mainUrl = "https://watchanimeworld.pro"
    override var name = "WatchAnimeWorld"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // One language entry from the /api/player1.php data blob:
    // [{"language":"Hindi","link":"https://short.icu/<code>"}, ...]
    data class LangLink(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("link") val link: String? = null,
    )

    // FirePlayer/VPlayer getVideo response: the HLS master playlist URL.
    data class FirePlayerResponse(
        @JsonProperty("securedLink") val securedLink: String? = null,
        @JsonProperty("videoSource") val videoSource: String? = null,
    )

    // enc-dec.app dec-abyss response: direct .mp4 URLs per quality.
    data class AbyssSource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("size") val size: Long? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("codec") val codec: String? = null,
        @JsonProperty("status") val status: Boolean? = null,
    )

    data class AbyssResult(
        @JsonProperty("sources") val sources: List<AbyssSource>? = null,
    )

    data class AbyssResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: AbyssResult? = null,
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
        return text?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
    }

    // Body text carries "Overview: <plot>" followed by the meta line
    // ("24 min 2019 Trailer" on series, "Genres ... Languages" on
    // movies). The og:description is NOT usable for movies (SEO spam).
    private fun parseOverview(bodyText: String): String? {
        val idx = bodyText.indexOf("Overview:")
        if (idx < 0) return null
        var plot = bodyText.substring(idx + "Overview:".length).trim()
        for (cut in listOf(" Genres ", " Trailer ", " choose season ")) {
            val i = plot.indexOf(cut)
            if (i > 0) plot = plot.substring(0, i).trim()
        }
        // Trailing duration + year ("24 min 2019", "1h 52m 2019").
        val m = Regex("\\s+\\d+\\s*(?:h\\s*\\d+m|min)\\s+(?:19|20)\\d{2}\\b").find(plot)
        if (m != null) plot = plot.substring(0, m.range.first).trim()
        return plot.takeIf { it.length >= 20 }
    }

    // Movies only: "Genres Drama , Fantasy , Romance Languages ...".
    // Series pages carry no genre block, so this returns empty there.
    private fun parseGenres(bodyText: String): List<String> {
        val m = Regex("Genres\\s+(.+?)\\s+Languages\\b").find(bodyText) ?: return emptyList()
        return m.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // One card: <article class="post"> with h2.entry-title, an
    // <a class="lnk-blk"> to /series/<slug>/ or /movies/<slug>/, and a
    // TMDB poster <img> (protocol-relative src). The .year span holds
    // "EP:1-26" on series cards and the year on recommended cards.
    private fun parseCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a.lnk-blk") ?: article.selectFirst("a[href]") ?: return null
        val href = link.attr("href")
        if (href.isEmpty()) return null
        val url = fixUrl(href)
        val title = cleanTitle(article.selectFirst("h2.entry-title")?.text() ?: return null)
        if (title.isEmpty()) return null
        val img = article.selectFirst(".post-thumbnail img") ?: article.selectFirst("img")
        val poster = img?.let {
            it.attr("data-src").takeIf { s -> s.isNotEmpty() && !s.startsWith("data:") }
                ?: it.attr("src").takeIf { s -> s.isNotEmpty() && !s.startsWith("data:") }
        }?.let { fixUrl(it) }
        return if (url.contains("/movies/")) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.post")) {
            val item = parseCard(article) ?: continue
            if (seen.add(item.url)) out.add(item)
        }
        return out
    }

    // ---- homepage ----
    //
    // The REAL homepage https://watchanimeworld.pro/ is server-rendered
    // (verified live 2026-11-22). Its content sections, in order, are
    // headed by <h3 class="section-title">:
    //   "Newest Drops" — latest-episode swiper: <div class="swiper-slide
    //     latest-ep-swiper-slide"> > <article class="post"> with
    //     <h2 class="entry-title">, <img data-src>, <a class="lnk-blk">
    //     to the series page (e.g. Barbie Dreamtopia, BAT-FAM).
    //   "New Anime Arrivals" / "Just In: Cartoon Series" /
    //   "Latest Anime Movies" / "Fresh Cartoon Films" — a swiper:
    //   <div class="swiper-slide
    //   latest-movies-series-swiper-slide"> > <article class="post">
    //   with <img data-src> and <a class="lnk-blk">; the article has
    //   NO title element on these rows, so the title comes from the img
    //   alt "Image <title>" (13-19 items each).
    // "Watch History" (user-specific template), "Network" (logo slider)
    // and "Stream in Your Language" (language filter widget) carry no
    // browsable rows and are skipped.
    // The homepage is fetched once and cached for 10 minutes, because
    // getMainPage is called once per row.
    private var homeCacheAt = 0L
    private var homeCache: Map<String, List<SearchResponse>>? = null

    private fun homeItem(url: String, title: String, poster: String?): SearchResponse {
        return if (url.contains("/movies/")) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    private fun homePoster(article: Element): String? {
        val img = article.selectFirst("img[data-src]") ?: article.selectFirst("img[src]")
        val raw = img?.attr("data-src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            ?: img?.attr("src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
        return raw?.let { fixUrl(it) }
    }

    private val skippedHomeSections = setOf(
        "Watch History", "Network", "Stream in Your Language", "Navigate A to Z"
    )

    private fun parseHomeSections(doc: Document): Map<String, List<SearchResponse>> {
        val sections = linkedMapOf<String, MutableList<SearchResponse>>()
        var currentTitle: String? = null
        // Document order: each swiper slide belongs to the nearest
        // preceding h3.section-title, whatever its nesting.
        for (el in doc.select("h3.section-title, div.swiper-slide")) {
            when {
                el.tagName() == "h3" -> {
                    currentTitle = el.text().replace("View More »", "").replace("View More", "").trim()
                    if (currentTitle.isEmpty() || currentTitle in skippedHomeSections) {
                        currentTitle = null
                    }
                }
                currentTitle == null -> Unit
                el.hasClass("swiper-slide") -> {
                    val article = el.selectFirst("article.post") ?: continue
                    val link = article.selectFirst("a.lnk-blk[href]") ?: continue
                    val href = link.attr("href")
                    if (href.isEmpty()) continue
                    val title = cleanTitle(article.selectFirst("h2.entry-title")?.text().orEmpty())
                        .takeIf { it.isNotEmpty() }
                        ?: cleanTitle(article.selectFirst("img")?.attr("alt").orEmpty().removePrefix("Image "))
                            .takeIf { it.isNotEmpty() }
                        ?: continue
                    val list = sections.getOrPut(currentTitle) { mutableListOf() }
                    if (list.none { it.url == fixUrl(href) }) {
                        list.add(homeItem(fixUrl(href), title, homePoster(article)))
                    }
                }
            }
        }
        return sections.filterValues { it.isNotEmpty() }
    }

    private suspend fun fetchHomeSections(): Map<String, List<SearchResponse>> {
        val now = System.currentTimeMillis()
        homeCache?.let { if (now - homeCacheAt < 10 * 60 * 1000) return it }
        val sections = try {
            val doc = Jsoup.parse(app.get(mainUrl, headers = browserHeaders).text)
            parseHomeSections(doc)
        } catch (e: Exception) { emptyMap() }
        homeCache = sections
        homeCacheAt = now
        return sections
    }

    // Homepage section key -> the site's section title, and the archive
    // to fall back on if that section cannot be parsed.
    private val homeSections = mapOf(
        "home-newest-drops" to Pair("Newest Drops", "series"),
        "home-new-anime-arrivals" to Pair("New Anime Arrivals", "series"),
        "home-cartoon-series" to Pair("Just In: Cartoon Series", "series"),
        "home-latest-anime-movies" to Pair("Latest Anime Movies", "movies"),
        "home-fresh-cartoon-films" to Pair("Fresh Cartoon Films", "movies"),
    )

    private suspend fun fetchArchive(cat: String, page: Int): List<SearchResponse> {
        val url = if (page <= 1) "$mainUrl/$cat/" else "$mainUrl/$cat/page/$page/"
        return try {
            parseListing(Jsoup.parse(app.get(url, headers = browserHeaders).text))
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data
        val home = homeSections[cat]
        if (home != null && page <= 1) {
            val list = fetchHomeSections()[home.first]
            if (!list.isNullOrEmpty()) {
                return newHomePageResponse(HomePageList(request.name, list), hasNext = false)
            }
            // Section missing/unparseable: fall back to its archive.
            val archive = fetchArchive(home.second, 1)
            return newHomePageResponse(HomePageList(request.name, archive), hasNext = archive.isNotEmpty())
        }
        val list = fetchArchive(cat, page)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    override val mainPage = mainPageOf(
        "home-newest-drops" to "Newest Drops",
        "home-new-anime-arrivals" to "New Anime Arrivals",
        "home-cartoon-series" to "Just In: Cartoon Series",
        "home-latest-anime-movies" to "Latest Anime Movies",
        "home-fresh-cartoon-films" to "Fresh Cartoon Films",
        "series" to "All Anime Series",
        "movies" to "All Anime Movies",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // WordPress ?s= is server-rendered here (verified live
        // 2026-11-22: HTTP 200, real results, no Cloudflare challenge).
        // Pagination is untested, so search is page 1 only.
        if (page > 1) return emptyList<SearchResponse>().toNewSearchResponseList()
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(Jsoup.parse(html)).toNewSearchResponseList()
    }

    // "Recommended Series" / "Recommended Movies" section. The site has
    // NO cast block (the cast list <ul class="cast-lst"> is empty), so
    // actors stay unset rather than faked — the Cast row will simply not
    // appear for this plugin.
    private fun parseRecommendations(doc: Document): List<SearchResponse> {
        val header = doc.select("h3.section-title").firstOrNull {
            it.text().trim() == "Recommended Series" || it.text().trim() == "Recommended Movies"
        } ?: return emptyList()
        val section = header.closest("section") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in section.select("article.post")) {
            val item = parseCard(article) ?: continue
            if (seen.add(item.url)) out.add(item)
        }
        return out
    }

    private fun parseEpisodeRef(url: String): Pair<Int, Int>? {
        val m = Regex("-(\\d+)x(\\d+)/?$").find(url) ?: return null
        val s = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return s to e
    }

    private fun collectEpisodes(from: Document, poster: String?, out: MutableList<Episode>, seen: MutableSet<String>) {
        for (a in from.select("a[href*=/episode/]")) {
            val epUrl = fixUrl(a.attr("href"))
            if (epUrl.isEmpty() || !seen.add(epUrl)) continue
            val (s, e) = parseEpisodeRef(epUrl) ?: continue
            out.add(newEpisode(epUrl) {
                this.name = "Episode $e"
                this.episode = e
                this.season = s
                this.posterUrl = poster
            })
        }
    }

    // Post ID from <body class="... postid-2344 ..."> (WordPress renders
    // it on every single post page). Needed for the theme's season AJAX.
    private fun parsePostId(doc: Document): String? {
        val bodyClass = doc.selectFirst("body")?.attr("class") ?: return null
        return Regex("postid-(\\d+)").find(bodyClass)?.groupValues?.get(1)
    }

    // The theme loads a season's episodes via admin-ajax:
    // GET /wp-admin/admin-ajax.php?action=action_select_season&season=N&post=<id>
    // Response is the episode list HTML (same markup as the page).
    // Verified live 2026-11-22: Arifureta S1 returns episode links;
    // series with no episodes return an empty body.
    private suspend fun fetchSeasonAjax(postId: String, season: Int, poster: String?): List<Episode> {
        val html = try {
            app.get(
                "$mainUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$season&post=$postId",
                headers = browserHeaders
            ).text
        } catch (e: Exception) { return emptyList() }
        if (html.isBlank()) return emptyList()
        val list = mutableListOf<Episode>()
        collectEpisodes(Jsoup.parse(html), poster, list, mutableSetOf())
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val bodyText = doc.body()?.text() ?: return null
        val title = cleanTitle(doc.selectFirst("h1.entry-title")?.text() ?: return null)
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst(".post img")?.attr("src")?.let { fixUrl(it) }
        val plot = parseOverview(bodyText)
        val year = parseYear(doc.selectFirst(".entry-meta .year")?.text())
        val recommendations = parseRecommendations(doc)

        if (url.contains("/movies/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = parseGenres(bodyText)
                this.year = year
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        }

        // Series: the page only ever renders the LATEST season's
        // episodes in HTML. Other seasons come from the theme's own
        // admin-ajax endpoint (action_select_season) or the plain
        // /season/<slug>-<n>/ pages.
        // NOTE (verified live 2026-11-22): the site's newest series
        // (e.g. Big Hero 6, Firefly Wedding, Zatch Bell) have NO
        // episodes attached at all — the page carries no episode
        // section, the AJAX returns empty, search finds no episode
        // posts and guessed episode URLs 404. For those we return the
        // series with an empty episode list instead of null, so the
        // detail page renders instead of throwing "returned nothing".
        val slug = url.trimEnd('/').substringAfterLast("/")
        val seasons = doc.select(".sel-temp a[data-season]")
            .mapNotNull { it.attr("data-season").toIntOrNull() }.distinct().sorted()
        val postId = parsePostId(doc)
            ?: doc.selectFirst(".sel-temp a[data-post]")?.attr("data-post")?.takeIf { it.isNotEmpty() }
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        collectEpisodes(doc, poster, episodes, seen)
        // Probe every known season via the theme's AJAX endpoint. If the
        // page has no season selector at all, probe S1-S3 anyway — the
        // selector can be missing while episodes exist via AJAX.
        val seasonNumbers = seasons.ifEmpty { listOf(1, 2, 3) }
        if (postId != null) {
            val extra: List<List<Episode>> = seasonNumbers.amap { sn ->
                try {
                    fetchSeasonAjax(postId, sn, poster)
                } catch (e: Exception) { emptyList() }
            }
            for (list in extra) for (ep in list) {
                if (seen.add(ep.data)) episodes.add(ep)
            }
        }
        // Fallback for seasons the AJAX did not return: plain
        // /season/<slug>-<n>/ pages (v1's approach). Only when the
        // season numbers are real (from .sel-temp), to avoid wasted
        // requests on series that simply have no episodes.
        val missing = seasons.filter { sn -> episodes.none { it.season == sn } }
        if (missing.isNotEmpty()) {
            val extra: List<List<Episode>> = missing.amap { sn ->
                try {
                    val sDoc = Jsoup.parse(
                        app.get("$mainUrl/season/$slug-$sn/", headers = browserHeaders).text
                    )
                    val list = mutableListOf<Episode>()
                    collectEpisodes(sDoc, poster, list, mutableSetOf())
                    list
                } catch (e: Exception) { emptyList() }
            }
            for (list in extra) for (ep in list) {
                if (seen.add(ep.data)) episodes.add(ep)
            }
        }
        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.recommendations = recommendations.takeIf { it.isNotEmpty() }
        }
    }

    // ---- stream resolution ----

    // The /api/player1.php data blob is URL-encoded base64 JSON:
    // [{"language":"Hindi","link":"https://short.icu/<code>"}, ...]
    // The data param is URL-encoded: base64 padding "=" arrives as
    // "%3D" (and "+" / "/" as "%2B" / "%2F") — "%" is an illegal base64
    // char, so the raw string must be URL-decoded FIRST (the AnimeSalt
    // v4 lesson: titles whose data needs padding silently lost Server 2
    // before that fix). The "+" guard keeps a literal "+" in the base64
    // from becoming a space (URLDecoder treats "+" as a space in form
    // encoding). The theme also emits base64url ("-" / "_") on some
    // pages, so those are normalised too, and missing padding is added.
    private fun decodePlayerData(data: String): List<LangLink> {
        return try {
            var b64 = URLDecoder.decode(data.replace("+", "%2B"), Charsets.UTF_8)
                .replace("-", "+").replace("_", "/")
            while (b64.length % 4 != 0) b64 += "="
            val json = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            tryParseJson<List<LangLink>>(json) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    // FirePlayer/VPlayer embed (as-cdn26.top etc.): the embed HTML NEVER
    // contains the video URL — the player fetches it via:
    //   POST {origin}/player/index.php?data={hash}&do=getVideo
    //   body (form): hash={hash}&r={site base URL}
    //   -> JSON { securedLink | videoSource } = HLS master playlist URL.
    // Method from the working Nuvio provider for this exact site
    // (mayurgj/nama providers/watchanimeworld.js v1.2.1, fpGetMasterUrl).
    // NOTE: the host is unreachable from this VM (empty reply / no DNS),
    // so this path is a fallback only, verified by the phone test.
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

    // short.icu is a redirect shortener: follow the chain manually (up
    // to 5 hops) so the real video host is seen instead of the shortener
    // domain, which has no extractor of its own.
    private suspend fun resolveRedirects(url: String, referer: String, maxHops: Int = 5): String {
        var current = url
        repeat(maxHops) {
            val res = try {
                app.get(current, headers = browserHeaders + ("Referer" to referer), allowRedirects = false)
            } catch (e: Exception) { return current }
            if (res.code !in 300..399) return current
            val loc = res.headers["Location"] ?: res.headers["location"] ?: return current
            val next = if (loc.startsWith("http")) loc else try {
                java.net.URL(java.net.URL(current), loc).toString()
            } catch (e: Exception) { return current }
            if (next == current) return current
            current = next
        }
        return current
    }

    // Resolve a short.icu link to its final host via the manual
    // redirect chain. If the shortener answers without a 3xx from this
    // client, the link is returned unchanged and the caller falls back
    // to the stock extractor (which matches nothing for short.icu).
    private suspend fun resolveShortLink(url: String, referer: String): String {
        return resolveRedirects(url, referer)
    }

    private fun directMediaUrl(url: String): String? {
        val path = url.substringBefore("#").substringBefore("?")
        return if (path.endsWith(".m3u8", true) || path.endsWith(".mp4", true) ||
            path.endsWith(".mkv", true) || path.endsWith(".mov", true)
        ) url else null
    }

    // Server 2 (abyssplayer): the JWPlayer page carries one long base64
    // blob (JSON with slug, md5_id, user_id and an encrypted "media"
    // field). The blob is decoded via the enc-dec.app API, which
    // returns direct .mp4 URLs per quality. This is the exact method
    // Phisher's WORKING Animesalt v18 uses — and the method AnimeSalt
    // v4 uses on the phone (phone-confirmed 2026-11-22) for the sister
    // site animesalt.cx. Verified live from this VM:
    // POST https://enc-dec.app/api/dec-abyss with
    // {"text":"<full blob>"} returns HTTP 200
    // {"status":200,"result":{"sources":[{"url":"https://<host>.sssrr.org
    // /sora/<size>/<token>","size":...,"type":"480p"|"720p"|"1080p",
    // "codec":"h264","status":true}, ...]}}.
    // Sending just the URL or just the "media" string returns HTTP 500
    // — the FULL blob is what works. The decoded .mp4 URLs are IP-bound
    // (they 403 from this VM whose egress proxy rotates IPs), so on the
    // phone the plugin decodes and the player fetches from the same IP.
    //
    // Why short.icu -> abyssplayer: Phisher's compiled plugin has a
    // "Short" extractor (mainUrl https://short.icu) that EXTENDS his
    // "Abyass" extractor with no getUrl of its own — short.icu links
    // redirect to abyssplayer pages and the same Abyass decoder handles
    // them. short.icu itself is unreachable from this VM (HTTP 000),
    // so the redirect is verified by the phone test only.
    private suspend fun resolveAbyssLinks(abyssUrl: String, lang: String): List<ExtractorLink> {
        return try {
            val page = app.get(abyssUrl, headers = browserHeaders).text
            val blob = Regex("""["'][A-Za-z0-9+/=]{200,}["']""")
                .findAll(page)
                .map { it.value.trim('"', '\'') }
                .firstOrNull { candidate ->
                    try {
                        val decoded = String(Base64.getDecoder().decode(candidate), Charsets.UTF_8)
                        decoded.contains("\"media\"")
                    } catch (e: Exception) { false }
                } ?: return emptyList()
            val requestBody = """{"text":"$blob"}""".toRequestBody("application/json".toMediaTypeOrNull())
            val res = app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                ),
                requestBody = requestBody
            )
            val parsed = tryParseJson<AbyssResponse>(res.text) ?: return emptyList()
            val out = mutableListOf<ExtractorLink>()
            for (source in parsed.result?.sources.orEmpty()) {
                val url = source.url?.takeIf { it.isNotBlank() } ?: continue
                val label = source.type?.takeIf { it.isNotBlank() } ?: "Unknown"
                out.add(
                    newExtractorLink(
                        source = "WatchAnimeWorld",
                        name = "Server 2 $lang $label",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = abyssUrl
                        this.quality = getQualityFromName(label)
                    }
                )
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    // One Server 2 language entry: short.icu -> final host -> links.
    // Returns the links instead of calling the callback, so loadLinks
    // can resolve all languages concurrently and emit in page order.
    private suspend fun resolveServer2Entry(
        entry: LangLink,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val link = entry.link ?: return emptyList()
        val lang = entry.language ?: "Multi-Audio"
        val direct = directMediaUrl(link)
        if (direct != null) {
            return listOf(
                newExtractorLink(
                    source = "WatchAnimeWorld",
                    name = "Server 2 $lang",
                    url = direct,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        val finalUrl = try {
            resolveShortLink(link, pageUrl)
        } catch (e: Exception) { link }
        if (finalUrl.contains("abyssplayer.com")) {
            return resolveAbyssLinks(finalUrl, lang)
        }
        val directFinal = directMediaUrl(finalUrl)
        if (directFinal != null) {
            return listOf(
                newExtractorLink(
                    source = "WatchAnimeWorld",
                    name = "Server 2 $lang",
                    url = directFinal,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        // Fall back to the stock extractor for whatever host it landed on.
        val out = mutableListOf<ExtractorLink>()
        try {
            loadExtractor(finalUrl, subtitleCallback) { out.add(it) }
        } catch (e: Exception) { /* no extractor matched */ }
        return out
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

        // ---- Server 1 (fallback): FirePlayer/VPlayer embed (as-cdn26.top) ----
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
                        source = "WatchAnimeWorld",
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

        // ---- Server 2: multi-language player (the main path) ----
        // iframe data-src="/api/player1.php?data=<b64 JSON>" — the JSON
        // maps each audio language to a short.icu link. short.icu
        // redirects to player.abyssplayer.com, which is resolved through
        // the enc-dec.app dec-abyss API into direct .mp4 URLs per
        // quality (the AnimeSalt v4 method, phone-confirmed). Some
        // episodes only have Server 1, so this block is optional.
        val playerData = doc.selectFirst("#options-1 iframe[data-src]")?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("iframe[data-src*=player1.php]")?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("iframe[src*=player1.php]")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("iframe[src*=player.php]")?.attr("src")?.takeIf { it.isNotEmpty() }
        val langLinks = playerData
            ?.substringAfter("data=", "")
            ?.substringBefore("&")
            ?.takeIf { it.isNotEmpty() }
            ?.let { decodePlayerData(it) }
            ?: emptyList()
        if (langLinks.isNotEmpty()) {
            // Resolve every language CONCURRENTLY: each costs a redirect
            // chain plus an abyss fetch plus a dec-abyss POST, and doing
            // them one after another regularly blows the app's 30s
            // loadLinks timeout.
            val resolved: List<List<ExtractorLink>> = langLinks.amap { entry ->
                try {
                    resolveServer2Entry(entry, pageUrl, subtitleCallback)
                } catch (e: Exception) { emptyList() }
            }
            // Callbacks are emitted sequentially in page order, so the
            // source list order is unchanged.
            for (list in resolved) for (link in list) {
                callback(link)
                found = true
            }
        }

        return found
    }
}