package com.moviesdrive

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
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class MoviesDriveProvider : MainAPI() {

    override var mainUrl = "https://new2.moviesdrive.cyou"
    override var name = "MoviesDrive"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Episode data passed to loadLinks (as JSON string).
    // A LIST of links (not a map) so every quality variant shows
    // separately with its size, e.g. "480p [724.21 MB]" and
    // "HQ 1080p [11.95 GB]" are two different entries.
    // host: "archive" = mdrive.lol/archive/<id> page (fetch it in
    //   loadLinks to find its HubCloud/GDFlix buttons),
    //   "hubcloud" = hubcloud drive code, "gdflix" = gdflix file id.
    data class LinkEntry(
        @JsonProperty("label") val label: String,
        @JsonProperty("host") val host: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("q") val quality: Int,
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

    private fun toJson(data: EpisodeData): String? {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
        } catch (e: Exception) { null }
    }

    private fun cleanText(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    // Display title: cut at the first quality token (480p/720p/1080p/
    // 2160p), strip bracket groups, collapse whitespace.
    // "Jailer 2 (2026) HQ-HDRip [Hindi ...] 480p | 720p | ..." ->
    // "Jailer 2 (2026) HQ-HDRip"
    private fun displayTitle(raw: String): String {
        var t = raw
            .replace(Regex(" – MoviesDrive.*$"), "")
            .replace(Regex(" \\| MoviesDrive.*$"), "")
        val cut = Regex("\\b(480p|720p|1080p|2160p)\\b").find(t)
        if (cut != null) t = t.substring(0, cut.range.first)
        t = t.replace(Regex("\\s*\\[.*?\\]\\s*"), " ")
        return cleanText(t)
    }

    // Listing: div.movies-grid > a > div.poster-card > img (TMDB) +
    // p.poster-title (full title with quality info).
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("div.movies-grid a[href]")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            // Skip non-post links (category/tag pages inside the grid)
            if (url.contains("/category/") || url.contains("/tag/") || url.contains("/page/")) continue
            if (!seen.add(url)) continue
            val img = a.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            var rawTitle = a.selectFirst("p.poster-title")?.text()?.trim() ?: ""
            if (rawTitle.isEmpty()) rawTitle = a.attr("title").trim()
            if (rawTitle.isEmpty()) rawTitle = cleanText(a.text())
            if (rawTitle.isEmpty()) continue
            val title = displayTitle(rawTitle)
            if (title.isEmpty()) continue
            val isSeries = title.contains("Season", true) || url.contains("-season-")
            if (isSeries) {
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        "" to "Latest",
        "netflix" to "Netflix",
        "amzn-prime-video" to "Prime Video",
        "hollywood" to "Hollywood",
        "bollywood" to "Bollywood",
        "south" to "South",
        "hindi-dubbed" to "Hindi Dubbed",
        "dual-audio" to "Dual Audio",
        "k-drama" to "K-Drama",
        "web" to "Web Series",
        "2160p-4k" to "4K",
        "anime" to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data
        val url = when {
            cat.isEmpty() -> if (page <= 1) mainUrl else "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/category/$cat/"
            else -> "$mainUrl/category/$cat/page/$page/"
        }
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(html)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // WordPress search
        val q = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("title")?.text()?.trim() ?: ""
        val title = displayTitle(rawTitle)
        if (title.isEmpty()) return null
        // No og:image on this site; poster is the TMDB image in the post.
        val poster = doc.select("img[src*=image.tmdb.org]").firstOrNull()?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val isSeries = title.contains("Season", true) || url.contains("-season-")

        if (isSeries) {
            return loadSeries(title, url, doc, poster)
        } else {
            // Movie: one mdrive.lol/archive link per quality button, e.g.
            // "480p [724.21 MB]" -> https://mdrive.lol/archive/25337/
            val links = mutableListOf<LinkEntry>()
            val seen = mutableSetOf<String>()
            for (a in doc.select("a[href*=mdrive.lol/archive/]")) {
                val href = a.attr("href")
                val txt = cleanText(a.text())
                if (txt.isEmpty()) continue
                if (!Regex("(?i)(480p|720p|1080p|2160p|4K)").containsMatchIn(txt)) continue
                if (!seen.add(href)) continue
                links.add(LinkEntry(txt, "archive", href, qualityValue(txt)))
            }
            if (links.isEmpty()) return null
            val dataJson = toJson(EpisodeData(links = links)) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
            }
        }
    }

    // Series detail page layout (flat sequence of h5 elements):
    //   <h5>Dead-End Job (Season 1) WEB-DL [Hindi – English] 480p x264 [130MB/E]</h5>
    //   <h5><a href="https://mdrive.lol/archive/24804/">480p Single Episode</a></h5>
    //   <h5><a href="https://mdrive.lol/archive/24805/">Zip [1.2GB]</a></h5>
    //   ... same for 720p, 1080p, 4K ...
    // Each "Single Episode" mdrive page lists per-episode HubCloud +
    // GDFlix buttons under "EP01 – 480p [175.39 MB]" style headings.
    // Episodes are merged by episode number across qualities, so each
    // episode shows ALL quality variants (HDHub4u v3 pattern).
    private suspend fun loadSeries(title: String, url: String, doc: org.jsoup.nodes.Document, poster: String?): LoadResponse? {
        val byEpisode = mutableMapOf<Int, MutableList<LinkEntry>>()
        val packs = mutableListOf<LinkEntry>()
        var currentQuality = ""
        for (h in doc.select("h5")) {
            val link = h.selectFirst("a[href*=mdrive.lol/archive/]")
            val text = cleanText(h.text())
            if (link != null) {
                val href = link.attr("href")
                val linkText = cleanText(link.text())
                when {
                    linkText.contains("Single Episode", true) -> {
                        // Fetch the per-episode mdrive page and merge
                        val qLabel = Regex("(?i)(\\d{3,4}p[^\\[]*\\[[^\\]]+\\])")
                            .find(currentQuality)?.groupValues?.getOrNull(1) ?: currentQuality
                        fetchSingleEpisodePage(href, qLabel, byEpisode)
                    }
                    linkText.contains("Zip", true) -> {
                        val q = Regex("(?i)(\\d{3,4}p)").find(currentQuality)?.groupValues?.getOrNull(1) ?: ""
                        val label = if (q.isNotEmpty()) "$q Zip $linkText".replace("Zip Zip", "Zip") else "Season Pack $linkText"
                        packs.add(LinkEntry(label, "archive", href, qualityValue(currentQuality)))
                    }
                }
            } else if (Regex("(?i)\\d{3,4}p").containsMatchIn(text) &&
                Regex("(?i)(MB|GB)/E").containsMatchIn(text)) {
                // Quality section heading
                currentQuality = text
            }
        }
        val episodes = mutableListOf<Episode>()
        for ((epNum, links) in byEpisode.toSortedMap()) {
            if (links.isEmpty()) continue
            val dataJson = toJson(EpisodeData(links = links)) ?: continue
            episodes.add(newEpisode(dataJson) {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.posterUrl = poster
            })
        }
        for (pack in packs) {
            val dataJson = toJson(EpisodeData(links = listOf(pack))) ?: continue
            episodes.add(newEpisode(dataJson) {
                this.name = "Season Pack (${pack.label})"
                this.posterUrl = poster
            })
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    // Fetch one "Single Episode" mdrive.lol archive page and merge its
    // per-episode HubCloud/GDFlix buttons into byEpisode.
    // Page layout: <h5>EP01 – 480p [175.39 MB]</h5>
    //   <h5><a href="https://hubcloud.pw/drive/<code>">HubCloud</a></h5>
    //   <h5><a href="https://gdflix.vip/file/<id>">GDFliX</a></h5>
    private suspend fun fetchSingleEpisodePage(
        archiveUrl: String,
        qualityLabel: String,
        byEpisode: MutableMap<Int, MutableList<LinkEntry>>
    ) {
        val html = try {
            app.get(archiveUrl, headers = browserHeaders).text
        } catch (e: Exception) { return }
        val doc = Jsoup.parse(html)
        var currentEp: Int? = null
        var currentEpLabel = qualityLabel
        for (h in doc.select("h5")) {
            val text = cleanText(h.text())
            val epMatch = Regex("(?i)^(?:EP|Episode)\\s*0*(\\d+)\\s*[–\\-]\\s*(.*)$").find(text)
            if (epMatch != null) {
                currentEp = epMatch.groupValues[1].toIntOrNull()
                val rest = epMatch.groupValues[2].trim()
                currentEpLabel = if (rest.isNotEmpty()) rest else qualityLabel
                continue
            }
            val a = h.selectFirst("a[href]") ?: continue
            val href = a.attr("href")
            val ep = currentEp ?: continue
            when {
                href.contains("hubcloud.") && href.contains("/drive/") -> {
                    val code = href.substringAfter("/drive/").substringBefore("/").substringBefore("?").trim()
                    if (code.isNotEmpty()) {
                        byEpisode.getOrPut(ep) { mutableListOf() }.add(
                            LinkEntry("$currentEpLabel HubCloud", "hubcloud", code, qualityValue(currentEpLabel))
                        )
                    }
                }
                href.contains("gdflix.vip/file/") || href.contains("only-apis.com/file/") -> {
                    val id = href.substringAfter("/file/").substringBefore("/").substringBefore("?").trim()
                    if (id.isNotEmpty()) {
                        byEpisode.getOrPut(ep) { mutableListOf() }.add(
                            LinkEntry("$currentEpLabel GDFlix", "gdflix", id, qualityValue(currentEpLabel))
                        )
                    }
                }
            }
        }
    }

    // ---- Link resolvers ----

    // mdrive.lol/archive/<id> page: one HubCloud button + one GDFlix
    // button (image buttons; match by href domain, not by text).
    private suspend fun fetchArchiveLinks(archiveUrl: String): List<LinkEntry> {
        val html = try {
            app.get(archiveUrl, headers = browserHeaders).text
        } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<LinkEntry>()
        Regex("hubcloud\\.(?:ist|pw)/drive/([A-Za-z0-9]+)")
            .findAll(html).map { it.groupValues[1] }.distinct().forEach { code ->
                out.add(LinkEntry("HubCloud", "hubcloud", code, Qualities.Unknown.value))
            }
        Regex("(?:gdflix\\.vip|only-apis\\.com)/file/([A-Za-z0-9]+)")
            .findAll(html).map { it.groupValues[1] }.distinct().forEach { id ->
                out.add(LinkEntry("GDFlix", "gdflix", id, Qualities.Unknown.value))
            }
        return out
    }

    // Resolve a HubCloud file id all the way to the direct video file.
    // Chain: hubcloud.ist/drive/<code> -> gamerxyt.php link (in the page)
    // -> direct file. The gamerxyt page gives EITHER a Cloudflare R2 .mkv
    // URL or Pixeldrain links (page URL, NOT playable) -> convert those to
    // the Pixeldrain direct API URL (pixeldrain.dev/api/file/<id>), which
    // IS a playable video file. NOTE: the page lists several Pixeldrain
    // links and some are DEAD (404), so validate each with a HEAD request
    // and return the first that actually exists.
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
        val seen = mutableSetOf<String>()
        fun add(server: String, url: String) {
            val u = url.trim()
            if (u.isNotEmpty() && seen.add(u)) out.add(HubServer(server, u))
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

    private suspend fun emitHubCloud(
        code: String,
        label: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // One source per HubCloud server (FSL, FSLv2, PixelDrain, 10G)
        // so the app shows WHICH server a link is. The server name is a
        // plain word in the link name so the app's server chip can match it.
        val servers = resolveHubCloudServers(code)
        if (servers.isEmpty()) return false
        for (server in servers) {
            val name = if (server.server == "HubCloud") "HubCloud $label"
                else "HubCloud ${server.server} $label"
            callback(
                newExtractorLink(
                    source = "HubCloud",
                    name = name,
                    url = server.url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = "https://hubcloud.pw/"
                }
            )
        }
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        var found = false

        ep.links?.forEach { entry ->
            when (entry.host) {
                "archive" -> {
                    // Fetch the mdrive archive page; it has one HubCloud
                    // + one GDFlix button. Keep the entry's own label
                    // (quality + size) on the emitted links.
                    for (sub in fetchArchiveLinks(entry.id)) {
                        when (sub.host) {
                            "hubcloud" -> if (emitHubCloud(sub.id, entry.label, entry.quality, callback)) found = true
                            "gdflix" -> {
                                if (loadExtractor("https://gdflix.vip/file/${sub.id}", subtitleCallback, callback)) found = true
                            }
                        }
                    }
                }
                "hubcloud" -> {
                    if (emitHubCloud(entry.id, entry.label, entry.quality, callback)) found = true
                }
                "gdflix" -> {
                    // Best effort: GDFlix is Cloudflare-guarded for plain
                    // HTTP; CloudStream's extractor handles it when it can.
                    if (loadExtractor("https://gdflix.vip/file/${entry.id}", subtitleCallback, callback)) found = true
                }
            }
        }

        return found
    }
}
