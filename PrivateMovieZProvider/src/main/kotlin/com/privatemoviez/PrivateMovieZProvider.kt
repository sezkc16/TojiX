package com.privatemoviez

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
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

// PrivateMovieZ (b.privatemoviez.surf) — WordPress movie/series index,
// same family as HDHub4u / MoviesDrive / Vegamovies: Hindi-English dual
// audio + South dubs, BluRay/WEBRip 720p/1080p/2160p.
//
// Verified live 2026-11-21:
// - Homepage + ?s= search return server-rendered post cards (no
//   Cloudflare/Turnstile on plain fetches).
// - Post pages carry one quality section per heading ("—–==4K Lover==—–",
//   "—–==1080p Lover==—–", "—–==720p Lover==—–") with the download
//   buttons under it; the heading says "– GoogleDrive", i.e. the files
//   sit on Google Drive via the HubDrive/HubCloud family.
// - v2 fixes (phone-tested v1 bugs, 2026-11-21 02:19):
//   (a) Posters: the site lazy-loads images — `src` is a data: SVG
//       placeholder, the real URL is in `data-src`. v1 read `src` first
//       -> every Home card showed a broken image. v2 reads `data-src`
//       and strips the -300x450 size suffix (full-size image).
//   (b) Load: EVERY download button points to pmslink.lol/<token> (the
//       site's own shortener) — v1 did not know that host, dropped all
//       links, and load() returned nothing (IllegalStateException).
//       v2 classifies pmslink links and resolves the token page (302 to
//       the real host) in loadLinks. NOTE: pmslink sits behind a
//       Cloudflare managed challenge; it 403s from datacenter IPs, and
//       the phone test decides whether a residential/mobile IP passes.
//   (c) Description: the Storyline <p> lives in the sibling
//       .note-content div, not next to the <h3> — v1's sibling walk
//       found nothing. v2 reads .note-wrap > .note-content p; no
//       synopsis -> empty description (never fabricated).
// - v3 fixes (phone-tested v2 bug, 2026-11-21 02:33 — "No streams
//   found" on Play even though load() now works): the pmslink token
//   page is behind a Cloudflare MANAGED challenge. Plain HTTP 403s
//   from datacenter IPs, but the APP clears it: the engine's
//   ClearanceInterceptor detects the challenge and solves it in a
//   hidden WebView, storing a cf_clearance cookie per site
//   (verified in the engine: ClearanceInterceptor + WebClearanceStore
//   + RemoteWebViewHost.solveCloudflare). v2's resolver did ONE plain
//   app.get with the default timeout and gave up on 403 — and it
//   fired all three token fetches CONCURRENTLY via amap, so three
//   WebView solves for the same host contended. v3: (1) pmslink
//   tokens resolve SEQUENTIALLY in a pre-pass (first token warms
//   the clearance, the rest reuse it), (2) each token gets a retry
//   loop with a long per-attempt timeout (15s, then 10s) so the
//   hidden-WebView solve has time to finish, (3) a challenge page
//   (403 / "Just a moment" / __cf_chl) is retried instead of
//   returning null, (4) an explicit 3xx Location is honoured even
//   if the client did not follow it. Total stays inside the app's
//   ~30s loadLinks budget (pre-pass capped once 20s is spent).
class PrivateMovieZProvider : MainAPI() {

