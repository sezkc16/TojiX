package com.moviesflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

// Moviesflix (movieszflix.co.in) — DLE (not WordPress) movie/series index:
// Hindi-English dual audio + South dubs, WEB-DL 480p/720p/1080p.
//
// Verified live 2026-11-21 (all with plain HTTP + a browser User-Agent,
// no Cloudflare/Turnstile on any of these hops):
// - Homepage / category pages / search results are server-rendered
//   post cards: <article class="post-item"> with <a data-post-id
//   href="https://movieszflix.co.in/<id>-<slug>.html" title="..."> and
//   <img class="blog-picture" src="/uploads/posts/covers/*.webp">
//   (relative cover URL — made absolute). 15 cards per listing page,
//   homepage paginates as /page/N/.
// - Post pages carry a "Movie Info" / "Web-Series Info" block, a
//   "Movie-SYNOPSIS/PLOT:" paragraph (the real synopsis — used as the
//   description, never fabricated), and a "Download Links" section:
//   one <h3><span>480p</span></h3> / "720p x265" / "720p x264" /
//   "1080p x265" / "1080p x264" heading per button, each button an
//   <a class="btn" href="https://nexdrive.<tld>/genxfm<digits>/">.
//   nexdrive TLD varies (.help, .pics, ...). The "Watch Online"
//   iframe on posts is 404 — ignored; this is a download-link plugin.
// - DLE search is a POST to /index.php?do=search with
//   do=search&subaction=search&story=<query>. It 403s WITHOUT the
//   session cookie from a prior GET + Referer — so search() first
//   GETs the homepage (the app's client keeps the cookie), then
//   POSTs. Verified: POST with cookies returns 200 with the Jailer 2
//   post in the results. v1 implements page 1 only.
// - Link chain (verified end to end for Jailer 2 480p, 2026-11-21):
//   post button -> nexdrive.<tld>/genxfm<id>/ landing -> two
//   buttons: "G-Direct [Instant]" -> fast-dl.one/dl/<code> and
//   "VGMLINKS [G-Drive]" -> vgmlinks.live/<code>.
//   fast-dl.one/dl/<code> is a "Download Now" page whose JS
//   intercepts the click and fetches
//   dl/dl.php?code=<code>&secret=<secret>&prepare=1&hash=<24 hex>
//   &cfs=<16 hex> with Accept: application/json — NOTE the path is
//   /dl/dl.php (relative to /dl/<code>), NOT /dl.php (that 404s).
//   The prepare call returns {"ok":true,"url":
//   "https://video-downloads.googleusercontent.com/..."} — a direct
//   Google video file. Verified: HEAD 200, Content-Type: video/mkv,
//   ~522 MB for the Jailer 2 480p file (matches the 531MB post size).
//   Quirk: dl.php returns an empty reply over HTTP/1.1 and only
//   answers over HTTP/2 — OkHttp negotiates h2 via ALPN by default,
//   so the app should be fine; if the phone test shows prepare
//   failing, this is the first suspect.
// - vgmlinks.live is Cloudflare Turnstile-gated (form POSTs to
//   /<code> with a cf-turnstile widget) — deprioritized for v1; the
//   fast-dl path already yields a direct playable file, so v1 does
//   not block on vgmlinks.
// - Series: a series post's nexdrive landing lists one
//   <section class="episode"> per episode ("-:Episodes: N:-" +
//   its own fast-dl G-Direct button). load() splits those into
//   per-episode entries; if a landing has no episode sections it is
//   a season pack (one entry per quality link).
class MoviesflixProvider : MainAPI() {

    override var mainUrl = "https://movieszflix.co.in"
    override var name = "Moviesflix"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Episode data passed to loadLinks (as JSON string).
    // host: "nexdrive" = nexdrive.<tld>/genxfm<digits>/ landing page
    // (resolved in loadLinks: per-episode fast-dl codes), "fastdl" =
    // fast-dl.one/dl/<code> (already split per episode in load()).
    // episode: 0 = whole title / season pack, else the episode number.
    data class LinkEntry(
        @JsonProperty("label") val label: String,
        @JsonProperty("host") val host: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("q") val quality: Int,
        @JsonProperty("ep") val episode: Int = 0,
    )

