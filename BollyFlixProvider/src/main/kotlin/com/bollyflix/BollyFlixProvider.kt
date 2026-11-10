package com.bollyflix

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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class BollyFlixProvider : MainAPI() {

    override var mainUrl = "https://bollyflix.church"
    override var name = "BollyFlix"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Episode data passed to loadLinks (as JSON string).
    // A LIST of links (not a map) so every quality variant shows
    // separately with its size, e.g. "480p [630MB]" and
    // "1080p [3.6GB]" are two different entries.
    // host: "fastdl" = dl.fastdlserver.site/?id=<b64> (302s to a GDFlix
    //   file URL; resolve the redirect, then loadExtractor),
    //   "linksmod" = linksmod.top/view/<id> (fetch the page, collect
    //   its mirror hrefs, loadExtractor each).
    data class LinkEntry(
        @JsonProperty("label") val label: String,
        @JsonProperty("host") val host: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("q") val quality: Int,
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

    private fun cleanText(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    // Display title: strip the "Download " prefix and the
    // " | BollyFlix" suffix, cut at the first quality token
    // (480p/720p/1080p/2160p), strip bracket groups, collapse
    // whitespace.
    // "Download Jailer 2 (2026) Dual Audio {Hindi-Tamil} Movie 480p |
    //  720p | 1080p HDTC | BollyFlix" -> "Jailer 2 (2026) Dual Audio
    //  {Hindi-Tamil} Movie"
    private fun displayTitle(raw: String): String {
        var t = raw
            .replace(Regex("^Download\\s+"), "")
            .replace(Regex(" – BollyFlix.*$"), "")
            .replace(Regex(" \\| BollyFlix.*$"), "")
        val cut = Regex("\\b(480p|720p|1080p|2160p)\\b").find(t)
        if (cut != null) t = t.substring(0, cut.range.first)
        t = t.replace(Regex("\\s*\\[.*?\\]\\s*"), " ")
        t = t.replace(Regex("\\s*\\{.*?\\}\\s*"), " ")
        return cleanText(t)
    }

    private fun isSeriesUrlOrTitle(url: String, title: String): Boolean {
        return url.contains("-series/") || url.contains("/web-series/") ||
            title.contains("Season", true) || title.contains("WEB Series", true)
    }

    // Listing: <article class="latestPost"> cards (NOTE: the <article>
    // has id= BEFORE class=, so select by class only). Title from the
    // <a> title attr (or text), poster from <img src> (bflixcdn.xyz).
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.latestPost")) {
            val a = article.selectFirst("a[href]") ?: continue
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (url.contains("/page/")) continue
            if (!seen.add(url)) continue
            val img = a.selectFirst("img")
            val poster = img?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            var rawTitle = a.attr("title").trim()
            if (rawTitle.isEmpty()) rawTitle = cleanText(a.text())
            if (rawTitle.isEmpty()) continue
            val title = displayTitle(rawTitle)
            if (title.isEmpty()) continue
            if (isSeriesUrlOrTitle(url, title)) {
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        "" to "Latest",
        "movies/bollywood" to "Bollywood",
        "movies/hollywood" to "Hollywood",
        "movies/dual-audio-movies" to "Dual Audio",
        "movies/hindi-dubbed-movies-480p-720p" to "Hindi Dubbed",
        "movies/south-hindi-dubbed" to "South Hindi",
        "web-series" to "Web Series",
        "anime" to "Anime",
    )

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

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // WordPress search; /?s=<q> 302s to /search/<q> (follows by
        // default). NOTE: the search page HTML contains literal
        // "No results" strings in the template even when results
        // exist — parseListing counts the cards, never string-matches.
        val q = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("title")?.text()?.trim() ?: ""
        val title = displayTitle(rawTitle)
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.select("img[src*=bflixcdn.xyz]").firstOrNull()?.attr("src")
        val isSeries = isSeriesUrlOrTitle(url, title)

        if (isSeries) {
            return loadSeries(title, url, doc, poster)
        } else {
            return loadMovie(title, url, doc, poster)
        }
    }

    // Movie detail: each quality is an <h5> with a green span, e.g.
    // "Jailer 2 (2026) {Hindi-Tamil} 480p [630MB]", followed by a <p>
    // with TWO <a class="dl"> buttons: dl.fastdlserver.site/?id=<b64>
    // &type=file ("Google Drive") and linksmod.top/view/<id>
    // ("Download Links"). One LinkEntry per (quality, host).
    private suspend fun loadMovie(title: String, url: String, doc: org.jsoup.nodes.Document, poster: String?): LoadResponse? {
        val links = mutableListOf<LinkEntry>()
        for (h5 in doc.select("h5")) {
            val heading = cleanText(h5.text())
            val qMatch = Regex("(?i)(2160|1080|720|480)p").find(heading) ?: continue
            val qualityToken = qMatch.value
            val size = Regex("\\[([0-9.]+ ?[GM]B)\\]").find(heading)?.groupValues?.getOrNull(1) ?: ""
            val label = if (size.isNotEmpty()) "$qualityToken [$size]" else qualityToken
            val q = qualityValue(qualityToken)
            // Walk forward siblings until the next h5, collecting the
            // <a class="dl"> buttons in the following <p>.
            var sib = h5.nextElementSibling()
            while (sib != null && sib.tagName() != "h5") {
                for (a in sib.select("a.dl[href]")) {
                    val href = a.attr("href")
                    if (href.isEmpty()) continue
                    when {
                        href.contains("fastdlserver") -> {
                            links.add(LinkEntry("$label GDFlix", "fastdl", href, q))
                        }
                        href.contains("linksmod") -> {
                            links.add(LinkEntry("$label Mirrors", "linksmod", href, q))
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

    // Series detail: each part is an <h4>, e.g.
    // "Ramayana (Season 1) Hindi Part 1 [550MB/E]", followed by an
    // <a class="maxbutton-download-links" href="https://fxlinks.rest
    // /elinks/<id>/">. Each fxlinks page lists per-episode fastdlserver
    // links ("Episode 01".."Episode 25") + a "Season Zip" link.
    // One HTTP fetch per part (same pattern as HiCine's per-group fetch).
    private suspend fun loadSeries(title: String, url: String, doc: org.jsoup.nodes.Document, poster: String?): LoadResponse? {
        val episodes = mutableListOf<Episode>()
        var currentPart: Int? = null
        for (el in doc.select("h4, a.maxbutton-download-links")) {
            if (el.tagName() == "h4") {
                val text = cleanText(el.text())
                currentPart = Regex("(?i)Part\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            } else {
                val fxUrl = el.attr("href")
                val part = currentPart ?: continue
                if (fxUrl.isEmpty()) continue
                if (!fxUrl.contains("fxlinks.")) continue
                fetchFxlinksPart(fxUrl, part, episodes)
            }
        }
        if (episodes.isEmpty()) return null
        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    // Fetch one part's fxlinks page (fxlinks.rest 302s to fxlinks.fit)
    // and add one episode per fastdlserver link it lists.
    // The part number becomes the CloudStream season, so episode
    // numbers (which restart at 01 in every part) stay distinct.
    private suspend fun fetchFxlinksPart(fxUrl: String, part: Int, episodes: MutableList<Episode>) {
        val html = try {
            app.get(fxUrl, headers = browserHeaders).text
        } catch (e: Exception) { return }
        val doc = Jsoup.parse(html)
        for (a in doc.select("a[href*=fastdlserver]")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val text = cleanText(a.text())
            val epMatch = Regex("(?i)Episode\\s*0*(\\d+)").find(text)
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                val dataJson = toJson(EpisodeData(links = listOf(
                    LinkEntry("Part $part – Episode $epNum", "fastdl", href, Qualities.Unknown.value)
                ))) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Part $part – Episode $epNum"
                    this.episode = epNum
                    this.season = part
                })
            } else if (text.contains("Season Zip", true)) {
                val dataJson = toJson(EpisodeData(links = listOf(
                    LinkEntry("Part $part – Season Zip", "fastdl", href, Qualities.Unknown.value)
                ))) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Part $part – Season Zip"
                    this.season = part
                })
            }
        }
    }

    // ---- Link resolvers ----

    // fastdlserver URL 302s to a GDFlix file URL (domain rotates;
    // the file id is stable). Read the Location without following it.
    // If there is no Location, hand loadExtractor the fastdlserver URL
    // itself — the extractor follows the redirect on its own.
    private suspend fun resolveFastDl(fastDlUrl: String): String {
        return try {
            val resp = app.get(fastDlUrl, headers = browserHeaders, allowRedirects = false)
            val loc = resp.headers["Location"] ?: resp.headers["location"]
            if (!loc.isNullOrEmpty() && loc.startsWith("http")) loc else fastDlUrl
        } catch (e: Exception) { fastDlUrl }
    }

    // Mirror hosts that appear on linksmod.top mirror pages.
    private val mirrorHosts = listOf(
        "gofile.io", "mixdrop.ag", "mxdrop.click", "1fichier.com",
        "megaup.net", "dailyuploads.net", "vikingfile.com",
        "vik1ngfile.site", "multiup.io", "uploadmaza.com",
    )

    // Fetch a linksmod page and return its mirror hrefs.
    // Some linksmod pages are an "Unlock Links" captcha gate with NO
    // mirrors at all — those return an empty list and are skipped.
    private suspend fun fetchLinksmodMirrors(linksmodUrl: String): List<String> {
        val html = try {
            app.get(linksmodUrl, headers = browserHeaders).text
        } catch (e: Exception) { return emptyList() }
        val doc = Jsoup.parse(html)
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            if (mirrorHosts.none { href.contains(it) }) continue
            if (!seen.add(href)) continue
            out.add(href)
        }
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

        ep.links?.forEach { entry ->
            when (entry.host) {
                "fastdl" -> {
                    // Best effort: GDFlix is Cloudflare-guarded for plain
                    // HTTP; CloudStream's extractor handles it when it can.
                    val target = resolveFastDl(entry.id)
                    if (loadExtractor(target, subtitleCallback, callback)) found = true
                }
                "linksmod" -> {
                    for (mirror in fetchLinksmodMirrors(entry.id)) {
                        if (loadExtractor(mirror, subtitleCallback, callback)) found = true
                    }
                }
            }
        }

        return found
    }
}
