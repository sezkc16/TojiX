package com.desiremovies

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

class DesireMoviesProvider : MainAPI() {

    override var mainUrl = "https://1desiremovies.xyz"
    override var name = "DesireMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Episode data passed to loadLinks (as JSON string).
    // One entry per "GD & DOWNLOAD" button (one per quality), holding
    // the gyanigurus.online/view/<token> for that button. The token is
    // resolved in loadLinks (one fetch per button) — resolving the tokens
    // in load() would cost 7 fetches for a movie and ~70 for a series.
    data class LinkEntry(
        @JsonProperty("label") val label: String,  // full quality text, e.g. "1080p [3 GB]" or "2160p 4K"
        @JsonProperty("host") val host: String,    // always "gyan" (gyanigurus token)
        @JsonProperty("id") val id: String,        // gyanigurus view token
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

    // Post titles are "Name (Year) WEB-HDRip [Dual Audio] ... | <Category>
    // | DesireMovies". Display just "Name (Year)" for movies and
    // "Name (Year) [Season N]" for series, so two seasons of the same
    // show do not collapse into one title.
    private fun displayTitle(raw: String): String {
        val base = Regex("^(.*?\\(\\d{4}\\))").find(raw)?.groupValues?.getOrNull(1)?.trim()
            ?: raw.replace(Regex("\\s*\\[.*?\\]\\s*"), " ").replace(Regex("\\s+"), " ").trim()
        val season = Regex("(?i)Season\\s*(\\d+)").find(raw)?.groupValues?.getOrNull(1)
        return if (season != null && !base.contains("Season", true)) "$base [Season $season]" else base
    }

    private fun parseYear(rawTitle: String): Int? {
        return Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseSeason(rawTitle: String): Int {
        return Regex("(?i)Season\\s*(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    }

    // The Release Info block carries the synopsis as "Plot: ..." (movies
    // only — series pages have no Plot line, so description stays null
    // there). The plot runs until the "-: ScreenShot :-" section.
    private fun parseDescription(doc: Document): String? {
        val text = doc.body()?.text() ?: return null
        val idx = text.indexOf("Plot:")
        if (idx < 0) return null
        var plot = text.substring(idx + 5).trim()
        val end = plot.indexOf("ScreenShot")
        if (end > 0) plot = plot.substring(0, end).trim()
        plot = plot.replace(Regex("\\s*-:\\s*$"), "").trim()
        return plot.takeIf { it.length >= 20 }
    }

    // The site has NO cast block and NO related-titles block on post
    // pages (Release Info is only Title / Year / Size / Quality / IMDb /
    // Language / Genres / Plot), so actors and recommendations stay
    // unset rather than faked — the Cast and More Like This rows will
    // simply not appear for this plugin.

    // Parse a listing page (home / category / search) into search responses.
    // Posts are <article class="mh-loop-item"> with a thumbnail link and
    // a TMDB poster <img> whose alt is the full post title.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.mh-loop-item")) {
            val link = article.selectFirst("figure.mh-loop-thumb a")
                ?: article.selectFirst("a[href]") ?: continue
            val href = link.attr("href")
            if (href.isEmpty()) continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            val img = article.selectFirst("figure.mh-loop-thumb img")
                ?: article.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            var rawTitle = img?.attr("alt")?.trim().orEmpty()
            if (rawTitle.isEmpty()) {
                rawTitle = article.selectFirst("h3 a, .mh-loop-title a")?.text()?.trim().orEmpty()
            }
            if (rawTitle.isEmpty()) continue
            val title = displayTitle(rawTitle)
            val isSeries = rawTitle.contains("Season", true)
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
        // Categories live at /<slug>/ (no /category/ prefix — verified
        // live: /4k-movies/, /bollywood-movies-desiremovie/, /web-series/
        // all return 200 with posts). Home paginates at /page/<n>/.
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
        "bollywood-movies-desiremovie" to "Bollywood",
        "hollywood-movies-download-hindi" to "Hollywood Hindi",
        "south-movieshindi" to "South Hindi",
        "punjabi-movies" to "Punjabi",
        "web-series" to "Web Series",
        "tv-show" to "TV Shows",
        "4k-movies" to "4K Movies",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // WordPress ?s= answers with a Cloudflare "Just a moment..."
        // challenge (verified live 2026-11-22, HTTP 403), but
        // /search/<query> is server-rendered ("You searched for ...").
        // /search/<q>/page/2/ is untested, so search is page 1 only.
        if (page > 1) return emptyList<com.lagradost.cloudstream3.SearchResponse>().toNewSearchResponseList()
        val url = "$mainUrl/search/${query.trim().replace(" ", "+")}"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    // Walk the post content <p> elements in order, tracking the current
    // episode ("EP N" heading) and the current quality label
    // ("1080p [3 GB]" / "2160p 4K"). A "GD & DOWNLOAD" button
    // (gyanigurus.online/view/<token>) belongs to the last-seen quality
    // label and episode. Movies have no EP markers, so all their buttons
    // land in the movie's single link list. A Telegram banner <div>
    // sits between some labels and buttons, so the label is tracked as
    // state rather than read from the button's previous sibling.
    private fun parseContent(doc: Document): Pair<List<LinkEntry>, Map<Int, List<LinkEntry>>> {
        val content = doc.selectFirst("div.entry-content") ?: doc.body()
            ?: return Pair(emptyList(), emptyMap())
        val movieLinks = mutableListOf<LinkEntry>()
        val epLinks = sortedMapOf<Int, MutableList<LinkEntry>>()
        var currentEp: Int? = null
        var currentQuality: String? = null
        val epRe = Regex("(?i)^EP\\s*(\\d+)\\s*(?:ADDED)?$")
        val qualityRe = Regex("(?i)^(2160p|1080p|720p|480p|4K)\\b")
        for (p in content.select("p")) {
            val text = cleanTitle(p.text())
            if (text.isEmpty()) continue
            val epM = epRe.find(text)
            if (epM != null) {
                currentEp = epM.groupValues[1].toIntOrNull()
                currentQuality = null
                continue
            }
            if (qualityRe.containsMatchIn(text)) {
                currentQuality = text
                continue
            }
            val a = p.selectFirst("a[href*=gyanigurus.online/view/]") ?: continue
            val token = a.attr("href").substringAfter("/view/").substringBefore("/").substringBefore("?").trim()
            if (token.isEmpty()) continue
            val label = currentQuality ?: "Download"
            val entry = LinkEntry(label, "gyan", token, qualityValue(label))
            if (currentEp != null) {
                epLinks.getOrPut(currentEp) { mutableListOf() }.add(entry)
            } else {
                movieLinks.add(entry)
            }
        }
        return Pair(movieLinks, epLinks)
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("title")?.text()?.trim() ?: ""
        val title = displayTitle(rawTitle)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.entry-content img, article img, .post img")?.attr("src")
        val description = parseDescription(doc)
        val year = parseYear(rawTitle)
        val season = parseSeason(rawTitle)
        val (movieLinks, epLinks) = parseContent(doc)
        val isSeries = epLinks.isNotEmpty() || rawTitle.contains("Season", true)

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
            }
        } else {
            if (movieLinks.isEmpty()) return null
            val dataJson = toJson(EpisodeData(links = movieLinks)) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    // ---- Link resolvers ----

    // One gyanigurus "GD & DOWNLOAD" page: a link-protection page whose
    // hidden #content_for_display section lists the real file links as
    // plain <a class="hover_a link"> anchors — no JS execution and no
    // button click needed, they are in the HTML. Each button's page
    // lists up to four links for that quality's file:
    //   hubcloud.<tld>/drive/<code>  -> HubCloud servers (FSL/FSLv2/PixelDrain)
    //   hubdrive.blog/file/<id>      -> same HubCloud file via HubDrive
    //   gdflix.vip/file/<id>         -> GDFlix (stock loadExtractor)
    //   multicloudlinks.com/view/<c> -> second-level aggregator, skipped
    //   (HubCloud + HubDrive already cover the same file).
    data class GyanLinks(
        val hubCloud: String? = null,
        val hubDrive: String? = null,
        val gdflix: String? = null,
    )

    private suspend fun resolveGyan(token: String): GyanLinks {
        val html = try {
            app.get("https://gyanigurus.online/view/$token", headers = browserHeaders).text
        } catch (e: Exception) { return GyanLinks() }
        val doc = Jsoup.parse(html)
        var hubCloud: String? = null
        var hubDrive: String? = null
        var gdflix: String? = null
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (hubCloud == null) hubCloud = hubCloudCode(href)
            if (hubDrive == null) {
                Regex("hubdrive\\.blog/file/(\\d+)").find(href)?.groupValues?.getOrNull(1)?.let { hubDrive = it }
            }
            if (gdflix == null) {
                Regex("(?:gdflix\\.vip|only-apis\\.com)/file/([A-Za-z0-9]+)").find(href)?.groupValues?.getOrNull(1)?.let { gdflix = it }
            }
        }
        return GyanLinks(hubCloud, hubDrive, gdflix)
    }

    // hubdrive.blog/file/<id> -> HubDrive page with a hubcloud.<tld>/drive/<code>
    // link, resolved by the same HubCloud chain as HDHub4u.
    private suspend fun resolveHubDrive(fileId: String): List<HubServer> {
        val html = try {
            app.get("https://hubdrive.blog/file/$fileId", headers = browserHeaders).text
        } catch (e: Exception) { return emptyList() }
        val code = hubCloudCode(html) ?: return emptyList()
        return resolveHubCloudServers(code)
    }

    // One resolved HubCloud server: the actual server name (FSL, FSLv2,
    // PixelDrain, 10G) + its direct playable file URL. The gamerxyt page
    // lists one button per server; each becomes its own source in the app
    // so the user sees WHICH server a link is instead of "HubCloud" on
    // every row.
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
    // Buttons look like: <a href="..." ...>Download [FSL Server]</a>.
    // - FSL / FSLv2: the button href IS the R2 file URL (playable).
    //   R2 is NOT HEAD-checked: HEAD returns 403, but ranged GETs play.
    // - PixelServer: pixeldrain.dev/u/<id> -> api/file/<id>, HEAD-checked
    //   because some Pixeldrain links are DEAD (404).
    // - 10Gbps: pixel chain -> Google video file (see resolvePixel10G).
    // - Buzz Server (fuckingfast.net) is a download page, not a stream.
    // If no PixelDrain came from a button, the first working Pixeldrain
    // mirror from the page is added (the button's file is often dead
    // while another mirror is alive). FALLBACK: if no server at all
    // parsed, the old behaviour — first R2 URL as a single "HubCloud"
    // entry — so playback never breaks.
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
        // Pixeldrain is already covered above (as "PixelDrain").
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

        // Resolve every gyanigurus token CONCURRENTLY: each entry costs
        // a gyanigurus fetch plus a HubCloud chain (several HTTP hops),
        // and resolving them one after another (4K first, then 1080p)
        // regularly blows the app's 30s loadLinks timeout, so 1080p
        // never arrives. amap is CloudStream's own concurrent map (no
        // kotlinx import needed — the gradle plugin does not expose
        // kotlinx-coroutines to provider modules).
        data class ResolvedEntry(
            val servers: List<HubServer>,
            val gdflixId: String?,
        )

        val resolved: List<ResolvedEntry> = entries.amap { entry ->
            val gyan = resolveGyan(entry.id)
            // HubCloud and HubDrive point at the SAME HubCloud file, so
            // resolve HubCloud first and only fall back to HubDrive when
            // it yields nothing — resolving both would double the work
            // and emit duplicate source rows.
            val servers = run {
                val fromCloud = gyan.hubCloud?.let { resolveHubCloudServers(it) }.orEmpty()
                if (fromCloud.isNotEmpty()) fromCloud
                else gyan.hubDrive?.let { resolveHubDrive(it) }.orEmpty()
            }
            ResolvedEntry(servers, gyan.gdflix)
        }

        // Resolve every link entry; the source name keeps the full button
        // text (with size) so the user can pick the highest, e.g.
        // "HubCloud FSL 1080p [3 GB]". Callbacks are emitted sequentially
        // in page order, so the source list order is unchanged.
        entries.zip(resolved).forEach { (entry, res) ->
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
            // Best effort: GDFlix is Cloudflare-guarded for plain HTTP;
            // CloudStream's extractor handles it when it can.
            res.gdflixId?.let { id ->
                if (loadExtractor("https://gdflix.vip/file/$id", subtitleCallback, callback)) found = true
            }
        }

        return found
    }
}
