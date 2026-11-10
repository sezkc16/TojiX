package com.cinefreak

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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup

class CineFreakProvider : MainAPI() {

    override var mainUrl = "https://cinefreak.net"
    override var name = "CineFreak"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    // ---- CineCloud API models (Jackson) ----

    data class NonceResponse(
        @JsonProperty("n") val n: String? = null,
    )

    data class UnlockResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("html") val html: String? = null,
        @JsonProperty("message") val message: String? = null,
    )

    data class PlayResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("vlc") val vlc: String? = null,
        @JsonProperty("state") val state: String? = null,
    )

    // ---- Search API models (/search-api.php) ----

    data class SearchItem(
        @JsonProperty("t") val t: String? = null,
        @JsonProperty("l") val l: String? = null,
        @JsonProperty("c") val c: String? = null,
        @JsonProperty("i") val i: String? = null,
        @JsonProperty("q") val q: String? = null,
    )

    data class SearchApiResponse(
        @JsonProperty("results") val results: List<SearchItem>? = null,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
    )

    // Data passed to loadLinks (as JSON string).
    // host "dl": a CineCloud download token — loadLinks does
    //   /n -> /unlock -> server buttons -> 307 redirect -> R2 file.
    // host "watch": a CineCloud watch token — loadLinks does
    //   POST a=play -> JSON {url} (the R2 file).
    // All servers for one quality redirect to the SAME R2 file, so
    // loadLinks dedupes by final URL.
    data class LinkEntry(
        @JsonProperty("label") val label: String,  // e.g. "480p [370 MB]"
        @JsonProperty("host") val host: String,    // "dl" or "watch"
        @JsonProperty("id") val id: String,        // CineCloud token (after /f/)
        @JsonProperty("q") val quality: Int,
    )

    data class EpisodeData(
        @JsonProperty("links") val links: List<LinkEntry>? = null,
    )

    private fun qualityValue(label: String?): Int {
        return when {
            label == null -> Qualities.Unknown.value
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

    private fun cleanTitle(raw: String?): String {
        var t = (raw ?: "").replace(Regex("\\s+"), " ").trim()
        t = t.removeSuffix(" details").removeSuffix(" Details")
        t = t.substringBefore(" | ").substringBefore(" – ").trim()
        // Strip the SEO tail ("Download & Watch Online ...", "Full Movie Download ...")
        t = t.replace(Regex("(?i)\\s*(full (movie|series) )?download.*$"), "").trim()
        t = t.replace(Regex("(?i)\\s*watch online.*$"), "").trim()
        return t
    }

    // Parse a listing page (home / category) into search responses.
    // Cards are <a class="movie-card" href="https://cinefreak.net/<slug>/">
    // with aria-label="<full SEO title> details" and a TMDB poster <img>.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("a.movie-card[href]")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            val img = a.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            var title = a.attr("aria-label").trim()
            if (title.isEmpty()) title = img?.attr("alt")?.trim() ?: ""
            if (title.isEmpty()) title = a.text().trim()
            val display = cleanTitle(title)
            if (display.isEmpty()) continue
            val isSeries = url.contains("-full-series/") || display.contains("Season", true)
            if (isSeries) {
                out.add(newTvSeriesSearchResponse(display, url, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(display, url, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    // Categories are direct paths (not /category/): /hindi-movies/,
    // /k-drama/, /web-series/ ... 30 cards per page, /page/N/ pagination.
    override val mainPage = mainPageOf(
        "" to "Latest",
        "hindi-movies" to "Hindi Movies",
        "hindi-dubbed-movies" to "Hindi Dubbed",
        "english-movies" to "English Movies",
        "bangla-movies" to "Bangla Movies",
        "k-drama" to "K-Drama",
        "web-series" to "Web Series",
        "dual-audio" to "Dual Audio",
        "animation" to "Animation",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data
        val url = when {
            cat.isEmpty() && page <= 1 -> mainUrl
            cat.isEmpty() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$cat/"
            else -> "$mainUrl/$cat/page/$page/"
        }
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(html)
        return newHomePageResponse(
            HomePageList(request.name, list),
            hasNext = list.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // The site's own fast-search JSON API:
        // GET /search-api.php?q=<query>&pg=<page>
        val url = "$mainUrl/search-api.php?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}&pg=$page"
        val res: SearchApiResponse = try {
            app.get(url, headers = browserHeaders).parsed()
        } catch (e: Exception) { return null }
        val list = res.results?.mapNotNull { item ->
            val slug = item.l?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = cleanTitle(item.t)
            if (title.isEmpty()) return@mapNotNull null
            val postUrl = "$mainUrl/$slug/"
            val isSeries = slug.contains("-full-series/") || title.contains("Season", true)
            if (isSeries) {
                newTvSeriesSearchResponse(title, postUrl, TvType.TvSeries) { this.posterUrl = item.i }
            } else {
                newMovieSearchResponse(title, postUrl, TvType.Movie) { this.posterUrl = item.i }
            }
        } ?: emptyList()
        return list.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("title")?.text()?.trim() ?: ""
        val title = cleanTitle(rawTitle)
        if (title.isEmpty()) return null
        // og:image is a RankMath admin-ajax overlay GIF, not a poster.
        val og = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val poster = og?.takeIf { !it.contains("admin-ajax") && !it.contains("rank_math") }
            ?: doc.selectFirst(".screenshot-container img")?.attr("src")
        val isSeries = url.contains("-full-series/") || title.contains("Season", true)

        if (isSeries) {
            val season = Regex("(?i)Season\\s*(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val episodes = mutableListOf<Episode>()
            // Each .cft-row is one episode (or a batch, e.g. "Episodes 01-04"):
            // <b>Episode 00</b>, a Watch pill (a.cfs-watch) and quality
            // buttons (a.cfs-dl with <b>1080p</b>), all CineCloud tokens.
            for (row in doc.select("div.cft-row")) {
                val label = row.selectFirst(".cft-info b")?.text()?.trim() ?: continue
                val epNum = Regex("(\\d+)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                val links = mutableListOf<LinkEntry>()
                val seenTokens = mutableSetOf<String>()
                row.select("a.cfs-watch[href*=cinecloud.top/f/]").forEach { a ->
                    val tok = a.attr("href").substringAfter("cinecloud.top/f/").substringBefore("/").substringBefore("?").trim()
                    if (tok.isNotEmpty() && seenTokens.add("w:$tok")) {
                        links.add(LinkEntry("Watch $label", "watch", tok, Qualities.Unknown.value))
                    }
                }
                row.select("a.cfs-dl[href*=cinecloud.top/f/]").forEach { a ->
                    val tok = a.attr("href").substringAfter("cinecloud.top/f/").substringBefore("/").substringBefore("?").trim()
                    val q = a.selectFirst("b")?.text()?.trim() ?: a.text().trim()
                    if (tok.isNotEmpty() && seenTokens.add("d:$tok")) {
                        links.add(LinkEntry(q, "dl", tok, qualityValue(q)))
                    }
                }
                if (links.isEmpty()) continue
                val dataJson = toJson(EpisodeData(links = links)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = label
                    this.episode = epNum
                    this.season = season
                })
            }
            if (episodes.isEmpty()) return null
            episodes.sortBy { it.episode ?: 0 }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        } else {
            // Movie: quality sections under div.download-links-div —
            // <h4 class="movie-title">Title [Lang] SD 480p [370 MB]</h4>
            // followed by <div class="dlbtn-container"> with a
            // dlbtn-download and a dlbtn-watch CineCloud link each.
            // NOTE: a hidden .c5a7826 div holds decoy duplicates — skip
            // anything inside it.
            val links = mutableListOf<LinkEntry>()
            val seenTokens = mutableSetOf<String>()
            val section = doc.selectFirst("div.download-links-div") ?: doc.body()
            for (h in section.select("h4.movie-title")) {
                // Skip headings inside the hidden decoy div
                if (h.closest(".c5a7826") != null) continue
                val heading = h.text().trim()
                val quality = Regex("(\\d{3,4}p|4K)", RegexOption.IGNORE_CASE).find(heading)?.value ?: "HD"
                val size = Regex("\\[([^\\]]*(?:MB|GB))\\]", RegexOption.IGNORE_CASE).find(heading)?.groupValues?.getOrNull(1) ?: ""
                val label = if (size.isNotEmpty()) "$quality [$size]" else quality
                val q = qualityValue(quality)
                // The buttons container right after this heading
                var container = h.nextElementSibling()
                while (container != null && !container.hasClass("dlbtn-container")) {
                    if (container.tagName() == "h4") break
                    container = container.nextElementSibling()
                }
                if (container == null) continue
                container.select("a.dlbtn-download[href*=cinecloud.top/f/]").forEach { a ->
                    val tok = a.attr("href").substringAfter("cinecloud.top/f/").substringBefore("/").substringBefore("?").trim()
                    if (tok.isNotEmpty() && seenTokens.add("d:$tok")) {
                        links.add(LinkEntry(label, "dl", tok, q))
                    }
                }
                container.select("a.dlbtn-watch[href*=cinecloud.top/f/]").forEach { a ->
                    val tok = a.attr("href").substringAfter("cinecloud.top/f/").substringBefore("/").substringBefore("?").trim()
                    if (tok.isNotEmpty() && seenTokens.add("w:$tok")) {
                        links.add(LinkEntry(label, "watch", tok, q))
                    }
                }
            }
            if (links.isEmpty()) return null
            val dataJson = toJson(EpisodeData(links = links)) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
            }
        }
    }

    // ---- CineCloud resolvers (all verified via plain HTTP) ----
    // The Turnstile widget on CineCloud pages is NOT enforced server-side:
    // /unlock and the a=play POST both return ok:true with an EMPTY token,
    // so a provider can resolve every link without a browser.

    private fun cineHeaders(token: String): Map<String, String> {
        return browserHeaders + mapOf("Referer" to "https://cinecloud.top/f/$token")
    }

    // Download token -> server buttons -> 307 redirect -> direct R2 file.
    // Tries server buttons in order until one resolves; all servers for
    // one quality point at the same R2 file, so one resolved URL is enough.
    private suspend fun resolveDownloadToken(token: String): String? {
        // 1) "same browser" nonce
        val nonce: NonceResponse = try {
            app.get("https://cinecloud.top/f/$token/n", headers = cineHeaders(token)).parsed()
        } catch (e: Exception) { return null }
        val n = nonce.n ?: return null
        // 2) unlock (empty Turnstile token works — not enforced server-side)
        val unlock: UnlockResponse = try {
            app.post(
                "https://cinecloud.top/f/$token/unlock",
                headers = cineHeaders(token),
                params = mapOf("ts" to "", "bc" to n)
            ).parsed()
        } catch (e: Exception) { return null }
        if (unlock.ok != true) return null
        val html = unlock.html ?: return null
        val serverDoc = Jsoup.parse(html)
        val serverHrefs = serverDoc.select("a[href^=/f/]")
            .map { it.attr("href") }
            .distinct()
        for (href in serverHrefs) {
            val serverUrl = "https://cinecloud.top$href"
            // 3) the server link 307-redirects to the direct R2 file;
            // read the Location without following it.
            val loc = try {
                val resp = app.get(serverUrl, headers = cineHeaders(token), allowRedirects = false)
                resp.headers["Location"] ?: resp.headers["location"]
            } catch (e: Exception) { null }
            if (!loc.isNullOrEmpty() && loc.startsWith("http")) return loc
            // Fallback: hand the player the CineCloud URL itself — the
            // player follows the 307 to the file on its own.
            return serverUrl
        }
        return null
    }

    // Watch token -> POST a=play -> JSON {ok, url} (direct R2 file).
    private suspend fun resolveWatchToken(token: String): String? {
        val play: PlayResponse = try {
            app.post(
                "https://cinecloud.top/f/$token",
                headers = cineHeaders(token),
                params = mapOf("a" to "play")
            ).parsed()
        } catch (e: Exception) { return null }
        if (play.ok != true) return null
        return play.url?.takeIf { it.startsWith("http") }
            ?: play.vlc?.takeIf { it.startsWith("http") }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        var found = false
        val seenUrls = mutableSetOf<String>()
        ep.links?.forEach { entry ->
            val direct = when (entry.host) {
                "dl" -> resolveDownloadToken(entry.id)
                "watch" -> resolveWatchToken(entry.id)
                else -> null
            } ?: return@forEach
            if (!seenUrls.add(direct)) return@forEach
            callback(
                newExtractorLink(
                    source = "CineFreak",
                    name = "CineFreak ${entry.label}",
                    url = direct,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = entry.quality
                    this.referer = "https://cinecloud.top/"
                }
            )
            found = true
        }
        return found
    }
}
