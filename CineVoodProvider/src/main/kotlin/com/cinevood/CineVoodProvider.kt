package com.cinevood

import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class CineVoodProvider : MainAPI() {

    override var mainUrl = "https://new1.cinevood.plus"
    override var name = "CineVood"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    // Full browser-like headers: the site sits behind Cloudflare, and
    // plain requests get the "Just a moment..." challenge on detail
    // pages. These headers get the homepage through reliably; detail
    // pages are intermittent in CloudStream — the first test after
    // install must be whether a movie page loads.
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
    )

    // Episode data passed to loadLinks (as JSON string).
    // A LIST of links (not a map) so every quality variant shows
    // separately with its size, e.g. "1080p [6.7GB] HubCloud" and
    // "1080p [3.39GB] HubCloud" are two different entries.
    data class LinkEntry(
        @JsonProperty("label") val label: String,  // e.g. "1080p [6.7GB] HubCloud"
        @JsonProperty("host") val host: String,    // "hubcloud" | "filepress" | "gdflix" | "vidara"
        @JsonProperty("id") val id: String,        // file id / drive code / full url for vidara
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

    private fun qualityLabel(heading: String): String {
        return when {
            heading.contains("2160") || heading.contains("4K", true) -> "2160p"
            heading.contains("1080") -> "1080p"
            heading.contains("720") -> "720p"
            heading.contains("480") -> "480p"
            else -> "HD"
        }
    }

    private fun toJson(data: EpisodeData): String? {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
        } catch (e: Exception) { null }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    // Parse a listing page (home / category / search) into search responses.
    // Posts are <article class="latestPost excerpt"> with an <a> carrying
    // the title attribute and a TMDB poster <img> inside.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.latestPost")) {
            val a = article.selectFirst("a[href]") ?: continue
            val href = a.attr("href")
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            if (url == mainUrl || url == "$mainUrl/") continue
            val img = a.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            var title = a.attr("title").trim()
            if (title.isEmpty()) title = a.text().trim()
            if (title.isEmpty()) continue
            val display = title
                .replace(Regex("\\s*CineVood\\s*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ").trim()
            val isSeries = url.contains("/web-series/") || url.contains("/tv-shows/")
                || title.contains("Season", true)
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

    override val mainPage = mainPageOf(
        "" to "Latest",
        "bollywood" to "Bollywood",
        "hollywood" to "Hollywood",
        "hindi-dubbed/hollywood-dubbed" to "Hollywood Dubbed",
        "hindi-dubbed/south-dubbed" to "South Dubbed",
        "web-series" to "Web Series",
        "tv-shows" to "TV Shows",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // WordPress search
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("title")?.text()?.trim() ?: ""
        val title = cleanTitle(rawTitle)
            .replace(Regex(" – CineVood.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex(" \\| CineVood.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*CineVood\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("article img, .post img, img[src*=tmdb]")?.attr("src")

        // Downloads: each quality is an <h6> heading with the filename
        // and size (e.g. "...1080p X264 ... – 6.7 GB"), followed by a row
        // of three host links: HubCloud, FilePress, gdflix. Walk the h6
        // headings and collect the links until the next h6.
        val links = mutableListOf<LinkEntry>()
        val seenIds = mutableSetOf<String>()
        for (h6 in doc.select("h6")) {
            val heading = cleanTitle(h6.text())
            if (heading.isEmpty()) continue
            val isWatch = heading.contains("Watch Online", true)
            val q = qualityValue(heading)
            if (!isWatch && q == Qualities.Unknown.value) continue
            // Size from the heading, e.g. "6.7 GB" / "889.21 MB"
            val size = Regex("(\\d+(?:\\.\\d+)?\\s*(?:GB|MB))", RegexOption.IGNORE_CASE)
                .find(heading)?.groupValues?.getOrNull(1)?.replace(" ", "") ?: ""
            val qLabel = qualityLabel(heading)
            val sizePart = if (size.isNotEmpty()) " [$size]" else ""
            var sib = h6.nextElementSibling()
            while (sib != null && sib.tagName() != "h6") {
                val anchors = mutableListOf<org.jsoup.nodes.Element>()
                if (sib.tagName() == "a") anchors.add(sib)
                anchors.addAll(sib.select("a[href]"))
                for (a in anchors) {
                    val href = a.attr("href").trim()
                    if (href.isEmpty()) continue
                    when {
                        href.contains("hubcloud.pw/drive/") -> {
                            val id = href.substringAfter("hubcloud.pw/drive/").substringBefore("/").substringBefore("?").trim()
                            if (id.isNotEmpty() && seenIds.add("hub:$id")) {
                                val label = if (isWatch) "Watch Online HubCloud" else "$qLabel$sizePart HubCloud"
                                links.add(LinkEntry(label, "hubcloud", id, q))
                            }
                        }
                        href.contains("filepress") && href.contains("/file/") -> {
                            val id = href.substringAfter("/file/").substringBefore("/").substringBefore("?").trim()
                            if (id.isNotEmpty() && seenIds.add("fp:$id")) {
                                links.add(LinkEntry("$qLabel$sizePart FilePress", "filepress", href, q))
                            }
                        }
                        href.contains("gdflix") && href.contains("/file/") -> {
                            val id = href.substringAfter("/file/").substringBefore("/").substringBefore("?").trim()
                            if (id.isNotEmpty() && seenIds.add("gd:$id")) {
                                links.add(LinkEntry("$qLabel$sizePart GDFlix", "gdflix", href, q))
                            }
                        }
                        isWatch && href.contains("vidara") -> {
                            if (seenIds.add("vid:$href")) {
                                links.add(LinkEntry("Watch Online Vidara", "vidara", href, Qualities.Unknown.value))
                            }
                        }
                    }
                }
                sib = sib.nextElementSibling()
            }
        }
        if (links.isEmpty()) return null
        val dataJson = toJson(EpisodeData(links = links)) ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
            this.posterUrl = poster
        }
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

        // Resolve every link entry. HubCloud goes through the direct
        // chain (R2 / Pixeldrain). FilePress, GDFlix and Vidara go
        // through CloudStream's extractor (best effort — those hosts sit
        // behind Cloudflare and may not resolve).
        ep.links?.forEach { entry ->
            when (entry.host) {
                "hubcloud" -> {
                    // One source per HubCloud server (FSL, FSLv2, PixelDrain,
                    // 10G) so the app shows WHICH server a link is.
                    val servers = resolveHubCloudServers(entry.id)
                    if (servers.isEmpty()) return@forEach
                    for (server in servers) {
                        callback(
                            newExtractorLink(
                                source = "HubCloud",
                                name = "HubCloud ${server.server} ${entry.label}",
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
                "filepress", "gdflix", "vidara" -> {
                    if (loadExtractor(entry.id, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        return found
    }
}
