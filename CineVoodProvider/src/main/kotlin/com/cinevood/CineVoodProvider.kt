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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class CineVoodProvider : MainAPI() {

    override var mainUrl = "https://new2.cinevood.company"
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

    // Resolve a HubCloud drive code all the way to the direct video file.
    // Chain: hubcloud.pw/drive/<code> -> gamerxyt.php link (in the page)
    // -> direct file. The gamerxyt page gives EITHER a Cloudflare R2 .mkv
    // URL or Pixeldrain links (page URL, NOT playable) -> convert those to
    // the Pixeldrain direct API URL (pixeldrain.dev/api/file/<id>), which
    // IS a playable video file. NOTE: the page lists several Pixeldrain
    // links and some are DEAD (404), so validate each with a HEAD request
    // and return the first that actually exists.
    private suspend fun resolveHubCloudDirect(hubCode: String): String? {
        val hubUrl = "https://hubcloud.pw/drive/$hubCode"
        val hubHtml = try { app.get(hubUrl, headers = browserHeaders).text } catch (e: Exception) { return null }
        val gamerUrl = Regex("https?://gamerxyt\\.com/hubcloud\\.php\\?host=hubcloud&id=[A-Za-z0-9]+&token=[A-Za-z0-9]+")
            .find(hubHtml)?.value ?: return null
        val gamerHtml = try { app.get(gamerUrl, headers = browserHeaders).text } catch (e: Exception) { return null }
        // 1) Direct file: https://<hash>.r2.cloudflarestorage.com/hub/<hash>?X-Amz-...
        Regex("https?://[^\"' ]+\\.r2\\.cloudflarestorage\\.com/[^\"' ]+")
            .find(gamerHtml)?.value?.let { return it }
        // 2) Pixeldrain page links -> direct API file URLs; try each until
        // one actually exists (HEAD 200), because some are dead (404).
        val pdIds = Regex("https?://pixeldrain\\.dev/u/([A-Za-z0-9]+)")
            .findAll(gamerHtml).map { it.groupValues[1] }.distinct().toList()
        for (id in pdIds) {
            val apiUrl = "https://pixeldrain.dev/api/file/$id"
            val ok = try {
                app.head(apiUrl, headers = browserHeaders).code == 200
            } catch (e: Exception) { false }
            if (ok) return apiUrl
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
        var found = false

        // Resolve every link entry. HubCloud goes through the direct
        // chain (R2 / Pixeldrain). FilePress, GDFlix and Vidara go
        // through CloudStream's extractor (best effort — those hosts sit
        // behind Cloudflare and may not resolve).
        ep.links?.forEach { entry ->
            when (entry.host) {
                "hubcloud" -> {
                    val direct = resolveHubCloudDirect(entry.id) ?: return@forEach
                    callback(
                        newExtractorLink(
                            source = "HubCloud",
                            name = "HubCloud ${entry.label}",
                            url = direct,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = entry.quality
                            this.referer = "https://hubcloud.pw/"
                        }
                    )
                    found = true
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
