package com.fourkhdhub

import java.util.Base64 as JavaBase64
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

class FourKHDHubProvider : MainAPI() {

    override var mainUrl = "https://4khdhub.one"
    override var name = "4KHDHub"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://4khdhub.one/",
    )

    // Episode data passed to loadLinks (as JSON string).
    // A LIST of links (not a map) so every quality variant shows
    // separately with its size, e.g. "2160p DV HDR [16.74 GB]" and
    // "1080p H264 [6.6 GB]" are two different entries.
    // host "gm" = greenmotors.club download button (resolved in loadLinks
    // to a HubCloud/HubDrive URL, then to the direct file).
    // host "videasy" = the Watch Online player embed (via loadExtractor).
    data class LinkEntry(
        @JsonProperty("label") val label: String,
        @JsonProperty("host") val host: String,    // "gm" or "videasy"
        @JsonProperty("id") val id: String,        // greenmotors URL or videasy URL
        @JsonProperty("q") val quality: Int,       // Qualities value
    )

    data class EpisodeData(
        @JsonProperty("links") val links: List<LinkEntry>? = null,
    )

    // Decoded greenmotors JSON: {"w":10,"l":".../homelander/","o":"<b64 target>","data":"...","blog_url":"..."}
    data class GmPayload(
        @JsonProperty("o") val o: String? = null,
        @JsonProperty("data") val data: String? = null,
        @JsonProperty("blog_url") val blogUrl: String? = null,
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
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    // Parse a listing page (home / category / search) into search responses.
    // Cards are <a href="/<slug>-movie-<id>/" class="movie-card"> with a TMDB
    // poster <img> and the title in aria-label / img alt.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("a.movie-card[href]")) {
            val href = a.attr("href")
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            val img = a.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            var title = a.attr("aria-label").replace(Regex(" details$"), "").trim()
            if (title.isEmpty()) title = img?.attr("alt")?.trim() ?: ""
            if (title.isEmpty()) title = cleanTitle(a.text())
            if (title.isEmpty()) continue
            // Strip a trailing " (2026)" year for display; year is not stored here
            val display = title.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
            val isSeries = href.contains("-series-") || title.contains("Season", true)
            if (isSeries) {
                out.add(newTvSeriesSearchResponse(display, url, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(display, url, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/movies/" to "Movies",
        "category/hindi-movies/" to "Hindi Movies",
        "category/english-movies/" to "English Movies",
        "category/series/" to "Latest Episodes",
        "category/korean-series/" to "Korean Series",
        "category/hindi-series/" to "Hindi Series",
        "category/netflix/" to "Netflix",
        "category/jiohotstar/" to "JioHotstar",
        "category/disney/" to "Disney+",
        "category/2160p-HDR/" to "4K HDR",
        "category/imdb/" to "Top IMDb",
        "category/anime/" to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data
        val url = when {
            cat.isEmpty() -> if (page <= 1) mainUrl else "$mainUrl/?pagex=$page"
            page <= 1 -> "$mainUrl/$cat"
            else -> "$mainUrl/$cat?pagex=$page"
        }
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(html)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // Search is page 1 only (?pagex=N on search returns "No results").
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawH1 = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val title = cleanTitle(rawH1).replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
        if (title.isEmpty()) return null
        val year = Regex("\\((\\d{4})\\)").find(rawH1)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        // Plot: the single long <p> (synopsis).
        val plot = doc.select("p").map { cleanTitle(it.text()) }
            .firstOrNull { it.length >= 100 }
        val tags = doc.select("span.badge").map { cleanTitle(it.text()) }
            .filter { it.isNotEmpty() }.distinct().take(8)
        val isSeries = url.contains("-series-")

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            // Map (season, episode) -> list of quality links, so one
            // CloudStream episode carries ALL quality variants (the user
            // picks the highest at play time, HDHub4u v3 lesson).
            val epMap = linkedMapOf<Pair<Int, Int>, MutableList<LinkEntry>>()
            // Per-episode items: div.season-item > div.episode-download-item.
            for (seasonEl in doc.select("div.season-item")) {
                val seasonTxt = seasonEl.selectFirst("div.episode-number")?.text()
                    ?: seasonEl.selectFirst("div.season-title")?.text() ?: ""
                val seasonNum = Regex("S?0*(\\d+)").find(seasonTxt)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: continue
                // Quality of this group, e.g. "S02 1080p WEB-DL H264".
                val groupTitle = seasonEl.selectFirst("div.season-title")?.text()
                    ?: seasonEl.ownText()
                val groupQuality = cleanTitle(groupTitle)
                for (item in seasonEl.select("div.episode-download-item")) {
                    val fileTitle = cleanTitle(item.selectFirst("div.episode-file-title")?.text() ?: "")
                    val epTxt = item.selectFirst("span.badge-psa")?.text() ?: ""
                    val epNum = Regex("Episode-0*(\\d+)").find(epTxt)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("E0*(\\d+)").find(fileTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: continue
                    val size = item.selectFirst("span.badge-size")?.text()?.trim().orEmpty()
                    val sizeTag = if (size.isNotEmpty()) " [$size]" else ""
                    val qLabel = groupQuality.ifEmpty { fileTitle }
                    val quality = qualityValue(qLabel)
                    val label = "$qLabel$sizeTag".trim()
                    val links = epMap.getOrPut(seasonNum to epNum) { mutableListOf() }
                    for (a in item.select("a[href*=greenmotors.club]")) {
                        val gm = a.attr("href")
                        if (gm.isNotEmpty() && links.none { it.id == gm }) {
                            links.add(LinkEntry(label, "gm", gm, quality))
                        }
                    }
                }
            }
            for ((key, links) in epMap) {
                val (seasonNum, epNum) = key
                if (links.isEmpty()) continue
                val dataJson = toJson(EpisodeData(links = links)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.season = seasonNum
                })
            }
            // Season packs (div.download-item outside .episodes-list) as
            // extra "Season Pack" episodes.
            val packItems = doc.select("div.download-item").filter { item ->
                // A pack item is NOT inside an episodes-list container.
                var p = item.parent()
                var insideEpisodes = false
                while (p != null) {
                    if (p.hasClass("episodes-list")) { insideEpisodes = true; break }
                    p = p.parent()
                }
                !insideEpisodes
            }
            val seenPackUrls = mutableSetOf<String>()
            for (item in packItems) {
                val header = item.selectFirst("div.download-header")?.let { cleanTitle(it.text()) } ?: ""
                val fileTitle = cleanTitle(item.selectFirst("div.file-title")?.text() ?: "")
                val size = Regex("(\\d+(\\.\\d+)?\\s*(GB|MB))").find(header)?.groupValues?.getOrNull(1).orEmpty()
                val label = (if (header.isNotEmpty()) header else fileTitle) +
                    (if (size.isNotEmpty()) " [$size]" else "")
                val seasonNum = Regex("S0*(\\d+)").find(header)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("S0*(\\d+)").find(fileTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: continue
                val links = mutableListOf<LinkEntry>()
                for (a in item.select("a[href*=greenmotors.club]")) {
                    val gm = a.attr("href")
                    if (gm.isNotEmpty() && seenPackUrls.add(gm)) {
                        links.add(LinkEntry(label.trim(), "gm", gm, qualityValue(label)))
                    }
                }
                if (links.isEmpty()) continue
                val dataJson = toJson(EpisodeData(links = links)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Season $seasonNum Pack ($label)".trim()
                    this.episode = 0
                    this.season = seasonNum
                })
            }
            if (episodes.isEmpty()) return null
            episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            // Movie: one link entry per quality download-item (label keeps
            // quality + size so the user can pick the highest), plus the
            // Watch Online Videasy embed as a best-effort extra source.
            val links = mutableListOf<LinkEntry>()
            val seenGm = mutableSetOf<String>()
            for (item in doc.select("div.download-item")) {
                val header = item.selectFirst("div.download-header")?.let { cleanTitle(it.text()) } ?: ""
                val fileTitle = cleanTitle(item.selectFirst("div.file-title")?.text() ?: "")
                val size = Regex("(\\d+(\\.\\d+)?\\s*(GB|MB))").find(header)?.groupValues?.getOrNull(1).orEmpty()
                val qLabel = if (header.isNotEmpty()) header else fileTitle
                val label = qLabel + (if (size.isNotEmpty()) " [$size]" else "")
                for (a in item.select("a[href*=greenmotors.club]")) {
                    val gm = a.attr("href")
                    if (gm.isNotEmpty() && seenGm.add(gm)) {
                        links.add(LinkEntry(label.trim(), "gm", gm, qualityValue(label)))
                    }
                }
            }
            // Watch Online (Videasy) — best effort via loadExtractor.
            val videasy = doc.selectFirst("iframe[src*=videasy]")?.attr("src")
            if (!videasy.isNullOrEmpty()) {
                links.add(LinkEntry("Watch Online (Videasy)", "videasy", videasy, Qualities.Unknown.value))
            }
            if (links.isEmpty()) return null
            val dataJson = toJson(EpisodeData(links = links)) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    // ---- Link resolvers ----

    private fun b64decodeToString(input: String): String? {
        return try {
            val padded = input.trim() + "=".repeat((4 - input.trim().length % 4) % 4)
            String(JavaBase64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun b64encode(input: String): String {
        return JavaBase64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
    }

    // ROT13 (Phisher's "pen" function).
    private fun rot13(input: String): String {
        return input.map {
            when (it) {
                in 'A'..'Z' -> ((it - 'A' + 13) % 26 + 'A'.code).toChar()
                in 'a'..'z' -> ((it - 'a' + 13) % 26 + 'a'.code).toChar()
                else -> it
            }
        }.joinToString("")
    }

    // greenmotors.club/?id=<token> -> the real HubCloud/HubDrive URL.
    // The "Redirecting..." page contains s('o','<b64>') (fallback
    // ck('_wp_http_<n>','<v>')). Decode: b64(b64(b64(s))) -> ROT13 -> b64
    // -> JSON {"o":"<b64 target>","data":"...","blog_url":"..."}.
    // Verified live on movie AND series tokens.
    private suspend fun resolveGreenMotors(gmUrl: String): String? {
        val html = try {
            app.get(gmUrl, headers = browserHeaders).text
        } catch (e: Exception) { return null }
        val combined = buildString {
            Regex("s\\('o','([A-Za-z0-9+/=]+)'").findAll(html).forEach {
                append(it.groupValues[1])
            }
            Regex("ck\\('_wp_http_\\d+','([^']+)'").findAll(html).forEach {
                append(it.groupValues[1])
            }
        }
        if (combined.isEmpty()) return null
        return try {
            val step1 = b64decodeToString(combined) ?: return null
            val step2 = b64decodeToString(step1) ?: return null
            val step3 = b64decodeToString(rot13(step2)) ?: return null
            val payload = tryParseJson<GmPayload>(step3) ?: return null
            val encodedUrl = payload.o?.let { b64decodeToString(it) }?.trim().orEmpty()
            if (encodedUrl.isNotEmpty()) return encodedUrl
            // Fallback: <blog_url>?re=<b64(data)> returns the direct link as body text.
            val blogUrl = payload.blogUrl?.trim().orEmpty()
            val data = payload.data?.trim().orEmpty()
            if (blogUrl.isNotEmpty() && data.isNotEmpty()) {
                val direct = try {
                    app.get("$blogUrl?re=${b64encode(data)}", headers = browserHeaders).text.trim()
                } catch (e: Exception) { "" }
                if (direct.startsWith("http")) return direct
            }
            null
        } catch (e: Exception) { null }
    }

    // hubdrive.blog/file/<id> -> HubDrive page with a hubcloud.pw/drive/<code>
    // link, resolved by the same HubCloud chain as KatDrama/HDHub4u.
    private suspend fun resolveHubDrive(fileId: String): String? {
        val html = try {
            app.get("https://hubdrive.blog/file/$fileId", headers = browserHeaders).text
        } catch (e: Exception) { return null }
        val code = Regex("hubcloud\\.pw/drive/([A-Za-z0-9]+)").find(html)?.groupValues?.getOrNull(1) ?: return null
        return resolveHubCloudDirect(code)
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

    // Resolve one greenmotors button to the direct video file URL.
    private suspend fun resolveGmEntry(gmUrl: String): String? {
        val target = resolveGreenMotors(gmUrl) ?: return null
        return when {
            target.contains("hubcloud.pw/drive/") -> {
                val code = target.substringAfter("hubcloud.pw/drive/").substringBefore("/").substringBefore("?").trim()
                if (code.isEmpty()) null else resolveHubCloudDirect(code)
            }
            target.contains("hubdrive.blog/file/") -> {
                val id = target.substringAfter("hubdrive.blog/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isEmpty()) null else resolveHubDrive(id)
            }
            target.startsWith("http") -> target
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        var found = false

        // Resolve every link entry; the source name keeps the full quality
        // label (with size) so the user can pick the highest, e.g.
        // "HubCloud 2160p DV HDR [16.74 GB]".
        ep.links?.forEach { entry ->
            when (entry.host) {
                "videasy" -> {
                    // Watch Online player embed — best effort via the
                    // built-in extractors (unreachable from this VM, so
                    // unverified here; the greenmotors path is primary).
                    try {
                        loadExtractor(entry.id, subtitleCallback, callback)
                        found = true
                    } catch (e: Exception) { }
                }
                "gm" -> {
                    val direct = resolveGmEntry(entry.id) ?: return@forEach
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
            }
        }

        return found
    }
}
