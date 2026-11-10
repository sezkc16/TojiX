package com.movies4u

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

class Movies4uProvider : MainAPI() {

    override var mainUrl = "https://movies4u.td"
    override var name = "Movies4u"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Episode data passed to loadLinks (as JSON string).
    // A LIST of links (not a map) so every quality variant shows
    // separately with its size, e.g. "720p [600MB/E] HubCloud" and
    // "1080p [1.5GB/E] HubCloud" are two different entries.
    data class LinkEntry(
        @JsonProperty("label") val label: String,  // e.g. "720p [600MB/E] HubCloud" or full quality heading
        @JsonProperty("host") val host: String,    // "mdisk" | "hubcloud" | "gdflix" | "gdirect" | "filepress" | "vcloud" | "vdrive"
        @JsonProperty("id") val id: String,        // mdisk id / hub code / gdflix id / fastdl id / full URL (filepress/vcloud/vdrive)
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

    private fun toJson(data: EpisodeData): String? {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
        } catch (e: Exception) { null }
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("\\s*-\\s*Movies4u.*$"), "")
            .replace(Regex("\\s*\\|\\s*Movies4u.*$"), "")
        // Cut the trailing quality suffix, e.g. " 480p | 720p | 1080p"
        val idx = Regex("(?i)\\s+(?:480p|720p|1080p|2160p|4K)\\b").find(t)?.range?.first
        if (idx != null) t = t.substring(0, idx).trim()
        return t
    }

    private fun isSeriesTitle(title: String): Boolean {
        return title.contains("Season", true)
            || title.contains("TV-Show", true)
            || title.contains("TV Show", true)
            || title.contains("Web Series", true)
            || title.contains("K-Drama", true)
    }

    // Parse a listing page (home / category / search) into search responses.
    // Posts are <article class="entry-card"> with an <img class="wp-post-image">
    // poster and <h2 class="entry-title"><a href="...">Title</a></h2>.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (art in doc.select("article.entry-card")) {
            val a = art.selectFirst("h2.entry-title a") ?: continue
            val href = a.attr("href")
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            val img = art.selectFirst("img.wp-post-image")
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            val rawTitle = a.text().trim()
            if (rawTitle.isEmpty()) continue
            val title = cleanTitle(rawTitle)
            if (title.isEmpty()) continue
            if (isSeriesTitle(rawTitle)) {
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        "" to "Latest",
        "bollywood" to "Bollywood",
        "hollywood" to "Hollywood",
        "dual-audio" to "Dual Audio",
        "south-indian" to "South Indian",
        "web-series" to "Web Series",
        "tv-show" to "TV Shows",
        "korean" to "K-Drama",
        "hindi" to "Hindi",
        "english" to "English",
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
        val q = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("title")?.text()?.trim() ?: ""
        val title = cleanTitle(rawTitle)
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.entry-content img")?.attr("src")
        val isSeries = isSeriesTitle(rawTitle)

        val content = doc.selectFirst("div.entry-content") ?: doc.body() ?: return null

        if (isSeries) {
            // Walk headings in document order. "Season N Single Episodes
            // <quality> [size/E]" headings each have one mdisk link.
            // Each mdisk page lists per-episode blocks "-:Episodes: N:-"
            // with a mix of hosts (HubCloud / GDFlix / G-Direct /
            // Filepress / V-Cloud / V-Drive). Merge by (season, episode)
            // so every episode shows once with all quality variants.
            val epMap = linkedMapOf<Pair<Int, Int>, MutableList<LinkEntry>>()
            var currentHeading = ""
            var currentSeason = 1
            for (el in content.allElements) {
                val tag = el.tagName()
                if (tag.matches(Regex("h[1-6]"))) {
                    currentHeading = el.text().trim()
                    val sm = Regex("(?i)Season\\s*(\\d+)").find(currentHeading)
                    if (sm != null) currentSeason = sm.groupValues[1].toIntOrNull() ?: currentSeason
                    continue
                }
                if (tag != "a") continue
                val href = el.attr("href")
                if (!href.contains("mdrive.cloud/mdisk/")) continue
                val mdiskId = href.substringAfter("mdrive.cloud/mdisk/").substringBefore("/").substringBefore("?").trim()
                if (mdiskId.isEmpty()) continue
                val mdHtml = try {
                    app.get("https://mdrive.cloud/mdisk/$mdiskId", headers = browserHeaders).text
                } catch (e: Exception) { continue }
                val quality = qualityValue(currentHeading)
                val sizeTag = Regex("\\[[^\\]]+\\]").find(currentHeading)?.value ?: ""
                val epSplit = Regex("(?i)-:Episodes:\\s*(\\d+):-").findAll(mdHtml).toList()
                if (epSplit.isEmpty()) {
                    // No per-episode blocks: treat the whole mdisk page as
                    // one episode (batch / pack).
                    val links = collectHostLinks(mdHtml, "$currentHeading", quality)
                    if (links.isNotEmpty()) {
                        epMap.getOrPut(currentSeason to 0) { mutableListOf() }.addAll(links)
                    }
                    continue
                }
                for ((idx, m) in epSplit.withIndex()) {
                    val epNum = m.groupValues[1].toIntOrNull() ?: (idx + 1)
                    val segStart = m.range.last
                    val segEnd = if (idx + 1 < epSplit.size) epSplit[idx + 1].range.first else mdHtml.length
                    val seg = mdHtml.substring(segStart, segEnd)
                    val labelPrefix = "${currentHeading.replace(Regex("(?i)Season\\s*\\d+\\s*Single Episodes\\s*"), "").trim()} ".trim()
                    val links = collectHostLinks(seg, labelPrefix, quality)
                    if (links.isNotEmpty()) {
                        epMap.getOrPut(currentSeason to epNum) { mutableListOf() }.addAll(links)
                    }
                }
            }
            val episodes = mutableListOf<Episode>()
            for ((key, links) in epMap) {
                val (season, epNum) = key
                if (links.isEmpty()) continue
                val dataJson = toJson(EpisodeData(links = links)) ?: continue
                if (epNum == 0) {
                    episodes.add(newEpisode(dataJson) {
                        this.name = "Season Pack"
                        this.season = season
                    })
                } else {
                    episodes.add(newEpisode(dataJson) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = season
                    })
                }
            }
            if (episodes.isEmpty()) return null
            episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        } else {
            // Movie: each quality is an h5 heading (full filename + size)
            // followed by one "Download Now" mdisk link. Keep the full
            // heading text so the user can pick the highest quality.
            val links = mutableListOf<LinkEntry>()
            val seenIds = mutableSetOf<String>()
            var currentHeading = ""
            for (el in content.allElements) {
                val tag = el.tagName()
                if (tag.matches(Regex("h[1-6]"))) {
                    currentHeading = el.text().trim()
                    continue
                }
                if (tag != "a") continue
                val href = el.attr("href")
                if (!href.contains("mdrive.cloud/mdisk/")) continue
                val mdiskId = href.substringAfter("mdrive.cloud/mdisk/").substringBefore("/").substringBefore("?").trim()
                if (mdiskId.isEmpty() || !seenIds.add(mdiskId)) continue
                val label = currentHeading.ifEmpty { "Download" }
                links.add(LinkEntry(label, "mdisk", mdiskId, qualityValue(label)))
            }
            if (links.isEmpty()) return null
            val dataJson = toJson(EpisodeData(links = links)) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
            }
        }
    }

    // ---- Host collection on an mdisk (NEX DRIVE) page segment ----
    // Hosts per segment: HubCloud, GDFlix, G-Direct (fastdl), Filepress,
    // V-Cloud, V-Drive. Returns one LinkEntry per host occurrence.
    private fun collectHostLinks(html: String, labelPrefix: String, quality: Int): List<LinkEntry> {
        val out = mutableListOf<LinkEntry>()
        val seen = mutableSetOf<String>()
        for (m in Regex("https?://hubcloud\\.(?:ist|pw)/drive/([A-Za-z0-9]+)").findAll(html)) {
            val code = m.groupValues[1]
            if (seen.add("hub:$code")) out.add(LinkEntry("$labelPrefix HubCloud".trim(), "hubcloud", code, quality))
        }
        for (m in Regex("https?://gdflix\\.vip/file/([A-Za-z0-9]+)").findAll(html)) {
            val id = m.groupValues[1]
            if (seen.add("gd:$id")) out.add(LinkEntry("$labelPrefix GDFlix".trim(), "gdflix", id, quality))
        }
        for (m in Regex("https?://fastdl\\.beer/embed\\.php\\?download=([A-Za-z0-9]+)").findAll(html)) {
            val id = m.groupValues[1]
            if (seen.add("fast:$id")) out.add(LinkEntry("$labelPrefix G-Direct".trim(), "gdirect", id, quality))
        }
        for (m in Regex("https?://filebee\\.(?:live|top)/file/([A-Za-z0-9]+)").findAll(html)) {
            val u = m.value
            if (seen.add("fp:$u")) out.add(LinkEntry("$labelPrefix FilePress".trim(), "filepress", u, quality))
        }
        for (m in Regex("https?://vcloud\\.beer/([A-Za-z0-9]+)").findAll(html)) {
            val u = m.value
            if (seen.add("vc:$u")) out.add(LinkEntry("$labelPrefix V-Cloud".trim(), "vcloud", u, quality))
        }
        for (m in Regex("https?://one\\.vegadrive\\.app/s/([A-Za-z0-9]+)").findAll(html)) {
            val u = m.value
            if (seen.add("vd:$u")) out.add(LinkEntry("$labelPrefix V-Drive".trim(), "vdrive", u, quality))
        }
        return out
    }

    // ---- Link resolvers ----

    // G-Direct: fastdl.beer/embed.php?download=<id> serves a tiny page with
    // var reurl = "https://fastdl.beer/dl.php?link=<googleusercontent URL>".
    // The Google URL IS the direct video file (verified HEAD 200 video/mkv).
    private suspend fun resolveFastDl(embedId: String): String? {
        val html = try {
            app.get(
                "https://fastdl.beer/embed.php?download=$embedId",
                headers = browserHeaders + mapOf("Referer" to "https://mdrive.cloud/")
            ).text
        } catch (e: Exception) { return null }
        val reurl = Regex("reurl\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1) ?: return null
        val link = reurl.substringAfter("link=", "").trim()
        return link.takeIf { it.startsWith("http") }
    }

    // Resolve a HubCloud file id all the way to the direct video file.
    // Chain: hubcloud.ist/drive/<code> -> gamerxyt.php link (in the page)
    // -> direct file. The gamerxyt page gives EITHER a Cloudflare R2 .mkv
    // URL or Pixeldrain links (page URL, NOT playable) -> convert those to
    // the Pixeldrain direct API URL (pixeldrain.dev/api/file/<id>), which
    // IS a playable video file. NOTE: the page lists several Pixeldrain
    // links and some are DEAD (404), so validate each with a HEAD request
    // and return the first that actually exists.
    private suspend fun resolveHubCloudDirect(hubCode: String): String? {
        val hubUrl = "https://hubcloud.ist/drive/$hubCode"
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

        ep.links?.forEach { entry ->
            when (entry.host) {
                "mdisk" -> {
                    // Movie quality: fetch the mdisk page, then resolve
                    // every host on it (G-Direct / V-Cloud / V-Drive /
                    // Filepress).
                    val mdHtml = try {
                        app.get("https://mdrive.cloud/mdisk/${entry.id}", headers = browserHeaders).text
                    } catch (e: Exception) { return@forEach }
                    val hostLinks = collectHostLinks(mdHtml, entry.label, entry.quality)
                    for (hl in hostLinks) {
                        if (resolveEntry(hl, subtitleCallback, callback)) found = true
                    }
                }
                else -> {
                    if (resolveEntry(entry, subtitleCallback, callback)) found = true
                }
            }
        }

        return found
    }

    // Resolve one host entry to playable link(s). Returns true if at
    // least one link was emitted.
    private suspend fun resolveEntry(
        entry: LinkEntry,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when (entry.host) {
            "gdirect" -> {
                val direct = resolveFastDl(entry.id) ?: return false
                callback(
                    newExtractorLink(
                        source = "G-Direct",
                        name = "G-Direct ${entry.label}",
                        url = direct,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = entry.quality
                        this.referer = "https://fastdl.beer/"
                    }
                )
                true
            }
            "hubcloud" -> {
                val direct = resolveHubCloudDirect(entry.id) ?: return false
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
                true
            }
            "gdflix" -> {
                loadExtractor("https://gdflix.vip/file/${entry.id}", subtitleCallback, callback)
            }
            "filepress", "vcloud", "vdrive" -> {
                loadExtractor(entry.id, subtitleCallback, callback)
            }
            else -> false
        }
    }
}