    override var mainUrl = "https://b.privatemoviez.surf"
    override var name = "PrivateMovieZ"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Episode data passed to loadLinks (as JSON string).
    // A LIST of links (not a map) so every quality variant shows
    // separately with its size, e.g. "1080p [3.2GB]" and "2160p [8.1GB]".
    // host: "hubcdn" = hubcdn.club/file/<id>, "hubdrive" =
    // hubdrive.blog/file/<id>, "hubcloud" = hubcloud drive code,
    // "gm" = greenmotors.club URL, "gdflix" = gdflix file id,
    // "gdrive" = direct drive.google.com file URL,
    // "archive" = mdrive.lol/archive/<id> page (fetch it in loadLinks
    // to find its HubCloud/GDFlix buttons),
    // "pmslink" = pmslink.lol/<token> — the site's own shortener
    // (verified live 2026-11-21: EVERY download button on every post
    // points here, e.g. https://pmslink.lol/b23fea8764/ for Scream 7
    // 2160p; v1 did not know this host, so every link was dropped and
    // load() returned nothing). The token page 302-redirects to the real
    // host (HubDrive/HubCloud/GreenMotors/...); resolve it in loadLinks.
    // NOTE: pmslink sits behind a Cloudflare managed challenge — from a
    // datacenter IP it 403s. On the phone the app's ClearanceInterceptor
    // solves the challenge in a hidden WebView and stores the clearance
    // per site, so v3 resolves pmslink tokens SEQUENTIALLY (one solve
    // warms the clearance for the rest) with a retry loop and long
    // per-attempt timeouts — see resolvePmsLinkTarget.
    data class LinkEntry(
        @JsonProperty("label") val label: String,
        @JsonProperty("host") val host: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("q") val quality: Int,
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

    // HubCloud moved hubcloud.pw -> hubcloud.mov (verified live
    // 2026-11-15 on HDHub4u). Accept any hubcloud TLD so the next
    // move does not break resolution again.
    private fun hubCloudCode(text: String): String? {
        return Regex("hubcloud\\.[a-z]+/drive/([A-Za-z0-9]+)").find(text)?.groupValues?.getOrNull(1)
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
    // "Hoppers (2026) Hin-Eng BluRay 720p | 1080p | 2160p" -> "Hoppers (2026) Hin-Eng BluRay"
    private fun displayTitle(raw: String): String {
        var t = raw
            .replace(Regex(" – PrivateMovieZ.*$"), "")
            .replace(Regex(" \\| PrivateMovieZ.*$"), "")
        val cut = Regex("\\b(480p|720p|1080p|2160p)\\b").find(t)
        if (cut != null) t = t.substring(0, cut.range.first)
        t = t.replace(Regex("\\s*\\[.*?\\]\\s*"), " ")
        return cleanText(t)
    }

    // Poster URL from a lazy-loaded <img>: the site's images are
    // lazy-loaded, so `src` is a data: SVG placeholder and the real URL
    // lives in `data-src` (v1 used `src` first -> every poster was the
    // placeholder -> broken-image cards on Home). Strip the WP size
    // suffix (-300x450) so the full-size image is used.
    private fun posterUrl(img: org.jsoup.nodes.Element?): String? {
        val raw = img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: img?.attr("src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            ?: return null
        val full = raw.replace(Regex("-\\d+x\\d+(?=\\.[A-Za-z]{3,4}(?:\\?|$))"), "")
        return full.takeIf { it.startsWith("http") }
    }

    // Parse a listing page (home / category / search) into search responses.
    // Post cards link to /<slug>-download.../ with a TMDB poster <img>
    // and the title text inside the anchor (or in a sibling heading).
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("a[href*=-download]")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            var title = cleanText(a.text())
            if (title.isEmpty()) title = a.attr("title").trim()
            if (title.isEmpty()) {
                // Image-only card link: the title sits in a sibling heading.
                title = cleanText(a.parent()?.selectFirst("h1, h2, h3, h4")?.text() ?: "")
            }
            if (title.isEmpty()) continue
            val img = a.selectFirst("img")
                ?: a.parent()?.selectFirst("img")
            val poster = posterUrl(img)
            val display = displayTitle(title)
            if (display.isEmpty()) continue
            val isSeries = display.contains("Season", true)
                || Regex("(?i)\\bS\\d{2}\\b").containsMatchIn(display)
                || url.contains("-s0", true)
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

    // Category slugs verified via the site's WP API (wp-json/wp/v2/categories).
    override val mainPage = mainPageOf(
        "" to "Latest",
        "bollywood" to "Bollywood",
        "hollywood" to "Hollywood",
        "action" to "Action",
        "adventure" to "Adventure",
        "comedy" to "Comedy",
        "drama" to "Drama",
        "animation" to "Animation",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // ?s= is server-rendered on this site (verified live 2026-11-21:
        // /?s=Hoppers returns the Hoppers posts). Standard WP pagination.
        val q = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/?s=$q&paged=$page"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    // The plot: the "Storyline" section. The heading is
    // <h3 class="gb-heading">Storyline:</h3> inside .note-header, and the
    // synopsis <p> lives in the SIBLING .note-content div inside the same
    // .note-wrap — v1 walked nextElementSibling() off the <h3> and found
    // nothing, so the detail page showed "No description available".
    // If a post has no Storyline section, return null (empty description
    // — never fabricate, and never use the og:description download blurb).
    private fun parsePlot(doc: org.jsoup.nodes.Document): String? {
        val h = doc.select("h3:contains(Storyline)").firstOrNull()
        if (h != null) {
            val wrap = h.parents().firstOrNull { it.hasClass("note-wrap") }
            val p = wrap?.selectFirst(".note-content p")?.text()?.let { cleanText(it) }
            if (!p.isNullOrEmpty() && p.length >= 20) return p
        }
        // Regex fallback on the raw HTML (same structure).
        val m = Regex("(?is)Storyline:</h3>.*?<p[^>]*>(.*?)</p>")
            .find(doc.html())
        val text = m?.groupValues?.getOrNull(1)?.let { cleanText(Jsoup.parse(it).text()) }
        if (!text.isNullOrEmpty() && text.length >= 20) return text
        return null
    }

    // Quality of the "Lover" section a position in the raw HTML sits in:
    // walk backwards for the nearest "—–==1080p Lover==—–" style heading.
    private fun qualityAtPosition(html: String, pos: Int): String {
        val head = Regex("(?i)(2160p|4K|1080p|720p|480p)\\s*Lover").findAll(html)
            .filter { it.range.first < pos }.lastOrNull()
            ?: return ""
        return head.groupValues[1]
    }

    // Classify one URL into a LinkEntry (host + id), or null if it is not
    // a known download host. Quality comes from the button text first,
    // falling back to the surrounding "Lover" section heading.
    private fun classifyLink(href: String, label: String, fallbackQuality: String): LinkEntry? {
        val qLabel = if (Regex("(?i)(480p|720p|1080p|2160p|4K)").containsMatchIn(label)) label
            else fallbackQuality
        val q = qualityValue(qLabel)
        val cleanLabel = cleanText(label).ifEmpty { qLabel.ifEmpty { "Download" } }
        return when {
            href.contains("hubdrive.blog/file/") -> {
                val id = href.substringAfter("hubdrive.blog/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isEmpty()) null else LinkEntry(cleanLabel, "hubdrive", id, q)
            }
            href.contains("hubcdn.club/file/") -> {
                val id = href.substringAfter("hubcdn.club/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isEmpty()) null else LinkEntry(cleanLabel, "hubcdn", id, q)
            }
            href.contains("hubcloud.") && href.contains("/drive/") -> {
                val code = href.substringAfter("/drive/").substringBefore("/").substringBefore("?").trim()
                if (code.isEmpty()) null else LinkEntry(cleanLabel, "hubcloud", code, q)
            }
            href.contains("greenmotors.club") -> {
                LinkEntry(cleanLabel, "gm", href, q)
            }
            href.contains("gdflix.") && href.contains("/file/") -> {
                val id = href.substringAfter("/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isEmpty()) null else LinkEntry(cleanLabel, "gdflix", id, q)
            }
            href.contains("only-apis.com/file/") -> {
                val id = href.substringAfter("/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isEmpty()) null else LinkEntry(cleanLabel, "gdflix", id, q)
            }
            href.contains("drive.google.com/file/d/") -> {
                val id = href.substringAfter("/file/d/").substringBefore("/").substringBefore("?").trim()
                if (id.isEmpty()) null else LinkEntry(cleanLabel, "gdrive", href, q)
            }
            href.contains("mdrive.lol/archive/") -> {
                LinkEntry(cleanLabel, "archive", href, q)
            }
            href.contains("pmslink.lol/") -> {
                LinkEntry(cleanLabel, "pmslink", href, q)
            }
            else -> null
        }
    }

    // Collect every download link on a post page.
    // 1) Anchor scan: every <a href> whose button text carries a quality
    //    token, or which sits under a "Lover" quality heading.
    // 2) Raw-HTML regex sweep for the known host URL patterns (covers
    //    links embedded in JS / data attributes with no plain anchor).
    private fun collectLinks(doc: org.jsoup.nodes.Document, html: String): List<LinkEntry> {
        val links = mutableListOf<LinkEntry>()
        val seen = mutableSetOf<String>()
        fun add(entry: LinkEntry?) {
            if (entry == null) return
            if (seen.add("${entry.host}:${entry.id}")) links.add(entry)
        }
        // (1) Anchor scan, tracking the current "Lover" section.
        var currentQuality = ""
        for (el in doc.body()?.allElements ?: emptyList()) {
            val text = cleanText(el.ownText())
            val headMatch = Regex("(?i)^(.*?)\\s*(2160p|4K|1080p|720p|480p)\\s*Lover").find(text)
            if (headMatch != null) currentQuality = headMatch.groupValues[2]
            if (el.tagName() != "a") continue
            val href = el.attr("href")
            if (href.isEmpty()) continue
            val btnText = cleanText(el.text())
            val hasQuality = Regex("(?i)(480p|720p|1080p|2160p|4K)").containsMatchIn(btnText)
            if (!hasQuality && currentQuality.isEmpty()) continue
            add(classifyLink(href, btnText, currentQuality))
        }
        // (2) Raw-HTML regex sweep.
        val patterns = listOf(
            Regex("https?://hubdrive\\.blog/file/(\\d+)") to "hubdrive",
            Regex("https?://hubcdn\\.club/file/(\\d+)") to "hubcdn",
            Regex("https?://hubcloud\\.[a-z]+/drive/([A-Za-z0-9]+)") to "hubcloud",
            Regex("https?://greenmotors\\.club[^\"'\\s<>]+") to "gm",
            Regex("https?://gdflix\\.[a-z]+/file/([A-Za-z0-9]+)") to "gdflix",
            Regex("https?://only-apis\\.com/file/([A-Za-z0-9]+)") to "gdflix",
            Regex("https?://drive\\.google\\.com/file/d/([A-Za-z0-9_-]+)") to "gdrive",
            Regex("https?://mdrive\\.lol/archive/(\\d+)/?") to "archive",
            Regex("https?://pmslink\\.lol/[A-Za-z0-9]+/?") to "pmslink",
        )
        for ((re, host) in patterns) {
            for (m in re.findAll(html)) {
                val raw = m.value
                val q = qualityAtPosition(html, m.range.first)
                val entry = when (host) {
                    "gm" -> LinkEntry(q.ifEmpty { "Download" }, "gm", raw, qualityValue(q))
                    "gdrive" -> LinkEntry(q.ifEmpty { "Download" }, "gdrive", raw, qualityValue(q))
                    "archive" -> LinkEntry(q.ifEmpty { "Download" }, "archive", raw, qualityValue(q))
                    "pmslink" -> LinkEntry(q.ifEmpty { "Download" }, "pmslink", raw, qualityValue(q))
                    else -> classifyLink(raw, q, q)
                }
                add(entry)
            }
        }
        return links
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("title")?.text()?.trim() ?: ""
        val title = displayTitle(rawTitle)
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            ?: posterUrl(doc.select("img[data-src*=wp-content/uploads]").firstOrNull())
            ?: posterUrl(doc.select("img[src*=wp-content/uploads]").firstOrNull())
        val plot = parsePlot(doc)
        val isSeries = title.contains("Season", true)
            || Regex("(?i)\\bS\\d{2}\\b").containsMatchIn(title)
            || url.contains("-s0", true)

        val links = collectLinks(doc, html)
        if (links.isEmpty()) return null

        if (isSeries) {
            return loadSeries(title, url, doc, html, links, poster, plot)
        }
        val dataJson = toJson(EpisodeData(links = links)) ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // Series: per-episode if the page has "EPISODE N" markers (links in
    // each segment belong to that episode, HDHub4u pattern); otherwise
    // every quality link is a season pack. HubDrive pack pages
    // (hubdrive.blog/packs/<slug>) are expanded per episode.
    private suspend fun loadSeries(
        title: String,
        url: String,
        doc: org.jsoup.nodes.Document,
        html: String,
        links: List<LinkEntry>,
        poster: String?,
        plot: String?,
    ): LoadResponse? {
        val episodes = mutableListOf<Episode>()
        val bodyHtml = doc.body()?.html() ?: html
        val epSplit = Regex("(?i)EPiSODE\\s*(\\d+)").findAll(bodyHtml).toList()
        if (epSplit.isNotEmpty()) {
            for ((idx, m) in epSplit.withIndex()) {
                val epNum = m.groupValues[1].toIntOrNull() ?: (idx + 1)
                val segStart = m.range.last
                val segEnd = if (idx + 1 < epSplit.size) epSplit[idx + 1].range.first else bodyHtml.length
                val seg = bodyHtml.substring(segStart, segEnd)
                val segDoc = Jsoup.parse(seg)
                val epLinks = collectLinks(segDoc, seg)
                if (epLinks.isEmpty()) continue
                val dataJson = toJson(EpisodeData(links = epLinks)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }
        }
        if (episodes.isEmpty()) {
            // Season packs: one entry per quality link (plus any
            // hubdrive pack page expanded per episode).
            val packUrls = Regex("https?://hubdrive\\.blog/packs/([A-Za-z0-9\\-_]+)")
                .findAll(html).map { it.value }.distinct().toList()
            val byEpisode = sortedMapOf<Int, MutableList<LinkEntry>>()
            val usedIds = mutableSetOf<String>()
            for (packUrl in packUrls) {
                fetchHubDrivePack(packUrl, byEpisode, usedIds)
            }
            for ((epNum, epLinks) in byEpisode) {
                val dataJson = toJson(EpisodeData(links = epLinks)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }
            if (episodes.isEmpty()) {
                for (link in links) {
                    val label = if (link.label.contains("Pack", true)) link.label
                        else "Season Pack (${link.label})"
                    val dataJson = toJson(EpisodeData(links = listOf(link))) ?: continue
                    episodes.add(newEpisode(dataJson) {
                        this.name = label
                        this.posterUrl = poster
                    })
                }
            }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // Fetch one hubdrive.blog/packs/<slug> page (a file listing whose
    // rows are <div class="pack-clean-file"> with
    // .pack-clean-title = "Show.S01E01.480p...mkv",
    // .pack-clean-size = "171.55 MB" and the file id in the row's
    // onclick "window.open('/file/<id>', '_blank')") and merge its
    // per-episode hubdrive file links into byEpisode (keyed by episode
    // number). (Ported from HDHub4uProvider, verified pattern.)
    private suspend fun fetchHubDrivePack(
        packUrl: String,
        byEpisode: MutableMap<Int, MutableList<LinkEntry>>,
        usedIds: MutableSet<String>,
    ) {
        val html = try {
            app.get(packUrl, headers = browserHeaders).text
        } catch (e: Exception) { return }
        val doc = Jsoup.parse(html)
        for (row in doc.select("div.pack-clean-file")) {
            val filename = row.selectFirst(".pack-clean-title")?.text()?.trim() ?: continue
            val size = row.selectFirst(".pack-clean-size")?.text()?.trim() ?: ""
            var id = Regex("/file/(\\d+)").find(row.attr("onclick"))?.groupValues?.getOrNull(1) ?: ""
            if (id.isEmpty()) {
                id = row.selectFirst("a[href*=/file/]")?.attr("href")
                    ?.substringAfter("/file/")?.substringBefore("/")?.substringBefore("?")?.trim() ?: ""
            }
            if (id.isEmpty() || id in usedIds) continue
            val epNum = Regex("(?i)S\\d+E(\\d+)").find(filename)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("(?i)E(\\d+)").find(filename)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            usedIds.add(id)
            val qToken = Regex("(?i)(2160|1080|720|480)p").find(filename)?.value ?: "720p"
            val label = if (size.isNotEmpty()) "$qToken [$size]" else qToken
            byEpisode.getOrPut(epNum) { mutableListOf() }
                .add(LinkEntry(label, "hubdrive", id, qualityValue(qToken)))
        }
    }

    // ---- Link resolvers (ported from HDHub4uProvider / MoviesDriveProvider,
    // same HubCloud family, verified live) ----

    private fun b64decode(input: String): String? {
        return try {
            val padded = input + "=".repeat((4 - input.length % 4) % 4)
            String(JavaBase64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

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

    // Resolve one greenmotors button to the direct video file(s).
    private suspend fun resolveGmEntry(gmUrl: String): List<HubServer> {
        val target = resolveGreenMotors(gmUrl) ?: return emptyList()
        hubCloudCode(target)?.let { return resolveHubCloudServers(it) }
        return when {
            target.contains("hubdrive.blog/file/") -> {
                val id = target.substringAfter("hubdrive.blog/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isEmpty()) emptyList() else resolveHubDrive(id)
            }
            target.contains("hblinks.lol/") -> {
                val html = try {
                    app.get(target, headers = browserHeaders).text
                } catch (e: Exception) { return emptyList() }
                val code = hubCloudCode(html)
                if (code != null) return resolveHubCloudServers(code)
                val fid = Regex("hubdrive\\.blog/file/(\\d+)").find(html)?.groupValues?.getOrNull(1)
                if (fid != null) return resolveHubDrive(fid)
                emptyList()
            }
            target.startsWith("http") -> listOf(HubServer("HubCloud", target))
            else -> emptyList()
        }
    }

    // hubcdn.club/file/<id> -> JS redirect page with a reurl:
    // inventoryidea.com/?r=<b64 of hubcdn dl base>/<b64 of "link=<R2 URL>">
    // The R2 URL is the direct video file.
    private suspend fun resolveHubCdn(fileId: String): String? {
        val html = try {
            app.get("https://hubcdn.club/file/$fileId", headers = browserHeaders).text
        } catch (e: Exception) { return null }
        val reurl = Regex("reurl\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1) ?: return null
        val parts = reurl.substringAfter("?r=", "").split("/")
        if (parts.size < 2) return null
        val decoded = b64decode(parts[1]) ?: return null
        return decoded.removePrefix("link=").trim().takeIf { it.startsWith("http") }
    }

    // hubdrive.blog/file/<id> -> HubDrive page with a hubcloud.<tld>/drive/<code>
    // link, resolved by the same HubCloud chain as HDHub4u.
    private suspend fun resolveHubDrive(fileId: String): List<HubServer> {
        val html = try {
            app.get("https://hubdrive.blog/file/$fileId", headers = browserHeaders).text
        } catch (e: Exception) { return emptyList() }
        val code = hubCloudCode(html) ?: return emptyList()
        return resolveHubCloudServers(code)
    }

    // mdrive.lol/archive/<id> page: one HubCloud button + one GDFlix
    // button (image buttons; match by href domain, not by text).
    private suspend fun fetchArchiveLinks(archiveUrl: String): List<LinkEntry> {
        val html = try {
            app.get(archiveUrl, headers = browserHeaders).text
        } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<LinkEntry>()
        Regex("hubcloud\\.[a-z]+/drive/([A-Za-z0-9]+)")
            .findAll(html).map { it.groupValues[1] }.distinct().forEach { code ->
                out.add(LinkEntry("HubCloud", "hubcloud", code, Qualities.Unknown.value))
            }
        Regex("(?:gdflix\\.[a-z]+|only-apis\\.com)/file/([A-Za-z0-9]+)")
            .findAll(html).map { it.groupValues[1] }.distinct().forEach { id ->
                out.add(LinkEntry("GDFlix", "gdflix", id, Qualities.Unknown.value))
            }
        return out
    }

    // One resolved HubCloud server: the actual server name (FSL, FSLv2,
    // PixelDrain, 10G) + its direct playable file URL.
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
    private suspend fun resolveHubCloudServers(hubCode: String): List<HubServer> {
        val hubUrl = "https://hubcloud.ist/drive/$hubCode"
        val hubHtml = try { app.get(hubUrl, headers = browserHeaders).text } catch (e: Exception) { return emptyList() }
        val gamerUrl = Regex("https?://gamerxyt\\.com/hubcloud\\.php\\?host=hubcloud&id=[A-Za-z0-9]+&token=[A-Za-z0-9]+")
            .find(hubHtml)?.value ?: return emptyList()
        val gamerHtml = try { app.get(gamerUrl, headers = browserHeaders).text } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<HubServer>()
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
        // Fallback: first R2 URL as a single "HubCloud" entry.
        Regex("https?://[^\"' ]+\\.r2\\.cloudflarestorage\\.com/[^\"' ]+")
            .find(gamerHtml)?.value?.let { add("HubCloud", it); return out }
        return out
    }

    // Emit one source per pre-resolved HubCloud server (FSL, FSLv2,
    // PixelDrain, 10G) so the app shows WHICH server a link is.
    private suspend fun emitHubCloudServers(
        label: String,
        quality: Int,
        servers: List<HubServer>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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

    // pmslink.lol/<token> -> the real host URL. The token page
    // 302-redirects to the real host (HubDrive / HubCloud / GreenMotors /
    // ...). The token page sits behind a Cloudflare MANAGED challenge:
    // plain HTTP gets a 403 "Just a moment..." page from datacenter IPs,
    // but on the phone the app's ClearanceInterceptor detects the
    // challenge and solves it in a hidden WebView, storing a
    // cf_clearance cookie for pmslink.lol — so the retry has to give
    // that solve time to finish. Retries: attempt 1 with a 15s
    // timeout (the solve usually needs 3-8s), then a 3s pause and
    // attempt 2 with 10s (the clearance is stored by then, so it is
    // fast). A challenge page is retried, never treated as a target.
    // Returns the final URL after redirects, or a HubCloud drive URL
    // when a code is embedded in the page; null when the challenge
    // never clears.
    private suspend fun resolvePmsLinkTarget(tokenUrl: String, retries: Int = 2): String? {
        for (attempt in 0 until retries) {
            val timeout = if (attempt == 0) 15L else 10L
            val resp = try {
                app.get(tokenUrl, headers = browserHeaders, timeout = timeout)
            } catch (e: Exception) {
                if (attempt + 1 < retries) Thread.sleep(3000)
                continue
            }
            // A 3xx Location is the redirect target even if the client
            // did not follow it.
            val loc = resp.headers["Location"] ?: resp.headers["location"] ?: ""
            if (loc.startsWith("http") && !loc.contains("pmslink.lol")) return loc
            // Final URL after redirects (OkHttp follows them by default).
            val finalUrl = try {
                resp.okhttpResponse.request.url.toString()
            } catch (e: Exception) { "" }
            if (finalUrl.isNotEmpty() && !finalUrl.contains("pmslink.lol")) return finalUrl
            // Still on pmslink: read the (small) body to tell a
            // challenge page from a page that embeds the target.
            val body = try { resp.text } catch (e: Exception) { "" }
            val challenged = resp.code == 403 || resp.code == 503
                || finalUrl.contains("__cf_chl")
                || body.contains("Just a moment")
                || body.contains("challenges.cloudflare.com")
                || body.contains("_cf_chl_opt")
            if (!challenged) {
                val code = hubCloudCode(body)
                if (code != null) return "https://hubcloud.ist/drive/$code"
                // A 200 that is neither a redirect nor a challenge and
                // has no hubcloud code: nothing more to get here.
                return null
            }
            if (attempt + 1 < retries) Thread.sleep(3000)
        }
        return null
    }

    // Emit links for an already-resolved target URL (a pmslink landing,
    // or a greenmotors target). Mirrors the dispatch in resolveGmEntry.
    private suspend fun emitTarget(
        label: String,
        quality: Int,
        target: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        hubCloudCode(target)?.let {
            if (emitHubCloudServers(label, quality, resolveHubCloudServers(it), callback)) found = true
            return found
        }
        when {
            target.contains("hubdrive.blog/file/") -> {
                val id = target.substringAfter("hubdrive.blog/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isNotEmpty() && emitHubCloudServers(label, quality, resolveHubDrive(id), callback)) found = true
            }
            target.contains("hubcdn.club/file/") -> {
                val id = target.substringAfter("hubcdn.club/file/").substringBefore("/").substringBefore("?").trim()
                val direct = if (id.isEmpty()) null else resolveHubCdn(id)
                if (direct != null) {
                    callback(
                        newExtractorLink(
                            source = "HubCdn",
                            name = "HubCdn $label",
                            url = direct,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.referer = "https://hubcdn.club/"
                        }
                    )
                    found = true
                }
            }
            target.contains("greenmotors.club") -> {
                if (emitHubCloudServers(label, quality, resolveGmEntry(target), callback)) found = true
            }
            target.contains("hblinks.lol/") -> {
                val html = try { app.get(target, headers = browserHeaders).text } catch (e: Exception) { "" }
                val code = hubCloudCode(html)
                if (code != null) {
                    if (emitHubCloudServers(label, quality, resolveHubCloudServers(code), callback)) found = true
                } else {
                    val fid = Regex("hubdrive\\.blog/file/(\\d+)").find(html)?.groupValues?.getOrNull(1)
                    if (fid != null && emitHubCloudServers(label, quality, resolveHubDrive(fid), callback)) found = true
                }
            }
            target.contains("gdflix.") || target.contains("only-apis.com/file/") -> {
                val id = target.substringAfter("/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isNotEmpty() && loadExtractor("https://gdflix.vip/file/$id", subtitleCallback, callback)) found = true
            }
            target.contains("drive.google.com/file/d/") -> {
                if (loadExtractor(target, subtitleCallback, callback)) found = true
            }
            target.contains("mdrive.lol/archive/") -> {
                for (sub in fetchArchiveLinks(target)) {
                    when (sub.host) {
                        "hubcloud" -> {
                            val servers = resolveHubCloudServers(sub.id)
                            if (emitHubCloudServers(label, quality, servers, callback)) found = true
                        }
                        "gdflix" -> {
                            if (loadExtractor("https://gdflix.vip/file/${sub.id}", subtitleCallback, callback)) found = true
                        }
                    }
                }
            }
            target.startsWith("http") -> {
                callback(
                    newExtractorLink(
                        source = "HubCloud",
                        name = "HubCloud $label",
                        url = target,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = "https://hubcloud.pw/"
                    }
                )
                found = true
            }
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false
        var found = false
        val entries = ep.links ?: return false

        // pmslink tokens resolve SEQUENTIALLY first, before the
        // concurrent pass below. The token page is behind a Cloudflare
        // managed challenge, which the app clears in a hidden WebView
        // and stores per-site — three concurrent solves for the same
        // host contend and can all fail, so the first token warms the
        // clearance and the rest reuse it. The pre-pass is capped: once
        // 20s is spent, further tokens are left unresolved (null) so the
        // rest of loadLinks still fits the app's ~30s budget.
        val pmsTargets = mutableMapOf<String, String?>()
        val pmsStart = System.currentTimeMillis()
        for (entry in entries) {
            if (entry.host != "pmslink") continue
            if (entry.id in pmsTargets) continue
            if (System.currentTimeMillis() - pmsStart > 20_000) {
                pmsTargets[entry.id] = null
                continue
            }
            pmsTargets[entry.id] = resolvePmsLinkTarget(entry.id)
        }

        // Resolve every entry concurrently: each one costs several HTTP
        // hops, and resolving them one after another regularly blows the
        // app's 30s loadLinks timeout. amap is CloudStream's own
        // concurrent map (no kotlinx import needed). pmslink entries
        // use the pre-resolved targets above (no second fetch).
        data class ResolvedEntry(
            val hubCdn: String?,
            val servers: List<HubServer>,
            val subs: List<LinkEntry>,
            val target: String? = null,
        )

        val resolved: List<ResolvedEntry> = entries.amap { entry ->
            when (entry.host) {
                "hubcdn" -> ResolvedEntry(resolveHubCdn(entry.id), emptyList(), emptyList())
                "hubdrive" -> ResolvedEntry(null, resolveHubDrive(entry.id), emptyList())
                "gm" -> ResolvedEntry(null, resolveGmEntry(entry.id), emptyList())
                "hubcloud" -> ResolvedEntry(null, resolveHubCloudServers(entry.id), emptyList())
                "archive" -> ResolvedEntry(null, emptyList(), fetchArchiveLinks(entry.id))
                "pmslink" -> ResolvedEntry(null, emptyList(), emptyList(), pmsTargets[entry.id])
                else -> ResolvedEntry(null, emptyList(), emptyList())
            }
        }

        entries.zip(resolved).forEach { (entry, res) ->
            when (entry.host) {
                "hubcdn" -> {
                    val direct = res.hubCdn ?: return@forEach
                    callback(
                        newExtractorLink(
                            source = "HubCdn",
                            name = "HubCdn ${entry.label}",
                            url = direct,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = entry.quality
                            this.referer = "https://hubcdn.club/"
                        }
                    )
                    found = true
                }
                "hubdrive", "gm", "hubcloud" -> {
                    if (emitHubCloudServers(entry.label, entry.quality, res.servers, callback)) found = true
                }
                "archive" -> {
                    for (sub in res.subs) {
                        when (sub.host) {
                            "hubcloud" -> {
                                val servers = resolveHubCloudServers(sub.id)
                                if (emitHubCloudServers(entry.label, entry.quality, servers, callback)) found = true
                            }
                            "gdflix" -> {
                                if (loadExtractor("https://gdflix.vip/file/${sub.id}", subtitleCallback, callback)) found = true
                            }
                        }
                    }
                }
                "gdflix" -> {
                    if (loadExtractor("https://gdflix.vip/file/${entry.id}", subtitleCallback, callback)) found = true
                }
                "gdrive" -> {
                    // Direct Google Drive file: CloudStream's built-in
                    // GDrive extractor handles it.
                    if (loadExtractor(entry.id, subtitleCallback, callback)) found = true
                }
                "pmslink" -> {
                    val target = res.target ?: return@forEach
                    if (emitTarget(entry.label, entry.quality, target, subtitleCallback, callback)) found = true
                }
            }
        }

        return found
    }
}
