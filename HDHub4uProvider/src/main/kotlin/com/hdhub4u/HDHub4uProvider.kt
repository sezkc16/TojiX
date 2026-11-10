package com.hdhub4u

import android.util.Base64
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

class HDHub4uProvider : MainAPI() {

    override var mainUrl = "https://new12.hdhub4u.cl"
    override var name = "HDHub4u"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Episode data passed to loadLinks (as JSON string)
    // hubcdn: quality label -> hubcdn file id (resolves to direct R2 file)
    // hubdrive: quality label -> hubdrive file id (resolves via HubCloud)
    data class EpisodeData(
        @JsonProperty("hubcdn") val hubcdn: Map<String, String>? = null,
        @JsonProperty("hubdrive") val hubdrive: Map<String, String>? = null,
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
    // Posts are <a href="/<slug>-full-movie/"> or "-full-series/" with a
    // TMDB poster <img> and the title text inside.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("a[href*=-full-movie/], a[href*=-full-series/]")) {
            val href = a.attr("href")
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            val img = a.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            var title = a.text().trim()
            if (title.isEmpty()) {
                title = a.attr("title").trim()
            }
            if (title.isEmpty()) continue
            // Strip the quality/size suffix for a cleaner display title
            val display = title
                .replace(Regex("\\s*\\[.*?\\]\\s*"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val isSeries = url.contains("-full-series/") || title.contains("Season", true)
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
            page <= 1 -> "$mainUrl/category/$cat/"
            else -> "$mainUrl/category/$cat/page/$page/"
        }
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(html)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    override val mainPage = mainPageOf(
        "" to "Latest",
        "bollywood-movies" to "Bollywood",
        "hollywood-movies" to "Hollywood",
        "hindi-dubbed" to "Hindi Dubbed",
        "south-hindi-movies" to "South Hindi",
        "web-series" to "Web Series",
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
            .replace(Regex(" – HDHub.*$"), "")
            .replace(Regex(" \\| HDHub.*$"), "")
            .trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.entry-content img")?.attr("src")
        val isSeries = url.contains("-full-series/") || title.contains("Season", true)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            // Per-episode: "EPiSODE N" heading, then a 720p and a 1080p
            // hubdrive link. Walk the links in document order, tracking the
            // current episode number from the surrounding headings.
            val bodyHtml = doc.selectFirst("div.entry-content")?.html() ?: html
            // Split the content on "EPiSODE N" markers
            val epSplit = Regex("(?i)EPiSODE\\s*(\\d+)").findAll(bodyHtml).toList()
            for ((idx, m) in epSplit.withIndex()) {
                val epNum = m.groupValues[1].toIntOrNull() ?: (idx + 1)
                val segStart = m.range.last
                val segEnd = if (idx + 1 < epSplit.size) epSplit[idx + 1].range.first else bodyHtml.length
                val seg = bodyHtml.substring(segStart, segEnd)
                val hub = mutableMapOf<String, String>()
                for (lm in Regex("https://hubdrive\\.blog/file/(\\d+)").findAll(seg)) {
                    // The 720p link comes first, 1080p second in each segment
                    val q = if (hub.containsKey("720p")) "1080p" else "720p"
                    hub[q] = lm.groupValues[1]
                }
                if (hub.isEmpty()) continue
                val dataJson = toJson(EpisodeData(hubdrive = hub)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                })
            }
            // Season packs (1080p / 4K) as extra episodes
            val packLinks = Regex("https://hubdrive\\.blog/file/(\\d+)").findAll(bodyHtml)
                .map { it.groupValues[1] }.distinct().toList()
            val usedIds = episodes.flatMap { ep ->
                val d = tryParseJson<EpisodeData>(ep.data) ?: return@flatMap emptyList<String>()
                (d.hubdrive?.values ?: emptyList())
            }.toSet()
            for (pid in packLinks) {
                if (pid in usedIds) continue
                // Only treat it as a pack if it appears near a "PACK" label
                val pos = bodyHtml.indexOf(pid)
                val ctx = if (pos >= 0) bodyHtml.substring(maxOf(0, pos - 120), minOf(bodyHtml.length, pos + 40)) else ""
                if (!ctx.contains("PACK", true)) continue
                val q = when {
                    ctx.contains("2160", true) || ctx.contains("4K", true) -> "2160p"
                    ctx.contains("1080", true) -> "1080p"
                    else -> "Pack"
                }
                val dataJson = toJson(EpisodeData(hubdrive = mapOf(q to pid))) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Season Pack ($q)"
                })
            }
            if (episodes.isEmpty()) return null
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        } else {
            // Movie: collect quality download links (hubcdn + hubdrive).
            // greenmotors links are NOT resolvable, so they are skipped.
            val hubcdn = mutableMapOf<String, String>()
            val hubdrive = mutableMapOf<String, String>()
            for (a in doc.select("div.entry-content a[href]")) {
                val href = a.attr("href")
                val txt = a.text().trim()
                if (txt.isEmpty()) continue
                val q = when {
                    txt.contains("2160", true) || txt.contains("4K", true) -> "2160p"
                    txt.contains("1080", true) -> "1080p"
                    txt.contains("720", true) -> "720p"
                    txt.contains("480", true) -> "480p"
                    else -> continue
                }
                when {
                    href.contains("hubcdn.club/file/") -> {
                        val id = href.substringAfter("hubcdn.club/file/").substringBefore("/").substringBefore("?").trim()
                        if (id.isNotEmpty() && !hubcdn.containsKey(q)) hubcdn[q] = id
                    }
                    href.contains("hubdrive.blog/file/") -> {
                        val id = href.substringAfter("hubdrive.blog/file/").substringBefore("/").substringBefore("?").trim()
                        if (id.isNotEmpty() && !hubdrive.containsKey(q)) hubdrive[q] = id
                    }
                }
            }
            if (hubcdn.isEmpty() && hubdrive.isEmpty()) return null
            val dataJson = toJson(EpisodeData(hubcdn = hubcdn.ifEmpty { null }, hubdrive = hubdrive.ifEmpty { null })) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
            }
        }
    }

    // ---- Link resolvers ----

    private fun b64decode(input: String): String? {
        return try {
            val padded = input + "=".repeat((4 - input.length % 4) % 4)
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    // hubcdn.club/file/<id> -> JS redirect page with a reurl:
    // inventoryidea.com/?r=<b64 of hubcdn dl base>/<b64 of "link=<R2 URL>">
    // The R2 URL is the direct video file.
    private suspend fun resolveHubCdn(fileId: String): String? {
        val html = try {
            app.get("https://hubcdn.club/file/$fileId", headers = browserHeaders).text
        } catch (e: Exception) { return null }
        val reurl = Regex("reurl\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1) ?: return null
        // reurl = https://inventoryidea.com/?r=<b64>/<b64>
        val parts = reurl.substringAfter("?r=", "").split("/")
        if (parts.size < 2) return null
        val decoded = b64decode(parts[1]) ?: return null
        return decoded.removePrefix("link=").trim().takeIf { it.startsWith("http") }
    }

    // hubdrive.blog/file/<id> -> HubDrive page with a hubcloud.pw/drive/<code>
    // link, resolved by the same HubCloud chain as KatDrama.
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        var found = false

        // hubcdn -> direct R2 file
        ep.hubcdn?.forEach { (label, fileId) ->
            val direct = resolveHubCdn(fileId) ?: return@forEach
            callback(
                newExtractorLink(
                    source = "HubCdn",
                    name = "HubCdn $label",
                    url = direct,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = qualityValue(label)
                    this.referer = "https://hubcdn.club/"
                }
            )
            found = true
        }

        // hubdrive -> HubCloud direct file
        ep.hubdrive?.forEach { (label, fileId) ->
            val direct = resolveHubDrive(fileId) ?: return@forEach
            callback(
                newExtractorLink(
                    source = "HubCloud",
                    name = "HubCloud $label",
                    url = direct,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = qualityValue(label)
                    this.referer = "https://hubcloud.pw/"
                }
            )
            found = true
        }

        return found
    }
}
