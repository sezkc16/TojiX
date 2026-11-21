package com.animedubhindi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class AnimeDubHindiProvider : MainAPI() {

    override var mainUrl = "https://www.animedubhindi.link"
    override var name = "AnimeDubHindi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    // The actual download pages live on a second WordPress install:
    // new.adhlinks.com/episode/<slug>/ for series,
    // new.adhlinks.com/movie/<slug>/ for movies. The slug is NOT
    // derivable from the post slug (e.g. blue-box-season-2 ->
    // bluebox-s2), so load() always follows the real "Download / Watch"
    // link from the post page.
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Episode data passed to loadLinks (as JSON string).
    // One entry per host button under one quality row.
    data class LinkEntry(
        @JsonProperty("label") val label: String,  // full quality text, e.g. "480P x264 [Size: 125mb]"
        @JsonProperty("host") val host: String,    // "hubcloud" | "gdflix" | "filepress"
        @JsonProperty("id") val id: String,        // hubcloud code / gdflix id / full filepress URL
        @JsonProperty("q") val quality: Int,       // Qualities value
    )

    data class EpisodeData(
        @JsonProperty("links") val links: List<LinkEntry>? = null,
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

    // HubCloud moved from hubcloud.pw to hubcloud.mov (verified live
    // 2026-11-15 on HDHub4u: hubdrive.blog pages now link
    // hubcloud.mov/drive/<code>). Accept any hubcloud TLD so the next
    // move does not break resolution again.
    private fun hubCloudCode(text: String): String? {
        return Regex("hubcloud\\.[a-z]+/drive/([A-Za-z0-9]+)").find(text)?.groupValues?.getOrNull(1)
    }

    private fun toJson(data: EpisodeData): String? {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
        } catch (e: Exception) { null }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    // Post titles are "Name Season N Hindi, Tamil, ... (Multi Audio)
    // BluRay Episodes Download". Cut everything from the first language
    // / format marker so the listing shows just "Name Season N" (or
    // "Name (Year)" for movies).
    private fun listingTitle(raw: String): String {
        var t = raw
        for (marker in listOf(" Hindi", " (Multi Audio", " (Dual Audio", " Episodes Download", " Movie Download", " BluRay", " WEB-DL", " NF WEB-DL")) {
            val i = t.indexOf(marker)
            if (i > 0) t = t.substring(0, i)
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    // The Series/Movie Information block carries the clean title as
    // "Title: Naruto Shippuden (Season 18) MAL Rating: ..." /
    // "Title: Paprika (2006) Score: ..." / "Title: Blue Box Synonyms: ...".
    private fun parseInfoTitle(text: String): String? {
        return Regex("Title:\\s*(.+?)\\s*(?:MAL Rating|Rating|Score|Genres|Synonyms|Quality):")
            .find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parseYear(rawTitle: String): Int? {
        return Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseSeason(rawTitle: String): Int {
        return Regex("(?i)Season\\s*(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    }

    // The synopsis sits between "Synopsis:" and "Screenshots" in the
    // post content text.
    private fun parseDescription(text: String): String? {
        val idx = text.indexOf("Synopsis:")
        if (idx < 0) return null
        var plot = text.substring(idx + "Synopsis:".length).trim()
        val end = plot.indexOf("Screenshots")
        if (end > 0) plot = plot.substring(0, end).trim()
        return plot.takeIf { it.length >= 20 }
    }

    // "Genres: Action | Adventure | Comedy | Fantasy Quality: ..."
    private fun parseGenres(text: String): List<String> {
        val m = Regex("Genres:\\s*(.+?)\\s*(?:Quality|Audio Tracks|Subtitle|Total Episode|Duration|Uploader|Official Dub|Encoder):")
            .find(text) ?: return emptyList()
        return m.groupValues[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // MAL Rating: 7.9/10 (series) / Score: 7.9/10 (movies).
    private fun parseScore(text: String): Score? {
        val m = Regex("(?:MAL Rating|Score):\\s*(\\d+(?:\\.\\d+)?)\\s*/\\s*10").find(text) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.let { Score.from10(it) }
    }

    // The site has NO cast block and NO related-titles block on post
    // pages (Synopsis + Series/Movie Information only), so actors and
    // recommendations stay unset rather than faked — the Cast and More
    // Like This rows will simply not appear for this plugin.

    // Parse a listing page (home / category / search) into search responses.
    // Astra theme: <article class="post ..."> with a .post-thumb link +
    // TMDB poster <img> (alt = full post title) and h2.entry-title.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.post")) {
            val link = article.selectFirst(".post-thumb a")
                ?: article.selectFirst("h2.entry-title a")
                ?: article.selectFirst("a[href]") ?: continue
            val href = link.attr("href")
            if (href.isEmpty()) continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            val img = article.selectFirst(".post-thumb img")
                ?: article.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            var rawTitle = img?.attr("alt")?.trim().orEmpty()
            if (rawTitle.isEmpty()) {
                rawTitle = article.selectFirst("h2.entry-title a")?.text()?.trim().orEmpty()
            }
            if (rawTitle.isEmpty()) continue
            val title = listingTitle(rawTitle)
            if (title.isEmpty()) continue
            val isSeries = rawTitle.contains("Season", true)
                || article.classNames().contains("category-series")
            if (isSeries) {
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data
        // Categories live at /category/<slug>/, home paginates at
        // /page/<n>/ (verified live: /category/series/page/2/ exists).
        val url = when {
            cat.isEmpty() -> if (page <= 1) mainUrl else "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$cat/"
            else -> "$mainUrl/$cat/page/$page/"
        }
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(html)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/series" to "Anime Series",
        "category/movie" to "Anime Movies",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // WordPress ?s= is server-rendered here (verified live
        // 2026-11-22: HTTP 200, 10 results, no Cloudflare challenge).
        // Pagination is untested, so search is page 1 only.
        if (page > 1) return emptyList<com.lagradost.cloudstream3.SearchResponse>().toNewSearchResponseList()
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    // ---- adhlinks page parsing ----

    // One quality row's host buttons. The label is the full quality text
    // (e.g. "480P x264 [Size: 125mb]"). MEGA (/redirect.php?data=...) and
    // Multi (/re.php?data=...) are encrypted server-side redirect tokens
    // to download-only hosts — not playable streams, skipped.
    private fun hostEntry(text: String, href: String, label: String): LinkEntry? {
        return when {
            text.equals("Hcloud", true) || text.equals("HubCloud", true) -> {
                hubCloudCode(href)?.let { LinkEntry(label, "hubcloud", it, qualityValue(label)) }
            }
            text.equals("GDF", true) || text.equals("GDFlix", true) -> {
                val id = href.substringAfter("/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isNotEmpty()) LinkEntry(label, "gdflix", id, qualityValue(label)) else null
            }
            text.equals("Fprs", true) || text.equals("FileBee", true) -> {
                // FilePress-family hosts (fpgo.xyz on episode pages,
                // filebee.live on movie pages) — stock loadExtractor.
                if (href.startsWith("http")) LinkEntry(label, "filepress", href, qualityValue(label)) else null
            }
            else -> null
        }
    }

    // Episode page: an <h4> holds the quality rows as text + anchors in
    // document order — "480P x264 [Size: 125mb]<br><a>Hcloud</a> |
    // <a>GDF</a> | <a>MEGA</a> | <a>Multi</a> | <a>Fprs</a><br><br>
    // 720P ...". Walk the child nodes, tracking the current label.
    private fun parseQualityBlock(h4: Element, out: MutableList<LinkEntry>) {
        var currentLabel: String? = null
        val qualityRe = Regex("(?i)(480P|720P|1080P|2160P|4K)\\b[^\\n]*")
        for (node in h4.childNodes()) {
            if (node is TextNode) {
                val t = cleanTitle(node.text())
                if (t.isEmpty()) continue
                qualityRe.find(t)?.let { currentLabel = it.value.trim() }
            } else if (node is Element && node.tagName() == "a") {
                val text = node.text().trim()
                if (text.isEmpty()) continue  // empty anchor before Hcloud on some rows
                val label = currentLabel ?: "Download"
                hostEntry(text, node.attr("href"), label)?.let { out.add(it) }
            }
        }
    }

    // Movie page: <div class="pro-quality-wrapper"> per quality, with a
    // .pro-ep-quality span ("[480P x264]") and a .pro-btn-group of host
    // buttons (HubCloud / GDFlix / MEGA / Multi / FileBee).
    private fun parseQualityWrapper(wrapper: Element): List<LinkEntry> {
        val out = mutableListOf<LinkEntry>()
        val label = wrapper.selectFirst(".pro-ep-quality")?.text()?.trim() ?: "Download"
        for (a in wrapper.select("a[href]")) {
            val text = a.text().trim()
            if (text.isEmpty()) continue
            hostEntry(text, a.attr("href"), label)?.let { out.add(it) }
        }
        return out
    }

    // Walk the adhlinks content in document order, tracking the current
    // episode ("Episode: 373" / "Episode: 01 [New]"). "Zip Pack" h2s do
    // not match the episode regex, so pack links are never collected.
    // Movie pages have no episode h2s — their .pro-quality-wrapper
    // blocks land in the movie link list instead.
    private fun parseAdhLinks(doc: Document): Pair<List<LinkEntry>, Map<Int, List<LinkEntry>>> {
        val content = doc.selectFirst("div.entry-content") ?: doc.body()
            ?: return Pair(emptyList(), emptyMap())
        val movieLinks = mutableListOf<LinkEntry>()
        val epLinks = sortedMapOf<Int, MutableList<LinkEntry>>()
        var currentEp: Int? = null
        for (el in content.select("h2, h4, div.pro-quality-wrapper")) {
            when {
                el.tagName() == "h2" -> {
                    val epM = Regex("(?i)^Episode:\\s*(\\d+)").find(el.text().trim())
                    currentEp = epM?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                el.tagName() == "h4" -> {
                    if (currentEp != null) {
                        parseQualityBlock(el, epLinks.getOrPut(currentEp) { mutableListOf() })
                    }
                }
                el.hasClass("pro-quality-wrapper") -> {
                    if (currentEp == null) movieLinks.addAll(parseQualityWrapper(el))
                }
            }
        }
        return Pair(movieLinks, epLinks)
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val content = doc.selectFirst("div.entry-content") ?: doc.body() ?: return null
        val contentText = content.text()
        val infoTitle = parseInfoTitle(contentText)
        val rawTitle = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val title = (infoTitle ?: rawTitle).replace(Regex("\\s*-\\s*Anime Dub Hindi\\s*$"), "").trim()
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: content.selectFirst("img")?.attr("src")
        val description = parseDescription(contentText)
        val tags = parseGenres(contentText)
        val score = parseScore(contentText)
        val year = parseYear(infoTitle ?: rawTitle)
        val season = parseSeason(infoTitle ?: rawTitle)
        val adhUrl = content.selectFirst("a[href*=adhlinks.com]")?.attr("href") ?: return null
        val adhHtml = try {
            app.get(adhUrl, headers = browserHeaders).text
        } catch (e: Exception) { return null }
        val adhDoc = Jsoup.parse(adhHtml)
        val (movieLinks, epLinks) = parseAdhLinks(adhDoc)
        val isSeries = epLinks.isNotEmpty() || (infoTitle ?: rawTitle).contains("Season", true)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            if (epLinks.isNotEmpty()) {
                for ((epNum, links) in epLinks) {
                    val dataJson = toJson(EpisodeData(links = links)) ?: continue
                    episodes.add(newEpisode(dataJson) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = season
                        this.posterUrl = poster
                    })
                }
            } else {
                // Season pack: a "Season" title with no per-episode
                // sections — all quality buttons belong to one episode.
                if (movieLinks.isEmpty()) return null
                val dataJson = toJson(EpisodeData(links = movieLinks)) ?: return null
                episodes.add(newEpisode(dataJson) {
                    this.name = "Season Pack"
                    this.season = season
                    this.posterUrl = poster
                })
            }
            if (episodes.isEmpty()) return null
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.score = score
            }
        } else {
            if (movieLinks.isEmpty()) return null
            val dataJson = toJson(EpisodeData(links = movieLinks)) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.score = score
                this.year = year
            }
        }
    }

    // ---- Link resolvers (HubCloud chain copied from DesireMovies) ----

    data class HubServer(
        val server: String,
        val url: String,
    )

    // FSL/FSLv2 button href: usually the R2 file URL (playable as-is).
    // When it is NOT an R2 URL, the page's own JS appends '1' + the
    // current minute to the href before the user clicks — mimic that.
    private fun fslUrl(href: String): String {
        if (href.contains("r2.cloudflarestorage.com")) return href
        val minute = java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE)
        return href + "1" + minute
    }

    // Resolve the 10Gbps pixel link to the direct Google video file.
    // Chain: pixel.hubcloud.pw/?id=<token> -> 302 workers.dev
    // -> 302 cryptonewz.online/dl.php?link=<googleusercontent URL>.
    // The workers.dev hop is flaky (HTTP 500) — any failure just skips
    // 10G for this file, it never breaks the other servers.
    private suspend fun resolvePixel10G(pixelUrl: String): String? {
        return try {
            val hop1 = app.get(pixelUrl, headers = browserHeaders, allowRedirects = false)
            val workersUrl = hop1.headers["Location"] ?: hop1.headers["location"] ?: return null
            if (!workersUrl.startsWith("http")) return null
            val hop2 = app.get(workersUrl, headers = browserHeaders, allowRedirects = false)
            val dlUrl = hop2.headers["Location"] ?: hop2.headers["location"] ?: return null
            val linkParam = Regex("[?&]link=([^&]+)").find(dlUrl)?.groupValues?.getOrNull(1) ?: return null
            val gu = java.net.URLDecoder.decode(linkParam, "UTF-8").trim()
            if (!gu.startsWith("https://video-downloads.googleusercontent.com/")) return null
            val head = try { app.head(gu, headers = browserHeaders) } catch (e: Exception) { return null }
            if (head.code != 200) return null
            val ct = head.headers["Content-Type"] ?: head.headers["content-type"] ?: ""
            if (ct.isNotEmpty() && !ct.startsWith("video/") && !ct.startsWith("application/octet-stream")) return null
            gu
        } catch (e: Exception) { null }
    }

    // Resolve a HubCloud file id to one entry PER SERVER on the gamerxyt
    // page (FSL, FSLv2, PixelDrain, 10G), each with its direct file URL.
    private suspend fun resolveHubCloudServers(hubCode: String): List<HubServer> {
        val hubUrl = "https://hubcloud.ist/drive/$hubCode"
        val hubHtml = try { app.get(hubUrl, headers = browserHeaders).text } catch (e: Exception) { return emptyList() }
        val gamerUrl = Regex("https?://gamerxyt\\.com/hubcloud\\.php\\?host=hubcloud&id=[A-Za-z0-9]+&token=[A-Za-z0-9]+")
            .find(hubHtml)?.value ?: return emptyList()
        val gamerHtml = try { app.get(gamerUrl, headers = browserHeaders).text } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<HubServer>()
        // Dedup on (server, url): FSL and FSLv2 sometimes point at the
        // same file URL — with URL-only dedup the second one was dropped
        // and FSLv2 never showed. The same URL under a different server
        // name is a different source row, so it must be kept.
        val seen = mutableSetOf<Pair<String, String>>()
        fun add(server: String, url: String) {
            val u = url.trim()
            if (u.isNotEmpty() && seen.add(server to u)) out.add(HubServer(server, u))
        }
        val btnRe = Regex("<a\\s+href=\"([^\"]+)\"[^>]*>\\s*(?:<i[^>]*></i>\\s*)?Download \\[([^\\]]+)\\]")
        for (m in btnRe.findAll(gamerHtml)) {
            val href = m.groupValues[1].trim()
            val label = m.groupValues[2].trim()
            when {
                label.contains("FSLv2", ignoreCase = true) -> add("FSLv2", fslUrl(href))
                label.contains("FSL", ignoreCase = true) -> add("FSL", fslUrl(href))
                label.contains("Pixel", ignoreCase = true) -> {
                    val id = Regex("pixeldrain\\.dev/u/([A-Za-z0-9]+)").find(href)?.groupValues?.getOrNull(1) ?: continue
                    val apiUrl = "https://pixeldrain.dev/api/file/$id"
                    val ok = try {
                        app.head(apiUrl, headers = browserHeaders).code == 200
                    } catch (e: Exception) { false }
                    if (ok) add("PixelDrain", apiUrl)
                }
                label.contains("10G", ignoreCase = true) -> {
                    resolvePixel10G(href)?.let { add("10G", it) }
                }
                else -> { /* Buzz Server and unknown hosts: not a direct stream, skip */ }
            }
        }
        // The PixelServer button can point at a DEAD Pixeldrain file while
        // another mirror on the same page is alive — if no PixelDrain came
        // from a button, take the first working one from the page.
        if (out.none { it.server == "PixelDrain" }) {
            val pdIds = Regex("https?://pixeldrain\\.dev/u/([A-Za-z0-9]+)")
                .findAll(gamerHtml).map { it.groupValues[1] }.distinct().toList()
            for (id in pdIds) {
                val apiUrl = "https://pixeldrain.dev/api/file/$id"
                val ok = try {
                    app.head(apiUrl, headers = browserHeaders).code == 200
                } catch (e: Exception) { false }
                if (ok) { add("PixelDrain", apiUrl); break }
            }
        }
        if (out.isNotEmpty()) return out
        // Fallback: old single-link behaviour (first R2 URL as "HubCloud").
        Regex("https?://[^\"' ]+\\.r2\\.cloudflarestorage\\.com/[^\"' ]+")
            .find(gamerHtml)?.value?.let { add("HubCloud", it); return out }
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        var found = false
        val entries = ep.links ?: return false

        // Resolve every link entry CONCURRENTLY: each entry costs a
        // HubCloud chain (several HTTP hops), and resolving them one
        // after another regularly blows the app's 30s loadLinks timeout.
        // amap is CloudStream's own concurrent map (no kotlinx import
        // needed — the gradle plugin does not expose kotlinx-coroutines
        // to provider modules).
        data class ResolvedEntry(
            val servers: List<HubServer>,
        )

        val resolved: List<ResolvedEntry> = entries.amap { entry ->
            val servers = if (entry.host == "hubcloud") {
                resolveHubCloudServers(entry.id)
            } else {
                emptyList()
            }
            ResolvedEntry(servers)
        }

        // Callbacks are emitted sequentially in page order, so the
        // source list order is unchanged.
        entries.zip(resolved).forEach { (entry, res) ->
            when (entry.host) {
                "hubcloud" -> {
                    for (server in res.servers) {
                        val name = if (server.server == "HubCloud") "HubCloud ${entry.label}"
                            else "HubCloud ${server.server} ${entry.label}"
                        callback(
                            newExtractorLink(
                                source = "HubCloud",
                                name = name,
                                url = server.url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = entry.quality
                                this.referer = "https://hubcloud.pw/"
                            }
                        )
                        found = true
                    }
                }
                // Best effort: GDFlix and FilePress sit behind Cloudflare
                // for plain HTTP; CloudStream's extractor handles them
                // when it can.
                "gdflix", "filepress" -> {
                    if (loadExtractor(entry.id, subtitleCallback, callback)) found = true
                }
            }
        }

        return found
    }
}
