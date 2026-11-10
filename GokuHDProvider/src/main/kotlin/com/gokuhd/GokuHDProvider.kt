package com.gokuhd

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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class GokuHDProvider : MainAPI() {

    override var mainUrl = "https://gokuhd.nl"
    override var name = "GokuHD"
    // Hindi Dubbed Anime series + anime movies (Vega network).
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json",
        "Referer" to "$mainUrl/",
    )

    // Episode data passed to loadLinks (as JSON string).
    // host "fastdl": a fastdl.beer/embed.php?download=<id> URL (G-Direct).
    // host "nexdrive": a nexdrive.<tld>/genxfm<digits>/ URL (G-Direct page,
    //   ep == null -> whole file / first fastdl link on the page).
    // host "nexdrive-vcloud": a nexdrive page holding the V-Cloud / V-Drive
    //   links for an episode (resolved lazily in loadLinks).
    // host "vcloud" / "vdrive" / "filepress" / "extractor": a direct URL
    //   handed to the 3-arg loadExtractor.
    data class LinkEntry(
        @JsonProperty("label") val label: String,
        @JsonProperty("host") val host: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("q") val quality: Int,
        @JsonProperty("ep") val ep: Int? = null,
    )

    data class EpisodeData(
        @JsonProperty("links") val links: List<LinkEntry>? = null,
    )

    // WP REST posts (used for search; /?s= is CF-403 and /search/<q> has
    // zero server-side results).
    data class WpPost(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("title") val title: WpTitle? = null,
        @JsonProperty("featured_media") val featuredMedia: Int? = null,
    )

    data class WpTitle(
        @JsonProperty("rendered") val rendered: String? = null,
    )

    data class WpMedia(
        @JsonProperty("source_url") val sourceUrl: String? = null,
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
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("\\s+"), " ")
            .replace(Regex("\\s*\\|\\s*\\[.*\\]$"), "")
            .trim()
    }

    private fun unescapeHtml(raw: String): String {
        return Jsoup.parse(raw).body()?.text()?.trim() ?: raw.trim()
    }

    private fun fixPoster(poster: String?): String? {
        if (poster.isNullOrEmpty()) return null
        return if (poster.startsWith("http")) poster else mainUrl + poster
    }

    private fun isSeriesTitle(title: String): Boolean {
        return title.contains("Season", true)
            || title.contains("TV Series", true)
            || title.contains("Anime Series", true)
    }

    // Parse a listing page (home / category) into search responses.
    // Posts are <article> cards with <h2 class="entry-title"><a> and an
    // <img> poster inside .post-image.
    private fun parseListing(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article")) {
            val a = article.selectFirst("h2.entry-title a")
                ?: article.selectFirst("a[href]") ?: continue
            val href = a.attr("href").trim()
            if (href.isEmpty()) continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            val img = article.selectFirst("img")
            val poster = fixPoster(
                img?.attr("src")?.takeIf { it.isNotEmpty() }
                    ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            )
            val title = cleanTitle(a.text())
            if (title.isEmpty()) continue
            if (isSeriesTitle(title)) {
                out.add(newTvSeriesSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, url, TvType.AnimeMovie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        "" to "Latest",
        "series/hindi-dubbed-series" to "Hindi Dubbed Series",
        "series/english-dubbed-subbed-series" to "English Dubbed & Subbed",
        "series/hindi-subbed-series" to "Hindi Subbed Series",
        "movies/hindi-dubbed-movies" to "Hindi Dubbed Movies",
        "trending" to "Trending",
        "ongoing-series" to "Ongoing Series",
        "qualities/720p" to "720p",
        "qualities/1080p" to "1080p",
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

    // Search via WP REST: GET /wp-json/wp/v2/posts?search=<q>&per_page=18.
    // (/?s= is Cloudflare-403; /search/<q> returns 0 server-side results
    // because it is a Typesense JS widget.)
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/wp-json/wp/v2/posts?search=$q&per_page=18&page=$page"
        val posts = try {
            app.get(url, headers = apiHeaders).parsed<List<WpPost>>()
        } catch (e: Exception) {
            return null
        }
        if (posts.isNullOrEmpty()) return emptyList<SearchResponse>().toNewSearchResponseList()
        val posterCache = mutableMapOf<Int, String?>()
        val out = mutableListOf<SearchResponse>()
        for (p in posts) {
            val link = p.link?.trim() ?: continue
            val title = cleanTitle(unescapeHtml(p.title?.rendered ?: continue))
            if (title.isEmpty()) continue
            var poster: String? = null
            val fm = p.featuredMedia
            if (fm != null && fm > 0) {
                poster = posterCache.getOrPut(fm) {
                    try {
                        app.get("$mainUrl/wp-json/wp/v2/media/$fm", headers = apiHeaders)
                            .parsed<WpMedia>()?.sourceUrl
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            if (isSeriesTitle(title)) {
                out.add(newTvSeriesSearchResponse(title, link, TvType.Anime) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, link, TvType.AnimeMovie) { this.posterUrl = poster })
            }
        }
        return out.toNewSearchResponseList()
    }

    // One quality section on a detail page: the nexdrive links under an
    // <h4> heading such as "Title [Season 18] {...} 720p x264 [180MB/E]".
    private data class DetailSection(
        val season: Int?,
        val quality: String,
        val size: String?,
        val gdirectUrl: String?,
        val vcloudUrl: String?,
        val batchUrl: String?,
    )

    private fun parseDetailSections(doc: org.jsoup.nodes.Document): List<DetailSection> {
        val out = mutableListOf<DetailSection>()
        for (h in doc.select("h4")) {
            val text = h.text().trim()
            val q = Regex("(?i)(2160p|1080p|720p|480p|360p|4K)").find(text)?.groupValues?.get(1) ?: continue
            val season = Regex("(?i)Season\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val size = Regex("""\[([0-9.]+ ?[MG]B(?:/E)?)\]""").find(text)?.groupValues?.get(1)
            var gdirect: String? = null
            var vcloud: String? = null
            var batch: String? = null
            var sib = h.nextElementSibling()
            while (sib != null && sib.tagName() != "h4") {
                for (a in sib.select("a[href]")) {
                    val href = a.attr("href").trim()
                    if (!href.contains("nexdrive.")) continue
                    val t = a.text().trim()
                    when {
                        t.startsWith("Source 1", true) -> gdirect = href
                        t.startsWith("Source 2", true) -> vcloud = href
                        t.startsWith("Batch File", true) -> batch = href
                        t.startsWith("Download Now", true) -> if (gdirect == null) gdirect = href
                        else -> if (gdirect == null) gdirect = href
                    }
                }
                sib = sib.nextElementSibling()
            }
            if (gdirect == null && vcloud == null && batch == null) continue
            out.add(DetailSection(season, q.uppercase(), size, gdirect, vcloud, batch))
        }
        return out
    }

    // One section of a nexdrive page: "-:Episode: N:-" (GokuHD uses the
    // SINGULAR form; Vegamovies uses plural "-:Episodes: N:-" — the regex
    // below accepts both).
    private data class NexSection(
        val ep: Int?,
        val fastdl: String?,
        val filepress: String?,
        val vcloud: String?,
        val vdrive: String?,
        val others: List<Pair<String, String>>,
    )

    private fun fastdlOf(s: String): String? =
        Regex("""https://fastdl\.beer/embed\.php\?download=[A-Za-z0-9]+""").find(s)?.value

    private fun filepressOf(s: String): String? =
        Regex("""https://(?:filebee\.xyz|filebee\.top)/file/[A-Za-z0-9]+""").find(s)?.value

    private fun vcloudOf(s: String): String? =
        Regex("""https://vcloud\.beer/[A-Za-z0-9_-]+""").find(s)?.value

    private fun vdriveOf(s: String): String? =
        Regex("""https://one\.vegadrive\.app/s/[A-Za-z0-9]+""").find(s)?.value

    private fun otherHostsOf(s: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        val re = Regex("""https://(?:1fichier\.com/\?[A-Za-z0-9]+|gofile\.io/d/[A-Za-z0-9]+|megaup\.net/[A-Za-z0-9]+/[^"'\s]+|vikingfile\.com/f/[A-Za-z0-9]+)""")
        for (m in re.findAll(s)) {
            val url = m.value
            if (!seen.add(url)) continue
            val label = when {
                url.contains("1fichier") -> "1Fichier"
                url.contains("gofile") -> "GoFile"
                url.contains("megaup") -> "MegaUp"
                url.contains("vikingfile") -> "VikingFile"
                else -> "Mirror"
            }
            out.add(label to url)
        }
        return out
    }

    private fun parseNexdriveSections(html: String): List<NexSection> {
        val markers = Regex("""-:Episode[s]?:\s*(\d+):-""").findAll(html).toList()
        if (markers.isEmpty()) {
            return listOf(
                NexSection(
                    null,
                    fastdlOf(html),
                    filepressOf(html),
                    vcloudOf(html),
                    vdriveOf(html),
                    otherHostsOf(html),
                )
            )
        }
        return markers.mapIndexed { idx, mk ->
            val segStart = mk.range.last
            val segEnd = if (idx + 1 < markers.size) markers[idx + 1].range.first else html.length
            val seg = html.substring(segStart, segEnd)
            NexSection(
                mk.groupValues[1].toIntOrNull(),
                fastdlOf(seg),
                filepressOf(seg),
                vcloudOf(seg),
                vdriveOf(seg),
                otherHostsOf(seg),
            )
        }
    }

    private suspend fun fetchNexdriveSections(nexUrl: String): List<NexSection> {
        val html = try {
            app.get(nexUrl, headers = browserHeaders + mapOf("Referer" to "$mainUrl/")).text
        } catch (e: Exception) {
            return emptyList()
        }
        return parseNexdriveSections(html)
    }

    // Episode numbers inside a nexdrive page (used to enumerate a season).
    private suspend fun fetchNexdriveEpisodeNumbers(nexUrl: String): List<Int> {
        val html = try {
            app.get(nexUrl, headers = browserHeaders + mapOf("Referer" to "$mainUrl/")).text
        } catch (e: Exception) {
            return emptyList()
        }
        return Regex("""-:Episode[s]?:\s*(\d+):-""").findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct().sorted().toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("h1.single-post-title")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim() ?: return null
        val title = cleanTitle(rawTitle)
        if (title.isEmpty()) return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: fixPoster(doc.selectFirst("article img")?.attr("src"))
        val sections = parseDetailSections(doc)
        if (sections.isEmpty()) return null
        val isSeries = sections.any { it.season != null } || isSeriesTitle(title)

        if (isSeries) {
            val epMap = mutableMapOf<Pair<Int, Int>, MutableList<LinkEntry>>()
            val packMap = mutableMapOf<Int, MutableList<LinkEntry>>()
            val bySeason = sections.groupBy { it.season ?: 1 }
            for ((season, secs) in bySeason) {
                // Enumerate the season's episode numbers from ONE nexdrive
                // page (all qualities of a season list the same episodes).
                var epNums: List<Int> = emptyList()
                for (sec in secs) {
                    val probe = sec.gdirectUrl ?: sec.vcloudUrl ?: continue
                    epNums = fetchNexdriveEpisodeNumbers(probe)
                    if (epNums.isNotEmpty()) break
                }
                if (epNums.isEmpty()) {
                    // No episode sections (whole-season pack page): one
                    // "Season Pack" episode holding every quality's links.
                    for (sec in secs) {
                        val sizeSuffix = sec.size?.let { " [$it]" } ?: ""
                        val q = qualityValue(sec.quality)
                        sec.gdirectUrl?.let {
                            packMap.getOrPut(season) { mutableListOf() }
                                .add(LinkEntry("${sec.quality}$sizeSuffix G-Direct", "nexdrive", it, q, null))
                        }
                        sec.vcloudUrl?.let {
                            packMap.getOrPut(season) { mutableListOf() }
                                .add(LinkEntry("${sec.quality}$sizeSuffix V-Cloud", "nexdrive-vcloud", it, q, null))
                        }
                        sec.batchUrl?.let {
                            packMap.getOrPut(season) { mutableListOf() }
                                .add(LinkEntry("${sec.quality}$sizeSuffix Batch", "nexdrive", it, q, null))
                        }
                    }
                } else {
                    for (epNum in epNums) {
                        for (sec in secs) {
                            val sizeSuffix = sec.size?.let { " [$it]" } ?: ""
                            val q = qualityValue(sec.quality)
                            sec.gdirectUrl?.let {
                                epMap.getOrPut(season to epNum) { mutableListOf() }
                                    .add(LinkEntry("${sec.quality}$sizeSuffix G-Direct", "nexdrive", it, q, epNum))
                            }
                            sec.vcloudUrl?.let {
                                epMap.getOrPut(season to epNum) { mutableListOf() }
                                    .add(LinkEntry("${sec.quality}$sizeSuffix V-Cloud", "nexdrive-vcloud", it, q, epNum))
                            }
                            sec.batchUrl?.let {
                                epMap.getOrPut(season to epNum) { mutableListOf() }
                                    .add(LinkEntry("${sec.quality}$sizeSuffix Batch", "nexdrive", it, q, epNum))
                            }
                        }
                    }
                }
            }
            val episodes = mutableListOf<Episode>()
            for ((key, links) in epMap) {
                val (season, epNum) = key
                val dataJson = toJson(EpisodeData(links)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Episode $epNum"
                    this.season = season
                    this.episode = epNum
                })
            }
            for ((season, links) in packMap) {
                val dataJson = toJson(EpisodeData(links)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Season Pack"
                    this.season = season
                    this.episode = 0
                })
            }
            if (episodes.isEmpty()) return null
            episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
            }
        } else {
            // Movie: one LinkEntry per (quality, host) — the nexdrive page
            // for each quality already lists every host for that file.
            val links = mutableListOf<LinkEntry>()
            for (sec in sections) {
                val q = qualityValue(sec.quality)
                val sizeSuffix = sec.size?.let { " [$it]" } ?: ""
                val nex = sec.gdirectUrl ?: continue
                for (s in fetchNexdriveSections(nex)) {
                    s.fastdl?.let {
                        links.add(LinkEntry("${sec.quality}$sizeSuffix G-Direct", "fastdl", it, q, null))
                    }
                    s.filepress?.let {
                        links.add(LinkEntry("${sec.quality}$sizeSuffix FilePress", "filepress", it, q, null))
                    }
                    s.vcloud?.let {
                        links.add(LinkEntry("${sec.quality}$sizeSuffix V-Cloud", "vcloud", it, q, null))
                    }
                    s.vdrive?.let {
                        links.add(LinkEntry("${sec.quality}$sizeSuffix V-Drive", "vdrive", it, q, null))
                    }
                    s.others.forEach { (hostName, u) ->
                        links.add(LinkEntry("${sec.quality}$sizeSuffix $hostName", "extractor", u, q, null))
                    }
                }
            }
            if (links.isEmpty()) return null
            val dataJson = toJson(EpisodeData(links)) ?: return null
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, dataJson) {
                this.posterUrl = poster
            }
        }
    }

    // ---- Link resolution ----
    // Chain: nexdrive.<tld>/genxfm.../ -> "G-Direct [Instant]" link
    // fastdl.beer/embed.php?download=<id> -> page contains
    //   var reurl = "https://fastdl.beer/dl.php?link=https://video-downloads.googleusercontent.com/<token>"
    // The googleusercontent URL is the direct playable file — fastdl.beer
    // gives it DIRECTLY in the reurl (NO separate prepare-JSON call; that
    // was Vegamovies' fast-dl.one flow, a different host).

    private suspend fun resolveFastDl(embedUrl: String): String? {
        val html = try {
            app.get(embedUrl, headers = browserHeaders + mapOf("Referer" to "$mainUrl/")).text
        } catch (e: Exception) {
            return null
        }
        val m = Regex(
            """reurl\s*=\s*"https://fastdl\.beer/dl\.php\?link=(https://video-downloads\.googleusercontent\.com/[^"]+)""""
        ).find(html) ?: return null
        return m.groupValues[1].replace("&amp;", "&").trim()
            .takeIf { it.startsWith("http") }
    }

    // Resolve one nexdrive link (optionally a single episode inside it)
    // all the way to the direct googleusercontent file URL.
    private suspend fun resolveNexdrive(nexUrl: String, ep: Int?): String? {
        val sections = fetchNexdriveSections(nexUrl)
        if (sections.isEmpty()) return null
        val target = if (ep != null) {
            sections.firstOrNull { it.ep == ep } ?: sections.firstOrNull()
        } else {
            sections.firstOrNull()
        }
        val embed = target?.fastdl ?: return null
        return resolveFastDl(embed)
    }

    // Resolve the secondary hosts (V-Cloud / V-Drive / FilePress) inside one
    // nexdrive page (optionally a single episode inside it).
    private suspend fun resolveNexdriveSecondary(
        nexUrl: String,
        ep: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sections = fetchNexdriveSections(nexUrl)
        if (sections.isEmpty()) return false
        val target = if (ep != null) {
            sections.firstOrNull { it.ep == ep } ?: sections.firstOrNull()
        } else {
            sections.firstOrNull()
        } ?: return false
        var found = false
        target.fastdl?.let {
            val direct = resolveFastDl(it)
            if (direct != null) {
                callback(
                    newExtractorLink(
                        source = "G-Direct",
                        name = "G-Direct",
                        url = direct,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://fastdl.beer/"
                    }
                )
                found = true
            }
        }
        target.vcloud?.let {
            try {
                if (loadExtractor(it, subtitleCallback, callback)) found = true
            } catch (e: Exception) {
            }
        }
        target.vdrive?.let {
            try {
                if (loadExtractor(it, subtitleCallback, callback)) found = true
            } catch (e: Exception) {
            }
        }
        target.filepress?.let {
            try {
                if (loadExtractor(it, subtitleCallback, callback)) found = true
            } catch (e: Exception) {
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
        ep.links?.forEach { entry ->
            when (entry.host) {
                "fastdl", "nexdrive" -> {
                    val direct = if (entry.host == "fastdl") {
                        resolveFastDl(entry.id)
                    } else {
                        resolveNexdrive(entry.id, entry.ep)
                    } ?: return@forEach
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
                    found = true
                }
                "nexdrive-vcloud" -> {
                    if (resolveNexdriveSecondary(entry.id, entry.ep, subtitleCallback, callback)) {
                        found = true
                    }
                }
                "vcloud", "vdrive", "filepress", "extractor" -> {
                    try {
                        if (loadExtractor(entry.id, subtitleCallback, callback)) found = true
                    } catch (e: Exception) {
                    }
                }
            }
        }
        return found
    }
}