    data class EpisodeData(
        @JsonProperty("links") val links: List<LinkEntry>? = null,
    )

    // fast-dl prepare response: {"ok":true,"url":"https://video-downloads.googleusercontent.com/..."}
    data class FastDlPrepare(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("url") val url: String? = null,
    )

    private fun qualityValue(label: String): Int {
        return when {
            label.contains("2160") || label.contains("4K", true) -> Qualities.P2160.value
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun toJson(data: EpisodeData): String? {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
        } catch (e: Exception) { null }
    }

    private fun cleanText(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    private fun randomHex(chars: Int): String {
        val rnd = java.security.SecureRandom()
        val hex = "0123456789abcdef"
        return buildString {
            repeat(chars) { append(hex[rnd.nextInt(16)]) }
        }
    }

    // Display title: cut at the first quality token (480p/720p/1080p/
    // 2160p), strip bracket groups, the trailing site name and trailing
    // format tokens.
    // "Jailer 2 2026 Hindi - Tamil Dual Audio Moviesflix - [WEB-DL 720p - 480p - 1080p]"
    //   -> "Jailer 2 2026 Hindi - Tamil Dual Audio"
    private fun displayTitle(raw: String): String {
        var t = raw
            .replace(Regex("(?i) – Moviesflix.*$"), "")
            .replace(Regex("(?i) - Download.*$"), "")
        val cut = Regex("\\b(480p|720p|1080p|2160p)\\b").find(t)
        if (cut != null) t = t.substring(0, cut.range.first)
        t = t.replace(Regex("\\s*\\[.*?\\]\\s*"), " ")
        t = t.replace(Regex("(?i)\\s*-?\\s*Moviesflix\\s*-?\\s*$"), "")
        // Trailing format tokens left over after the quality cut.
        var prev: String
        do {
            prev = t
            t = t.replace(Regex("(?i)\\s*[-–|]?\\s*(WEB-DL|WEBRip|BluRay|HDRip|HDCAM|Download)\\s*$"), "")
        } while (t != prev)
        t = t.replace(Regex("[\\s\\-–|]+$"), "")
        return cleanText(t)
    }

    // Poster URL: the site's covers are relative
    // (/uploads/posts/covers/*.webp) — make them absolute. Prefer
    // data-src (lazy) over src, skip data: placeholders.
    private fun posterUrl(img: org.jsoup.nodes.Element?): String? {
        val raw = img?.attr("data-src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            ?: img?.attr("src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            ?: return null
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("/") -> mainUrl + raw
            else -> "$mainUrl/$raw"
        }
    }

    // Parse a listing page (home / category / search results) into
    // search responses. Post cards are <article class="post-item">
    // with an <a data-post-id href=".../<id>-<slug>.html" title="...">
    // wrapping the cover <img class="blog-picture">. Each card's link
    // appears twice (image + title) — dedupe by URL.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("article.post-item a[href*=.html]")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!Regex("/\\d+-").containsMatchIn(url)) continue
            if (!seen.add(url)) continue
            var title = cleanText(a.attr("title"))
            if (title.isEmpty()) title = cleanText(a.text())
            if (title.isEmpty()) {
                title = cleanText(a.parents().firstOrNull { it.hasClass("post-item") }
                    ?.selectFirst("h2, h3, .post-title")?.text() ?: "")
            }
            if (title.isEmpty()) continue
            val card = a.parents().firstOrNull { it.hasClass("post-item") }
            val img = a.selectFirst("img") ?: card?.selectFirst("img")
            val poster = posterUrl(img)
            val display = displayTitle(title)
            if (display.isEmpty()) continue
            val isSeries = display.contains("Season", true)
                || display.contains("Web Series", true)
                || display.contains("TV Series", true)
                || Regex("(?i)\\bS\\d{1,2}\\b").containsMatchIn(display)
                || url.contains("/tv-shows/", true)
            if (isSeries) {
                out.add(newTvSeriesSearchResponse(display, url, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(display, url, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data
        val url = when {
            cat.isEmpty() -> if (page <= 1) mainUrl else "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$cat/"
            else -> "$mainUrl/$cat/page/$page/"
        }
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(html)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    // Category slugs verified via the site's sitemap.xml (2026-11-21).
    override val mainPage = mainPageOf(
        "" to "Latest",
        "bollywood-movies" to "Bollywood",
        "hollywood-movies" to "Hollywood",
        "dual-audio-hindi-english-movies" to "Dual Audio",
        "tv-shows" to "TV Shows",
        "action" to "Action",
        "comedy" to "Comedy",
        "drama" to "Drama",
        "thriller" to "Thriller",
        "horror" to "Horror",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // v1: page 1 only. DLE search 403s without the session cookie
        // from a prior GET + Referer, so warm the homepage first (the
        // app's client keeps the cookie), then POST the search form.
        if (page > 1) return emptyList<com.lagradost.cloudstream3.SearchResponse>().toNewSearchResponseList()
        try {
            app.get(mainUrl, headers = browserHeaders)
        } catch (e: Exception) { }
        val res = try {
            app.post(
                "$mainUrl/index.php?do=search",
                headers = browserHeaders,
                referer = mainUrl,
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query.trim(),
                )
            )
        } catch (e: Exception) { return null }
        return parseListing(res.text).toNewSearchResponseList()
    }

    // The plot: the paragraph right after the "Movie-SYNOPSIS/PLOT:"
    // heading (same heading on movie and series posts). If a post has
    // no synopsis, return null (empty description — never fabricate,
    // and never use the og:description download blurb).
    private fun parsePlot(doc: org.jsoup.nodes.Document): String? {
        val m = Regex("(?is)SYNOPSIS/PLOT:</span></h3>\\s*<p>(.*?)</p>")
            .find(doc.html())
        val text = m?.groupValues?.getOrNull(1)?.let { cleanText(Jsoup.parse(it).text()) }
        if (!text.isNullOrEmpty() && text.length >= 20) return text
        return null
    }

    // Quality of a position in the raw HTML: the nearest quality
    // heading ("480p" / "720p x265" / "1080p x264" ...) before it.
    private fun qualityAtPosition(html: String, pos: Int): String {
        val head = Regex("(?i)(2160p|1080p|720p|480p)(?:\\s*x26[45])?\\s*</span></h3>")
            .findAll(html).filter { it.range.first < pos }.lastOrNull()
            ?: return ""
        return head.value.replace(Regex("</span></h3>$"), "").trim()
    }

    // Collect every nexdrive download link on a post page.
    // 1) Anchor scan: <a class="btn" href="https://nexdrive.<tld>/genxfm<digits>/">
    //    under a quality heading, with the button text carrying the size
    //    ("Click Here To Download [531MB]").
    // 2) Raw-HTML regex sweep (covers links with no plain anchor).
    private fun collectLinks(doc: org.jsoup.nodes.Document, html: String): List<LinkEntry> {
        val links = mutableListOf<LinkEntry>()
        val seen = mutableSetOf<String>()
        fun add(entry: LinkEntry?) {
            if (entry == null) return
            if (seen.add(entry.id)) links.add(entry)
        }
        val nexRe = Regex("https?://nexdrive\\.[a-z]+/genxfm\\d+/?")
        for (a in doc.select("a[href*=nexdrive.]")) {
            val href = a.attr("href")
            if (href.isEmpty() || !nexRe.containsMatchIn(href)) continue
            val pos = html.indexOf(href)
            val qLabel = qualityAtPosition(html, if (pos >= 0) pos else html.length)
            val btnText = cleanText(a.text())
            val size = Regex("\\[([^\\]]+)\\]").find(btnText)?.groupValues?.getOrNull(1) ?: ""
            val label = when {
                qLabel.isNotEmpty() && size.isNotEmpty() -> "$qLabel [$size]"
                qLabel.isNotEmpty() -> qLabel
                else -> "Download"
            }
            add(LinkEntry(label, "nexdrive", href, qualityValue(qLabel)))
        }
        for (m in nexRe.findAll(html)) {
            val href = m.value
            if (seen.contains(href)) continue
            val qLabel = qualityAtPosition(html, m.range.first)
            val label = qLabel.ifEmpty { "Download" }
            add(LinkEntry(label, "nexdrive", href, qualityValue(qLabel)))
        }
        return links
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: ""
        val title = displayTitle(rawTitle)
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("http") }
            ?: posterUrl(doc.selectFirst("img[src*=uploads/posts/covers]"))
        val plot = parsePlot(doc)
        val isSeries = title.contains("Season", true)
            || title.contains("Web Series", true)
            || title.contains("TV Series", true)
            || Regex("(?i)\\bS\\d{1,2}\\b").containsMatchIn(title)
            || url.contains("/tv-shows/", true)

        val links = collectLinks(doc, html)
        if (links.isEmpty()) return null

        if (isSeries) {
            return loadSeries(title, url, links, poster, plot)
        }
        val dataJson = toJson(EpisodeData(links = links)) ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // Series: split each nexdrive landing into its per-episode
    // fast-dl codes (the landing lists one <section class="episode">
    // per episode). One Episode entry per episode, with that
    // episode's links in every quality. If no landing exposes
    // episodes, fall back to season-pack entries (one per quality
    // link, resolved per-episode in loadLinks).
    private suspend fun loadSeries(
        title: String,
        url: String,
        links: List<LinkEntry>,
        poster: String?,
        plot: String?,
    ): LoadResponse? {
        val episodes = mutableListOf<Episode>()
        val perLink = links.amap { entry -> entry to fetchNexdriveEpisodes(entry.id) }
        val byEpisode = sortedMapOf<Int, MutableList<LinkEntry>>()
        var anyEpisodes = false
        for ((entry, eps) in perLink) {
            for ((epNum, code) in eps) {
                if (epNum > 0) anyEpisodes = true
                byEpisode.getOrPut(epNum) { mutableListOf() }
                    .add(LinkEntry(entry.label, "fastdl", code, entry.quality, epNum))
            }
        }
        if (anyEpisodes) {
            for ((epNum, epLinks) in byEpisode) {
                if (epNum <= 0) continue
                val dataJson = toJson(EpisodeData(links = epLinks)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }
        }
        if (episodes.isEmpty()) {
            for (link in links) {
                val label = if (link.label.contains("Pack", true)) link.label
                    else "Season Pack (${link.label})"
                val dataJson = toJson(EpisodeData(links = listOf(link))) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = label
                    this.posterUrl = poster
                })
            }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // Fetch one nexdrive.<tld>/genxfm<id>/ landing page and return its
    // fast-dl codes as (episode, code) pairs. Series landings list one
    // <section class="episode"> per episode ("-:Episodes: N:-" + a
    // G-Direct button); movie landings have a single "Download Links"
    // section with one G-Direct button (episode 0).
    private suspend fun fetchNexdriveEpisodes(nexUrl: String): List<Pair<Int, String>> {
        val html = try {
            app.get(nexUrl, headers = browserHeaders, timeout = 15L).text
        } catch (e: Exception) { return emptyList() }
        val doc = Jsoup.parse(html)
        val out = mutableListOf<Pair<Int, String>>()
        val seen = mutableSetOf<String>()
        for (sec in doc.select("section.episode")) {
            val epNum = Regex("(?i)Episodes?:\\s*(\\d+)")
                .find(sec.selectFirst("h2.ep-title")?.text() ?: "")
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val href = sec.selectFirst("a[href*=fast-dl.one/dl/]")?.attr("href") ?: continue
            val code = Regex("fast-dl\\.one/dl/([A-Za-z0-9]+)")
                .find(href)?.groupValues?.getOrNull(1) ?: continue
            if (seen.add(code)) out.add(epNum to code)
        }
        if (out.isNotEmpty()) return out
        val code = Regex("fast-dl\\.one/dl/([A-Za-z0-9]+)").find(html)
            ?.groupValues?.getOrNull(1) ?: return emptyList()
        return listOf(0 to code)
    }

    // fast-dl.one/dl/<code> -> direct Google video file.
    // The landing page's "Download Now" button is a relative
    // dl.php?code=<code>&secret=<secret> link; the page's JS fetches
    // /dl/dl.php with prepare=1 + random hash/cfs params and
    // Accept: application/json, and the server answers
    // {"ok":true,"url":"https://video-downloads.googleusercontent.com/..."}.
    // The returned URL is HEAD-validated (200 + video/* content type)
    // before use. Two attempts: the second re-fetches the landing in
    // case the secret rotated.
    private suspend fun resolveFastDl(code: String, referer: String?): String? {
        for (attempt in 0 until 2) {
            try {
                val landingHeaders = browserHeaders + mapOf(
                    "Referer" to (referer ?: mainUrl)
                )
                val landing = app.get(
                    "https://fast-dl.one/dl/$code",
                    headers = landingHeaders,
                    timeout = 15L
                ).text
                val m = Regex("dl\\.php\\?code=([A-Za-z0-9]+)&(?:amp;)?secret=([A-Za-z0-9]+)")
                    .find(landing) ?: continue
                val (dlCode, secret) = m.destructured
                val prepUrl = "https://fast-dl.one/dl/dl.php?code=$dlCode&secret=$secret" +
                    "&prepare=1&hash=${randomHex(24)}&cfs=${randomHex(16)}"
                val prepHeaders = browserHeaders + mapOf(
                    "Accept" to "application/json",
                    "Cache-Control" to "no-store",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                    "Referer" to "https://fast-dl.one/dl/$code"
                )
                val prep = app.get(prepUrl, headers = prepHeaders, timeout = 15L)
                val data = tryParseJson<FastDlPrepare>(prep.text)
                val gu = data?.url?.takeIf {
                    data.ok == true && it.startsWith("https://video-downloads.googleusercontent.com/")
                } ?: continue
                val head = try {
                    app.head(gu, headers = browserHeaders)
                } catch (e: Exception) { continue }
                if (head.code != 200) continue
                val ct = head.headers["Content-Type"] ?: head.headers["content-type"] ?: ""
                if (ct.isNotEmpty() && !ct.startsWith("video/") && !ct.startsWith("application/octet-stream")) continue
                return gu
            } catch (e: Exception) {
                // Fall through to the retry below.
            }
            if (attempt == 0) Thread.sleep(2000)
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        val entries = ep.links ?: return false
        var found = false

        data class Resolved(val url: String, val epNum: Int)

        // Each entry costs several HTTP hops (nexdrive landing +
        // fast-dl landing + prepare + HEAD), so resolve them
        // concurrently; the app's ~30s loadLinks budget is the limit.
        val resolved: List<List<Resolved>> = entries.amap { entry ->
            when (entry.host) {
                "fastdl" -> {
                    val gu = resolveFastDl(entry.id, null)
                    if (gu != null) listOf(Resolved(gu, entry.episode)) else emptyList()
                }
                "nexdrive" -> {
                    fetchNexdriveEpisodes(entry.id).mapNotNull { (epNum, code) ->
                        resolveFastDl(code, entry.id)?.let { Resolved(it, epNum) }
                    }
                }
                else -> emptyList()
            }
        }

        entries.zip(resolved).forEach { (entry, list) ->
            for (r in list) {
                val display = if (r.epNum > 0) "Episode ${r.epNum} — ${entry.label}" else entry.label
                callback(
                    newExtractorLink(
                        source = "G-Direct",
                        name = "G-Direct $display",
                        url = r.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = entry.quality
                        this.referer = "https://fast-dl.one/"
                    }
                )
                found = true
            }
        }

        return found
    }
}
