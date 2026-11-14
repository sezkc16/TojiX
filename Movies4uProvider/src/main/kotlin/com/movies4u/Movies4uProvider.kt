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
import com.lagradost.cloudstream3.amap
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

    override var mainUrl = "https://movies4u.et"
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
                // The mdisk host moved from mdrive.cloud to mdrive.sbs
                // (2026-11): match any mdrive.<tld> and fetch the link
                // as given, so a future domain move does not break load().
                val mdiskUrl = Regex("https?://mdrive\\.[a-z]+/mdisk/[A-Za-z0-9]+")
                    .find(href)?.value?.substringBefore("?")?.trim() ?: continue
                val mdHtml = try {
                    app.get(mdiskUrl, headers = browserHeaders).text
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
                        this.posterUrl = poster
                    })
                } else {
                    episodes.add(newEpisode(dataJson) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = season
                        this.posterUrl = poster
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
                // The mdisk host moved from mdrive.cloud to mdrive.sbs
                // (2026-11): match any mdrive.<tld>. The id stores the
                // full mdisk URL (LinkEntry.id already holds full URLs
                // for filepress/vcloud/vdrive), so loadLinks fetches the
                // host the page actually linked to.
                val mdiskUrl = Regex("https?://mdrive\\.[a-z]+/mdisk/[A-Za-z0-9]+")
                    .find(href)?.value?.substringBefore("?")?.trim() ?: continue
                if (!seenIds.add(mdiskUrl)) continue
                val label = currentHeading.ifEmpty { "Download" }
                links.add(LinkEntry(label, "mdisk", mdiskUrl, qualityValue(label)))
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
    // V-Cloud, V-Drive, plus the mirror hosts (GoFile, MegaUp, 1Fichier,
    // VikingFile) which the built-in loadExtractor handles. Returns one
    // LinkEntry per host occurrence.
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
        // Mirror hosts (same set GokuHD collects from nexdrive pages):
        // the built-in loadExtractor resolves the ones it knows
        // (GoFile etc.) and returns false fast for the rest, so a
        // variant with no G-Direct — e.g. Vibe 1080p HQ — can still
        // get a row from a mirror instead of nothing.
        for (m in Regex("https?://(?:1fichier\\.com/\\?[A-Za-z0-9]+|gofile\\.io/d/[A-Za-z0-9]+|megaup\\.net/[A-Za-z0-9]+/[^\"'\\s]+|vikingfile\\.com/f/[A-Za-z0-9]+)").findAll(html)) {
            val u = m.value
            val hostLabel = when {
                u.contains("1fichier") -> "1Fichier"
                u.contains("gofile") -> "GoFile"
                u.contains("megaup") -> "MegaUp"
                u.contains("vikingfile") -> "VikingFile"
                else -> "Mirror"
            }
            if (seen.add("ex:$u")) out.add(LinkEntry("$labelPrefix $hostLabel".trim(), "extractor", u, quality))
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
                headers = browserHeaders + mapOf("Referer" to "https://mdrive.sbs/")
            ).text
        } catch (e: Exception) { return null }
        val reurl = Regex("reurl\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1) ?: return null
        val link = reurl.substringAfter("link=", "").trim()
        return link.takeIf { it.startsWith("http") }
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
        val entries = ep.links ?: return false
        var found = false

        // Stage 1 — fetch every mdisk page CONCURRENTLY. The old code
        // fetched them one after another, and Stage 2 resolved every
        // host on a page before touching the next variant: 4 variants
        // x (mdisk ~2s + G-Direct ~3s + FilePress/V-Cloud/V-Drive
        // extractors ~3-8s each) = 45-90s of serial HTTP, way past
        // the app's 30s loadLinks timeout. On Vibe only the first two
        // G-Direct rows (480p, 720p) ever arrived — 1080p never did.
        // amap is CloudStream's own concurrent map (no kotlinx import
        // needed — the gradle plugin does not expose kotlinx-coroutines
        // to provider modules). Same pattern as the 1080p parallel
        // fix in 4KHDHub v3 / HDHub4u v7 / MoviesDrive v3 / SSRMovies v7.
        val hostGroups: List<List<LinkEntry>> = entries.amap { entry ->
            if (entry.host == "mdisk") {
                // Movie quality: fetch the mdisk page, then resolve
                // every host on it (G-Direct / HubCloud / V-Cloud /
                // V-Drive / Filepress / mirrors).
                // The id is the full mdisk URL (see load()); fall
                // back to the old .cloud URL for legacy numeric ids.
                val mdUrl = if (entry.id.startsWith("http")) entry.id
                    else "https://mdrive.cloud/mdisk/${entry.id}"
                val mdHtml = try {
                    app.get(mdUrl, headers = browserHeaders).text
                } catch (e: Exception) { return@amap emptyList<LinkEntry>() }
                collectHostLinks(mdHtml, entry.label, entry.quality)
            } else {
                listOf(entry)
            }
        }

        // Stage 2 — resolve every host link CONCURRENTLY, collecting
        // the ExtractorLinks instead of emitting them, so a slow host
        // (FilePress / V-Cloud / V-Drive / GoFile mirrors) can never
        // block the fast G-Direct rows of the next quality. amap
        // preserves input order, so the flattened list stays in page
        // order and the callbacks below emit in the same order as
        // before — the source list order is unchanged.
        val resolved: List<List<ExtractorLink>> = hostGroups.flatten().amap { hl ->
            val out = mutableListOf<ExtractorLink>()
            try {
                resolveEntry(hl, subtitleCallback) { out.add(it) }
            } catch (e: Exception) { }
            out
        }

        for (links in resolved) {
            for (link in links) {
                callback(link)
                found = true
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
                // One source per HubCloud server (FSL, FSLv2, PixelDrain,
                // 10G) so the app shows WHICH server a link is.
                val servers = resolveHubCloudServers(entry.id)
                if (servers.isEmpty()) return false
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
                }
                true
            }
            "gdflix" -> {
                loadExtractor("https://gdflix.vip/file/${entry.id}", subtitleCallback, callback)
            }
            "filepress", "vcloud", "vdrive", "extractor" -> {
                loadExtractor(entry.id, subtitleCallback, callback)
            }
            else -> false
        }
    }
}
