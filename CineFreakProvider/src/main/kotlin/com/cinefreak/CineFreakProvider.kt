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
        @JsonProperty("need") val need: String? = null,
        @JsonProperty("message") val message: String? = null,
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
    // CineCloud token = the ID part of the /f/ link ONLY (before the
    // "_" suffix). The full href is /f/<id>_<suffix>, but a=play and
    // /unlock accept ONLY the id — the full string returns 410 expired
    // (found 2026-11-12: watch tokens work id-only, fail with suffix).
    // load() strips the suffix with substringBefore("_").
    data class LinkEntry(
        @JsonProperty("label") val label: String,  // e.g. "480p [370 MB]"
        @JsonProperty("host") val host: String,    // "dl" or "watch"
        @JsonProperty("id") val id: String,        // CineCloud token id (after /f/, before _)
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
                    val tok = a.attr("href").substringAfter("cinecloud.top/f/").substringBefore("/").substringBefore("?").substringBefore("_").trim()
                    if (tok.isNotEmpty() && seenTokens.add("w:$tok")) {
                        links.add(LinkEntry("Watch $label", "watch", tok, Qualities.Unknown.value))
                    }
                }
                row.select("a.cfs-dl[href*=cinecloud.top/f/]").forEach { a ->
                    val tok = a.attr("href").substringAfter("cinecloud.top/f/").substringBefore("/").substringBefore("?").substringBefore("_").trim()
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
                    val tok = a.attr("href").substringAfter("cinecloud.top/f/").substringBefore("/").substringBefore("?").substringBefore("_").trim()
                    if (tok.isNotEmpty() && seenTokens.add("d:$tok")) {
                        links.add(LinkEntry(label, "dl", tok, q))
                    }
                }
                container.select("a.dlbtn-watch[href*=cinecloud.top/f/]").forEach { a ->
                    val tok = a.attr("href").substringAfter("cinecloud.top/f/").substringBefore("/").substringBefore("?").substringBefore("_").trim()
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

    // ---- CineCloud resolvers ----
    // CineCloud's Turnstile check is INTERMITTENT (found 2026-11-10):
    // most of the time /unlock and a=play accept an EMPTY token, but
    // during enforcement windows they reject it (403 "Verification
    // failed" / 401 "need ts") and NO provider can obtain a real
    // Turnstile token — only a browser can. So loadLinks below runs in
    // two passes: a fast first pass, and — only if it finds NOTHING —
    // a delayed second pass, so a short enforcement blip does not
    // zero out the whole list. A sustained enforcement window still
    // cannot be beaten (same as Kartoons.to).
    // Also: some download tokens go stale (410 "link has expired")
    // while their sibling watch token still works, so watch is tried
    // before download for the same quality.

    private fun cineHeaders(token: String): Map<String, String> {
        return browserHeaders + mapOf("Referer" to "https://cinecloud.top/f/$token")
    }

    // Download token -> server buttons -> 307 redirect -> direct R2 file.
    // Single attempt (the caller retries at the loadLinks level).
    // Prefers VISIBLE server buttons; the hidden .c5a7826 div holds
    // duplicates (they also work, but are decoys).
    private suspend fun resolveDownloadToken(token: String): String? {
        // 1) "same browser" nonce
        val nonce: NonceResponse = try {
            app.get("https://cinecloud.top/f/$token/n", headers = cineHeaders(token)).parsed()
        } catch (e: Exception) { return null }
        val n = nonce.n ?: return null
        // 2) unlock (empty Turnstile token works when enforcement is off)
        // NOTE: form fields go in `data` (request body). `params` are
        // URL query params — with params only, NiceHttp builds a POST
        // with a null body and OkHttp throws before any request is sent.
        val unlock: UnlockResponse = try {
            app.post(
                "https://cinecloud.top/f/$token/unlock",
                headers = cineHeaders(token),
                data = mapOf("ts" to "", "bc" to n)
            ).parsed()
        } catch (e: Exception) { return null }
        if (unlock.ok != true) return null
        val html = unlock.html ?: return null
        val serverDoc = Jsoup.parse(html)
        val all = serverDoc.select("a[href^=/f/]")
        val visible = all.filter { it.closest(".c5a7826") == null }
        val serverHrefs = (if (visible.isNotEmpty()) visible else all)
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
    // Single attempt (the caller retries at the loadLinks level).
    private suspend fun resolveWatchToken(token: String): String? {
        val play: PlayResponse = try {
            app.post(
                "https://cinecloud.top/f/$token",
                headers = cineHeaders(token),
                data = mapOf("a" to "play")
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
        val seenUrls = mutableSetOf<String>()
        // One stream per quality label: prefer the Watch token (a=play
        // is ONE call that returns the file URL directly), fall back to
        // the Download token (n -> unlock -> server 307) for the same
        // quality. This halves the request burst vs resolving every
        // token, and a stale download token (410) no longer kills a
        // quality whose watch token still works.
        val groups = (ep.links ?: emptyList()).groupBy { it.label }

        suspend fun emit(label: String, quality: Int, url: String): Boolean {
            if (!seenUrls.add(url)) return false
            callback(
                newExtractorLink(
                    source = "CineFreak",
                    name = "CineFreak $label",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = "https://cinecloud.top/"
                }
            )
            return true
        }

        // Resolves one quality group: watch token first (a=play is ONE
        // call that returns the file URL directly), download token as
        // fallback (n -> unlock -> server 307). Returns true if a
        // stream was emitted for this group.
        suspend fun resolveGroup(label: String, entries: List<LinkEntry>): Boolean {
            val quality = entries.firstOrNull()?.quality ?: Qualities.Unknown.value
            var direct: String? = null
            for (entry in entries) {
                if (entry.host != "watch") continue
                direct = resolveWatchToken(entry.id)
                if (direct != null) break
            }
            if (direct == null) {
                for (entry in entries) {
                    if (entry.host != "dl") continue
                    direct = resolveDownloadToken(entry.id)
                    if (direct != null) break
                }
            }
            return if (direct != null) emit(label, quality, direct) else false
        }

        var found = false
        // Pass 1 (fast): resolve every quality group.
        for ((label, entries) in groups) {
            if (resolveGroup(label, entries)) found = true
        }
        if (found) return true

        // Pass 2 (only if pass 1 found NOTHING — e.g. a short
        // Turnstile enforcement blip): wait, then resolve every group
        // again with fresh nonces. Thread.sleep, not coroutines delay:
        // kotlinx-coroutines is not on this module's compile classpath.
        Thread.sleep(5000)
        for ((label, entries) in groups) {
            if (resolveGroup(label, entries)) found = true
        }
        return found
    }
}
