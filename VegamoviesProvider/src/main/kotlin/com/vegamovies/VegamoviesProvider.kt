package com.vegamovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import org.jsoup.Jsoup

class VegamoviesProvider : MainAPI() {

    override var mainUrl = "https://vegamovies8.co.in"
    override var name = "Vegamovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    // Episode data passed to loadLinks (as JSON string).
    // kind "nexdrive": a nexdrive.<tld>/genxfm<digits>/ link.
    //   ep == null  -> movie quality variant (whole file)
    //   ep != null  -> series episode N inside that quality's nexdrive page
    data class LinkEntry(
        @JsonProperty("label") val label: String,   // e.g. "480p [330MB]"
        @JsonProperty("host") val host: String,     // "nexdrive"
        @JsonProperty("id") val id: String,         // full nexdrive URL
        @JsonProperty("q") val quality: Int,        // Qualities value
        @JsonProperty("ep") val ep: Int? = null,    // episode number, or null
    )

    data class EpisodeData(
        @JsonProperty("links") val links: List<LinkEntry>? = null,
    )

    data class PrepareResponse(
        @JsonProperty("ok") val ok: Boolean = false,
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

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    private fun fixPoster(poster: String?): String? {
        if (poster.isNullOrEmpty()) return null
        return if (poster.startsWith("http")) poster else mainUrl + poster
    }

    // Parse a listing page (home / category / search) into search responses.
    // Posts are <article class="post-item"> with <a class="blog-img">
    // (href = post URL, title attr) and <img src="/uploads/posts/covers/*.webp">.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.post-item")) {
            val a = article.selectFirst("a.blog-img") ?: article.selectFirst("h3.entry-title a") ?: continue
            val href = a.attr("href").trim()
            if (href.isEmpty()) continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            val img = article.selectFirst("img")
            val poster = fixPoster(
                img?.attr("src")?.takeIf { it.isNotEmpty() }
                    ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            )
            var title = cleanTitle(article.selectFirst("h3.entry-title a")?.text() ?: "")
            if (title.isEmpty()) title = cleanTitle(a.attr("title"))
            if (title.isEmpty()) continue
            val isSeries = title.contains("Season", true)
                || title.contains("TV-Show", true)
                || Regex("(?i)\\bEP[- ]?\\d+").containsMatchIn(title)
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
        "bollywood-movies" to "Bollywood",
        "hollywood-movies" to "Hollywood",
        "dual-audio-hindi-english-movies" to "Dual Audio Hindi",
        "dual-audio-hindi-english-movies/south-indian-dubbed-movies-download" to "South Hindi",
        "tv-shows/web-series-hindi" to "Web Series Hindi",
        "telugu-movies-free-download" to "Telugu",
        "tamil-movies" to "Tamil",
        "action" to "Action",
        "comedy" to "Comedy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data.trim().trim('/')
        val url = when {
            cat.isEmpty() -> if (page <= 1) mainUrl else "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$cat/"
            else -> "$mainUrl/$cat/page/$page/"
        }
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(html)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // DLE search (GET form works the same as the POST form)
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$q"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    // One quality section on a detail page: the nexdrive link + the
    // quality heading + the button's size, e.g. 480p / [330MB].
    private data class QualitySection(
        val nexUrl: String,
        val quality: String,
        val size: String?,
    )

    private fun parseQualitySections(html: String): List<QualitySection> {
        val out = mutableListOf<QualitySection>()
        val seen = mutableSetOf<String>()
        val anchorRe = Regex("""<a[^>]*href="(https://nexdrive\.[^"/]+/[^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        for (m in anchorRe.findAll(html)) {
            val url = m.groupValues[1].trim()
            if (!seen.add(url)) continue
            // Quality = the nearest 480p/720p/1080p/2160p/4K token BEFORE the anchor
            val before = html.substring(maxOf(0, m.range.first - 800), m.range.first)
            val q = Regex("(?i)(2160p|1080p|720p|480p|360p|4K)").findAll(before).lastOrNull()?.groupValues?.get(1) ?: "HD"
            // Size = [330MB] / [6.6GB] inside the button text
            val btnText = m.groupValues[2].replace(Regex("<[^>]+>"), " ")
            val size = Regex("""\[([0-9.]+ ?[MG]B)\]""").find(btnText)?.groupValues?.get(1)
            out.add(QualitySection(url, q.uppercase(), size))
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("title")?.text()?.trim() ?: ""
        val title = cleanTitle(rawTitle)
            .removePrefix("Download ")
            .replace(Regex(" - Vegamovies$"), "")
            .trim()
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: fixPoster(doc.selectFirst(".blog-pic img, article img")?.attr("src"))
        val sections = parseQualitySections(html)
        if (sections.isEmpty()) return null // vgmlinks-only posts can't play
        val isSeries = title.contains("Season", true)
            || title.contains("TV-Show", true)
            || Regex("(?i)\\bEP[- ]?\\d+").containsMatchIn(title)
        val seasonNum = Regex("(?i)Season\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            for (sec in sections) {
                val q = qualityValue(sec.quality)
                val sizeSuffix = sec.size?.let { " [$it]" } ?: ""
                // Fetch the nexdrive page to list its episodes:
                // "-:Episodes: N:-" sections, each with a G-Direct link.
                val epNums = fetchEpisodeNumbers(sec.nexUrl)
                if (epNums.isEmpty()) {
                    // No episode sections (e.g. a "Complete" pack): one entry
                    val dataJson = toJson(
                        EpisodeData(links = listOf(
                            LinkEntry("${sec.quality}$sizeSuffix", "nexdrive", sec.nexUrl, q, null)
                        ))
                    ) ?: continue
                    episodes.add(newEpisode(dataJson) {
                        this.name = "Complete (${sec.quality}$sizeSuffix)"
                        this.season = seasonNum
                        this.episode = 1
                        this.posterUrl = poster
                    })
                } else {
                    for (epNum in epNums) {
                        val dataJson = toJson(
                            EpisodeData(links = listOf(
                                LinkEntry("${sec.quality}$sizeSuffix", "nexdrive", sec.nexUrl, q, epNum)
                            ))
                        ) ?: continue
                        episodes.add(newEpisode(dataJson) {
                            this.name = "Episode $epNum (${sec.quality}$sizeSuffix)"
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster
                        })
                    }
                }
            }
            if (episodes.isEmpty()) return null
            episodes.sortWith(compareBy({ it.episode ?: 0 }, { it.name }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        } else {
            val links = sections.map { sec ->
                val sizeSuffix = sec.size?.let { " [$it]" } ?: ""
                LinkEntry("${sec.quality}$sizeSuffix", "nexdrive", sec.nexUrl, qualityValue(sec.quality), null)
            }
            val dataJson = toJson(EpisodeData(links = links)) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
            }
        }
    }

    // ---- Link resolution ----
    // Chain: nexdrive.<tld>/genxfm.../ -> "G-Direct [Instant]" link
    // fast-dl.one/dl/<code> -> button href dl.php?code=<code>&secret=<tok>
    // -> GET /dl/dl.php?code&secret&prepare=1&hash=<24hex>&cfs=<16hex>
    // (Accept: application/json) -> {"ok":true,"url":"<googleusercontent file>"}

    private fun randomHex(bytes: Int): String {
        val rnd = java.security.SecureRandom()
        val buf = ByteArray(bytes)
        rnd.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    // Fetch a nexdrive page and list its episode numbers ("-:Episodes: N:-").
    // Returns empty if the page has no episode sections (movie / pack).
    private suspend fun fetchEpisodeNumbers(nexUrl: String): List<Int> {
        val html = try {
            app.get(nexUrl, headers = browserHeaders + mapOf("Referer" to "$mainUrl/")).text
        } catch (e: Exception) { return emptyList() }
        return Regex("""-:Episodes:\s*(\d+):-""").findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct().sorted().toList()
    }

    // Resolve one nexdrive link (optionally a single episode inside it)
    // all the way to the direct googleusercontent file URL.
    private suspend fun resolveNexdrive(nexUrl: String, ep: Int?): String? {
        val nexHtml = try {
            app.get(nexUrl, headers = browserHeaders + mapOf("Referer" to "$mainUrl/")).text
        } catch (e: Exception) { return null }
        // Pick the G-Direct fast-dl link: for an episode, the one inside
        // that episode's section; otherwise the first one on the page.
        val fastDlUrl = if (ep != null) {
            val markers = Regex("""-:Episodes:\s*(\d+):-""").findAll(nexHtml).toList()
            var found: String? = null
            for ((idx, mk) in markers.withIndex()) {
                if (mk.groupValues[1].toIntOrNull() != ep) continue
                val segStart = mk.range.last
                val segEnd = if (idx + 1 < markers.size) markers[idx + 1].range.first else nexHtml.length
                val seg = nexHtml.substring(segStart, segEnd)
                found = Regex("""https://fast-dl\.one/dl/[A-Za-z0-9]+""").find(seg)?.value
                break
            }
            found
        } else {
            Regex("""https://fast-dl\.one/dl/[A-Za-z0-9]+""").find(nexHtml)?.value
        } ?: return null

        val fastHtml = try {
            app.get(fastDlUrl, headers = browserHeaders + mapOf("Referer" to nexUrl)).text
        } catch (e: Exception) { return null }
        val btn = Regex("""dl\.php\?code=([A-Za-z0-9]+)(?:&amp;|&)secret=([^"&\s]+)""").find(fastHtml)
            ?: return null
        val code = btn.groupValues[1]
        val secret = btn.groupValues[2]
        val base = fastDlUrl.substringBeforeLast("/")
        val prepareUrl = "$base/dl.php?code=$code" +
            "&secret=${java.net.URLEncoder.encode(secret, "UTF-8")}" +
            "&prepare=1&hash=${randomHex(12)}&cfs=${randomHex(8)}"
        val prep: PrepareResponse = try {
            app.get(
                prepareUrl,
                headers = browserHeaders + mapOf(
                    "Referer" to fastDlUrl,
                    "Accept" to "application/json",
                )
            ).parsed()
        } catch (e: Exception) { return null }
        if (!prep.ok) return null
        return prep.url?.takeIf { it.startsWith("http") }
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
            val direct = when (entry.host) {
                "nexdrive" -> resolveNexdrive(entry.id, entry.ep)
                else -> null
            } ?: return@forEach
            callback(
                newExtractorLink(
                    source = "G-Direct",
                    name = "G-Direct ${entry.label}",
                    url = direct,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = entry.quality
                    this.referer = "https://fast-dl.one/"
                }
            )
            found = true
        }
        return found
    }
}
