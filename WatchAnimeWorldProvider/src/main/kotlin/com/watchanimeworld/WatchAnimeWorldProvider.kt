package com.watchanimeworld

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WatchAnimeWorldProvider : MainAPI() {

    // NOTE: the user's domain watchanimeworld.one Cloudflare-blocks plain
    // HTTP clients (HTTP 403 even with full browser headers, verified
    // 2026-11-22), while watchanimeworld.pro serves the IDENTICAL site
    // (same titles, players, sitemaps) over plain HTTP. The plugin uses
    // the .pro mirror so it works from CloudStream's plain HTTP client.
    override var mainUrl = "https://watchanimeworld.pro"
    override var name = "WatchAnimeWorld"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // One language entry from the /api/player1.php data blob:
    // [{"language":"Hindi","link":"https://short.icu/<code>"}, ...]
    data class LangLink(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("link") val link: String? = null,
    )

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> "$mainUrl/$url"
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    private fun parseYear(text: String?): Int? {
        return text?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
    }

    // Body text carries "Overview: <plot>" followed by the meta line
    // ("24 min 2019 Trailer" on series, "Genres ... Languages" on
    // movies). The og:description is NOT usable for movies (SEO spam).
    private fun parseOverview(bodyText: String): String? {
        val idx = bodyText.indexOf("Overview:")
        if (idx < 0) return null
        var plot = bodyText.substring(idx + "Overview:".length).trim()
        for (cut in listOf(" Genres ", " Trailer ", " choose season ")) {
            val i = plot.indexOf(cut)
            if (i > 0) plot = plot.substring(0, i).trim()
        }
        // Trailing duration + year ("24 min 2019", "1h 52m 2019").
        val m = Regex("\\s+\\d+\\s*(?:h\\s*\\d+m|min)\\s+(?:19|20)\\d{2}\\b").find(plot)
        if (m != null) plot = plot.substring(0, m.range.first).trim()
        return plot.takeIf { it.length >= 20 }
    }

    // Movies only: "Genres Drama , Fantasy , Romance Languages ...".
    // Series pages carry no genre block, so this returns empty there.
    private fun parseGenres(bodyText: String): List<String> {
        val m = Regex("Genres\\s+(.+?)\\s+Languages\\b").find(bodyText) ?: return emptyList()
        return m.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // One card: <article class="post"> with h2.entry-title, an
    // <a class="lnk-blk"> to /series/<slug>/ or /movies/<slug>/, and a
    // TMDB poster <img> (protocol-relative src). The .year span holds
    // "EP:1-26" on series cards and the year on recommended cards.
    private fun parseCard(article: Element): SearchResponse? {
        val link = article.selectFirst("a.lnk-blk") ?: article.selectFirst("a[href]") ?: return null
        val href = link.attr("href")
        if (href.isEmpty()) return null
        val url = fixUrl(href)
        val title = cleanTitle(article.selectFirst("h2.entry-title")?.text() ?: return null)
        if (title.isEmpty()) return null
        val img = article.selectFirst(".post-thumbnail img") ?: article.selectFirst("img")
        val poster = img?.let {
            it.attr("src").takeIf { s -> s.isNotEmpty() }
                ?: it.attr("data-src").takeIf { s -> s.isNotEmpty() }
        }?.let { fixUrl(it) }
        return if (url.contains("/movies/")) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.post")) {
            val item = parseCard(article) ?: continue
            if (seen.add(item.url)) out.add(item)
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data
        // Homepage is one curated page (no pagination); /series/ and
        // /movies/ paginate at /page/N/.
        val url = when {
            cat.isEmpty() -> mainUrl
            page <= 1 -> "$mainUrl/$cat/"
            else -> "$mainUrl/$cat/page/$page/"
        }
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(Jsoup.parse(html))
        val hasNext = if (cat.isEmpty()) false else list.isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, list), hasNext = hasNext)
    }

    override val mainPage = mainPageOf(
        "" to "Latest",
        "series" to "All Series",
        "movies" to "All Movies",
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // WordPress ?s= is server-rendered here (verified live
        // 2026-11-22: HTTP 200, real results, no Cloudflare challenge).
        // Pagination is untested, so search is page 1 only.
        if (page > 1) return emptyList<SearchResponse>().toNewSearchResponseList()
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(Jsoup.parse(html)).toNewSearchResponseList()
    }

    // "Recommended Series" / "Recommended Movies" section. The site has
    // NO cast block (the cast list <ul class="cast-lst"> is empty), so
    // actors stay unset rather than faked — the Cast row will simply not
    // appear for this plugin.
    private fun parseRecommendations(doc: Document): List<SearchResponse> {
        val header = doc.select("h3.section-title").firstOrNull {
            it.text().trim() == "Recommended Series" || it.text().trim() == "Recommended Movies"
        } ?: return emptyList()
        val section = header.closest("section") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in section.select("article.post")) {
            val item = parseCard(article) ?: continue
            if (seen.add(item.url)) out.add(item)
        }
        return out
    }

    private fun parseEpisodeRef(url: String): Pair<Int, Int>? {
        val m = Regex("-(\\d+)x(\\d+)/?$").find(url) ?: return null
        val s = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return s to e
    }

    private fun collectEpisodes(from: Document, poster: String?, out: MutableList<Episode>, seen: MutableSet<String>) {
        for (a in from.select("a[href*=/episode/]")) {
            val epUrl = fixUrl(a.attr("href"))
            if (epUrl.isEmpty() || !seen.add(epUrl)) continue
            val (s, e) = parseEpisodeRef(epUrl) ?: continue
            out.add(newEpisode(epUrl) {
                this.name = "Episode $e"
                this.episode = e
                this.season = s
                this.posterUrl = poster
            })
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val bodyText = doc.body()?.text() ?: return null
        val title = cleanTitle(doc.selectFirst("h1.entry-title")?.text() ?: return null)
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst(".post img")?.attr("src")?.let { fixUrl(it) }
        val plot = parseOverview(bodyText)
        val year = parseYear(doc.selectFirst(".entry-meta .year")?.text())
        val recommendations = parseRecommendations(doc)

        if (url.contains("/movies/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = parseGenres(bodyText)
                this.year = year
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        }

        // Series: this page lists the latest season's episodes; the
        // season selector is JS (admin-ajax), but every season also has
        // a plain /season/<slug>-<n>/ page, so fetch the other seasons
        // directly and merge (dedup by episode URL).
        val slug = url.trimEnd('/').substringAfterLast("/")
        val seasons = doc.select(".sel-temp a[data-season]")
            .mapNotNull { it.attr("data-season").toIntOrNull() }.distinct().sorted()
        val currentSeason = doc.selectFirst(".n_s")?.text()?.trim()?.toIntOrNull()
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        collectEpisodes(doc, poster, episodes, seen)
        val otherSeasons = seasons.filter { it != currentSeason }
        if (otherSeasons.isNotEmpty()) {
            val extra: List<List<Episode>> = otherSeasons.amap { sn ->
                try {
                    val sDoc = Jsoup.parse(
                        app.get("$mainUrl/season/$slug-$sn/", headers = browserHeaders).text
                    )
                    val list = mutableListOf<Episode>()
                    collectEpisodes(sDoc, poster, list, mutableSetOf())
                    list
                } catch (e: Exception) { emptyList() }
            }
            for (list in extra) for (ep in list) {
                if (seen.add(ep.data)) episodes.add(ep)
            }
        }
        if (episodes.isEmpty()) return null
        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.recommendations = recommendations.takeIf { it.isNotEmpty() }
        }
    }

    // ---- Video resolution ----

    // The /api/player1.php data blob is URL-encoded base64 JSON:
    // [{"language":"Hindi","link":"https://short.icu/<code>"}, ...]
    private fun decodePlayerData(data: String): List<LangLink> {
        return try {
            var b64 = java.net.URLDecoder.decode(data, "UTF-8")
                .replace("-", "+").replace("_", "/")
            while (b64.length % 4 != 0) b64 += "="
            val json = String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
            tryParseJson<List<LangLink>>(json) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    // short.icu is a redirect shortener: follow the chain manually (up
    // to 5 hops) so loadExtractor sees the real video host instead of
    // the shortener domain, which has no extractor of its own.
    private suspend fun resolveRedirects(url: String, referer: String, maxHops: Int = 5): String {
        var current = url
        repeat(maxHops) {
            val res = try {
                app.get(current, headers = browserHeaders + ("Referer" to referer), allowRedirects = false)
            } catch (e: Exception) { return current }
            if (res.code !in 300..399) return current
            val loc = res.headers["Location"] ?: res.headers["location"] ?: return current
            val next = if (loc.startsWith("http")) loc else try {
                java.net.URL(java.net.URL(current), loc).toString()
            } catch (e: Exception) { return current }
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun directMediaUrl(url: String): String? {
        val path = url.substringBefore("#").substringBefore("?")
        return if (path.endsWith(".m3u8", true) || path.endsWith(".mp4", true) ||
            path.endsWith(".mkv", true) || path.endsWith(".mov", true)
        ) url else null
    }

    // Best effort: the as-cdn embed is a player page — if it carries a
    // direct m3u8/mp4 in its HTML, take it. The host drops plain HTTP
    // from datacenter IPs, so this only fires where it is reachable.
    private suspend fun scrapeEmbedMedia(embedUrl: String, referer: String): List<String> {
        val html = try {
            app.get(embedUrl, headers = browserHeaders + ("Referer" to referer)).text
        } catch (e: Exception) { return emptyList() }
        val found = mutableListOf<String>()
        val mediaRe = Regex("""https?://[^\s"'\\]+?\.(?:m3u8|mp4)(?:[?#][^\s"'\\]*)?""")
        for (m in mediaRe.findAll(html)) found.add(m.value)
        val fileRe = Regex(""""file"\s*:\s*"(https?://[^"]+)"""")
        for (m in fileRe.findAll(html)) {
            val u = m.groupValues[1]
            if (u.contains(".m3u8") || u.contains(".mp4")) found.add(u)
        }
        return found.distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data
        val html = try {
            app.get(pageUrl, headers = browserHeaders).text
        } catch (e: Exception) { return false }
        val doc = Jsoup.parse(html)
        var found = false

        // ---- Server 1 (default): as-cdn26.top embed ----
        val embedUrl = doc.selectFirst("#options-0 iframe[src]")?.attr("src")
            ?: doc.selectFirst("iframe[src*=as-cdn]")?.attr("src")
        if (!embedUrl.isNullOrEmpty()) {
            val fullEmbed = fixUrl(embedUrl)
            for (media in scrapeEmbedMedia(fullEmbed, pageUrl)) {
                callback(
                    newExtractorLink(
                        source = "WatchAnimeWorld",
                        name = "Server 1",
                        url = media,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
            // Stock extractor for the embed host, if one matches.
            if (loadExtractor(fullEmbed, subtitleCallback, callback)) found = true
        }

        // ---- Server 2 (alt): multi-language player ----
        // iframe data-src="/api/player1.php?data=<b64 JSON>" — the JSON
        // maps each audio language to a short.icu link. Some episodes
        // only have Server 1, so this block is optional.
        val playerData = doc.selectFirst("#options-1 iframe[data-src]")?.attr("data-src")
            ?: doc.selectFirst("iframe[data-src*=player1.php]")?.attr("data-src")
        val langLinks = playerData
            ?.substringAfter("data=", "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { decodePlayerData(it) }
            ?: emptyList()
        if (langLinks.isNotEmpty()) {
            data class Resolved(val name: String, val url: String?)

            // Resolve every language link CONCURRENTLY: each costs a
            // redirect chain, and resolving them one after another
            // regularly blows the app's 30s loadLinks timeout.
            val resolved: List<Resolved> = langLinks.amap { entry ->
                val link = entry.link
                    ?: return@amap Resolved(entry.language ?: "Multi-Audio", null)
                val finalUrl = try {
                    resolveRedirects(link, pageUrl)
                } catch (e: Exception) { link }
                Resolved(entry.language ?: "Multi-Audio", finalUrl)
            }

            // Callbacks are emitted sequentially in page order, so the
            // source list order is unchanged.
            for ((lang, finalUrl) in resolved) {
                if (finalUrl.isNullOrEmpty()) continue
                val direct = directMediaUrl(finalUrl)
                if (direct != null) {
                    callback(
                        newExtractorLink(
                            source = "WatchAnimeWorld",
                            name = "Server 2 $lang",
                            url = direct,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = pageUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                } else if (loadExtractor(finalUrl, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        return found
    }
}
