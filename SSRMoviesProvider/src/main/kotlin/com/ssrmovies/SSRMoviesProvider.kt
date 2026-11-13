package com.ssrmovies

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

class SSRMoviesProvider : MainAPI() {

    override var mainUrl = "https://site2.ssrmovies.page"
    override var name = "SSRMovies"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://site2.ssrmovies.page/",
    )

    // One download button on a detail page.
    // host "lz" = a secure.linkszilla.top/view/<code> button. The gate page
    // (plain GET, follows a 302) lists the real hosts (HubCloud, GDFlix).
    data class LinkEntry(
        @JsonProperty("label") val label: String,
        @JsonProperty("host") val host: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("q") val quality: Int,
    )

    data class EpisodeData(
        @JsonProperty("links") val links: List<LinkEntry>? = null,
    )

    // One download button on a detail page (label + gate URL + episode range).
    // epName: display name from an "Episode N ..." heading above the button.
    // groupKey: heading text for dateless "Episode – <date>" groups (WWE
    // weekly shows), numbered after the walk, oldest group first.
    private data class Btn(val label: String, val gateUrl: String, val epStart: Int?, val epEnd: Int?, val epName: String? = null, val groupKey: String? = null)

    // Passed through the SearchResponse url so load() knows the poster:
    // detail pages have NO poster image (no og:image, no content image).
    data class LoadData(
        @JsonProperty("u") val url: String,
        @JsonProperty("p") val poster: String? = null,
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

    private fun toJson(data: Any): String? {
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
        } catch (e: Exception) { null }
    }

    // Robustly pull a real http(s) URL out of the `data` string load() gets.
    // The app normally passes our LoadData JSON {"u":...,"p":...}, but a
    // stale/cached entry or a SearchResponse JSON ({"posterUrl":...,"url":...})
    // can arrive instead — passing a raw JSON object to app.get() throws
    // "Expected URL scheme 'http' or 'https' but no scheme was found for
    // {"post...". Never throw: return null so load() returns null gracefully.
    private fun extractUrl(data: String): String? {
        tryParseJson<LoadData>(data)?.url?.takeIf { it.startsWith("http") }?.let { return it }
        // Generic JSON: look for a "url" (or "u") field that is an http URL.
        try {
            val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(data)
            if (node != null && node.isObject) {
                for (key in listOf("url", "u", "link")) {
                    node.get(key)?.asText()?.takeIf { it.startsWith("http") }?.let { return it }
                }
            }
        } catch (e: Exception) { }
        if (data.startsWith("http")) return data
        // Last resort: first http(s) URL anywhere in the string.
        return Regex("https?://[^\"'\\s\\\\]+").find(data)?.value
    }

    private fun extractPoster(data: String, parsed: LoadData?): String? {
        parsed?.poster?.takeIf { it.startsWith("http") }?.let { return it }
        try {
            val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(data)
            if (node != null && node.isObject) {
                for (key in listOf("posterUrl", "poster", "p")) {
                    node.get(key)?.asText()?.takeIf { it.startsWith("http") }?.let { return it }
                }
            }
        } catch (e: Exception) { }
        return null
    }

    private fun cleanText(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    private fun extractYear(raw: String): Int? {
        return Regex("\\b(19\\d{2}|20\\d{2})\\b").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    // Strip quality/format/source tokens so "The Odyssey 2026 Dual Audio
    // Hindi (Cleaned) 1080p 720p 480p BluRay x264 ESubs" displays as
    // "The Odyssey". The year is removed here too (it is stored separately).
    private fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("\\[.*?\\]"), " ")
        t = t.replace(Regex("\\b(19\\d{2}|20\\d{2})\\b"), " ")
        t = t.replace(
            Regex("(?i)\\b(2160p|1080p|720p|480p|360p|4k|blu-ray|bluray|web-dl|webdl|hdrip|hdcam|hdts|proper|multi|dual audio|hindi|esub|esubs|x264|x265|h\\.?264|h\\.?265|hevc|amzn|nf|jhs|org|5\\.1|cleaned|uncut|hindi dubbed|dubbed)\\b"),
            " "
        )
        t = t.replace(Regex("(?i)\\bS\\d{1,2}\\b"), " ")
        // Drop parens/brackets emptied by the token removal above, e.g.
        // "(ORG 5.1)" -> "()" and "(Cleaned)" -> "()".
        t = t.replace(Regex("\\(\\s*\\)"), " ")
        t = t.replace(Regex("\\[\\s*\\]"), " ")
        t = t.replace(Regex("[\\s\\-:–]+"), " ").trim()
        return t.trim(' ', '-', '–', ':')
    }

    private fun seasonFrom(raw: String): Int? {
        return Regex("(?i)\\bS0*(\\d+)\\b").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun isSeriesTitle(raw: String, slug: String): Boolean {
        if (Regex("(?i)\\bS\\d{1,2}\\b").containsMatchIn(raw)) return true
        if (Regex("(?i)-s\\d{1,2}(-|$)").containsMatchIn(slug)) return true
        if (raw.contains("Complete", true)) return true
        return false
    }

    // Poster: the listing img is lazy-loaded (src is a 1x1 gif); the real
    // poster is in style="background:url('https://imgurworld.top/...')".
    private fun posterFrom(img: org.jsoup.nodes.Element?): String? {
        if (img == null) return null
        val style = img.attr("style")
        val bg = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.getOrNull(1)
        if (!bg.isNullOrEmpty()) return bg
        val src = img.attr("src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
        if (src != null) return src
        return img.attr("data-src").takeIf { it.isNotEmpty() }
    }

    // Parse a listing page (home / category / search) into search responses.
    // Cards are article.latestPost > a.post-image[href] + h2.title a.
    private fun parseListing(html: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.latestPost")) {
            val a = article.selectFirst("a.post-image[href]") ?: article.selectFirst("a[href]") ?: continue
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val url = if (href.startsWith("http")) href else mainUrl + href
            if (!seen.add(url)) continue
            val titleEl = article.selectFirst("h2.title a") ?: a
            val rawTitle = cleanText(titleEl.attr("title").ifEmpty { titleEl.text() })
            if (rawTitle.isEmpty()) continue
            val poster = posterFrom(article.selectFirst("img"))
            val display = cleanTitle(rawTitle)
            if (display.isEmpty()) continue
            val loadJson = toJson(LoadData(url, poster)) ?: url
            if (isSeriesTitle(rawTitle, url)) {
                out.add(newTvSeriesSearchResponse(display, loadJson, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(display, loadJson, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/dual-audio-movies/" to "Dual Audio",
        "category/bollywood-movies/" to "Bollywood",
        "category/hollywood-movies-hd/" to "Hollywood",
        "category/web-series/" to "Web Series",
        "category/4k-movies/" to "4K Movies",
        "category/telugu-movies/" to "Telugu",
        "category/tamil-movies/" to "Tamil",
        "category/punjabi-movies/" to "Punjabi",
        "category/animated-movies/" to "Animated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cat = request.data
        val url = when {
            cat.isEmpty() && page <= 1 -> mainUrl
            cat.isEmpty() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$cat"
            else -> "$mainUrl/${cat.removeSuffix("/")}/page/$page/"
        }
        val html = app.get(url, headers = browserHeaders).text
        val list = parseListing(html)
        return newHomePageResponse(HomePageList(request.name, list), hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val html = app.get(url, headers = browserHeaders).text
        return parseListing(html).toNewSearchResponseList()
    }

    override suspend fun load(data: String): LoadResponse? {
        val parsed = tryParseJson<LoadData>(data)
        val url = extractUrl(data) ?: return null
        val posterFromList = extractPoster(data, parsed)
        val html = app.get(url, headers = browserHeaders).text
        val doc = Jsoup.parse(html)
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()?.substringBefore("–")?.trim()
            ?: ""
        if (rawTitle.isEmpty()) return null
        val title = cleanTitle(rawTitle)
        if (title.isEmpty()) return null
        val year = extractYear(rawTitle)
        val poster = posterFromList
        val plot = doc.select("p").map { cleanText(it.text()) }
            .firstOrNull { it.length >= 100 }
        val seasonNum = seasonFrom(rawTitle) ?: seasonFrom(url) ?: 1

        // Download buttons: a[href*=linkszilla] with labels like
        // "Watch & Download in 1080p - 3.45GB" (movie) or
        // "Download (Ep 08) 1080p - 1.34GB" (series) or
        // "Download (Ep 01-04) 1080p - 3.45GB" (pack).
        // Episodic / weekly shows use a THIRD layout (Koffee With Karan,
        // Bigg Boss, Sa Re Ga Ma Pa, WWE NXT, ...): headings
        // "|| Episode 05 (Guests) ||" or "|| Episode – 10th November 2026 ||"
        // above plain "Watch & Download in 1080p" buttons with NO (Ep N)
        // in the label. The old code only read (Ep N) from the button label,
        // so these pages had no episode data at all and load() returned null
        // ("SSRMovies returned nothing"). Walk headings + buttons in
        // document order so each button inherits the episode of the heading
        // above it; dateless "Episode – <date>" groups are numbered after
        // the walk, oldest group first, so old episodes keep their numbers.
        val buttons = mutableListOf<Btn>()
        var currentEpStart: Int? = null
        var currentEpEnd: Int? = null
        var currentEpName: String? = null
        var currentGroupKey: String? = null
        for (el in doc.select("h1, h2, h3, h4, h5, h6, a[href*=linkszilla]")) {
            if (el.tagName().matches(Regex("h[1-6]"))) {
                val heading = cleanText(el.text())
                val stripped = heading.trim('|', ' ').trim()
                if (stripped.startsWith("Episode", true)) {
                    val epMatch = Regex("Episode\\s*(\\d+)(?:\\s*-\\s*(\\d+))?", RegexOption.IGNORE_CASE).find(stripped)
                    if (epMatch != null) {
                        currentEpStart = epMatch.groupValues.getOrNull(1)?.toIntOrNull()
                        currentEpEnd = epMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: currentEpStart
                        currentEpName = stripped.takeIf { it.isNotEmpty() }
                        currentGroupKey = null
                    } else {
                        // Dateless heading, e.g. "Episode – 10th November 2026".
                        currentEpStart = null
                        currentEpEnd = null
                        currentEpName = null
                        currentGroupKey = stripped.takeIf { it.isNotEmpty() }
                    }
                } else {
                    currentEpStart = null
                    currentEpEnd = null
                    currentEpName = null
                    currentGroupKey = null
                }
                continue
            }
            val gate = el.attr("href")
            if (gate.isEmpty()) continue
            val label = cleanText(el.text())
            if (label.isEmpty()) continue
            val epMatch = Regex("\\(Ep\\s*(\\d+)(?:\\s*-\\s*(\\d+))?\\)", RegexOption.IGNORE_CASE).find(label)
            val epStart = epMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: currentEpStart
            val epEnd = epMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: epStart
            val epName = if (epMatch != null) null else currentEpName
            val groupKey = if (epMatch != null) null else currentGroupKey
            buttons.add(Btn(label, gate, epStart, epEnd, epName, groupKey))
        }
        if (buttons.isEmpty()) return null
        val isSeries = buttons.any { it.epStart != null } || buttons.any { it.groupKey != null } || isSeriesTitle(rawTitle, url)

        fun entryFor(btn: Btn): LinkEntry {
            val size = Regex("(\\d+(\\.\\d+)?\\s*(GB|MB))").find(btn.label)?.groupValues?.getOrNull(1).orEmpty()
            val qPart = when {
                btn.label.contains("2160") || btn.label.contains("4K", true) -> "2160p"
                btn.label.contains("1080") -> "1080p"
                btn.label.contains("720") -> "720p"
                btn.label.contains("480") -> "480p"
                btn.label.contains("360") -> "360p"
                else -> ""
            }
            val label = (qPart + (if (size.isNotEmpty()) " [$size]" else "")).trim()
                .ifEmpty { btn.label }
            return LinkEntry(label, "lz", btn.gateUrl, qualityValue(btn.label))
        }

        if (isSeries) {
            // Group by episode number so one CloudStream episode carries
            // ALL quality variants (user picks the highest at play time).
            val epMap = linkedMapOf<Int, MutableList<LinkEntry>>()
            val epNames = linkedMapOf<Int, String>()
            for (btn in buttons) {
                val start = btn.epStart ?: continue
                val end = btn.epEnd ?: start
                val key = start
                val links = epMap.getOrPut(key) { mutableListOf() }
                val entry = entryFor(btn)
                if (links.none { it.id == entry.id }) links.add(entry)
                val name = btn.epName ?: if (end != start) "Episode $start-$end" else "Episode $start"
                epNames[key] = name
            }
            // Dateless "Episode – <date>" groups (WWE weekly shows): number
            // them oldest-first (last group on the page = lowest number) so
            // earlier episodes keep their numbers when a new one is added
            // at the top of the page. Numbered episodes keep their numbers;
            // these start after the highest numbered episode.
            val unnumbered = linkedMapOf<String, MutableList<LinkEntry>>()
            for (btn in buttons) {
                val key = btn.groupKey ?: continue
                val links = unnumbered.getOrPut(key) { mutableListOf() }
                val entry = entryFor(btn)
                if (links.none { it.id == entry.id }) links.add(entry)
            }
            var nextEp = (epMap.keys.maxOrNull() ?: 0) + 1
            for (key in unnumbered.keys.reversed()) {
                val links = unnumbered[key] ?: continue
                if (links.isEmpty()) continue
                epMap[nextEp] = links
                epNames[nextEp] = key
                nextEp++
            }
            val episodes = mutableListOf<Episode>()
            for ((epNum, links) in epMap) {
                if (links.isEmpty()) continue
                val dataJson = toJson(EpisodeData(links = links)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = epNames[epNum] ?: "Episode $epNum"
                    this.episode = epNum
                    this.season = seasonNum
                    this.posterUrl = poster
                })
            }
            if (episodes.isEmpty()) return null
            episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val links = mutableListOf<LinkEntry>()
            val seenGate = mutableSetOf<String>()
            for (btn in buttons) {
                val entry = entryFor(btn)
                if (seenGate.add(entry.id)) links.add(entry)
            }
            if (links.isEmpty()) return null
            val dataJson = toJson(EpisodeData(links = links)) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // Resolve a HubCloud file id all the way to the direct video file.
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
        // The gate lists hubcloud.pw; .ist only 302-redirects to .pw, so
        // fetch .pw directly (one less hop that can fail on the phone).
        val hubUrl = "https://hubcloud.pw/drive/$hubCode"
        val hubHtml = try { app.get(hubUrl, headers = browserHeaders).text } catch (e: Exception) {
            try { app.get("https://hubcloud.ist/drive/$hubCode", headers = browserHeaders).text } catch (e2: Exception) { return emptyList() }
        }
        val gamerUrl = Regex("https?://gamerxyt\\.com/hubcloud\\.php\\?host=hubcloud&id=[A-Za-z0-9]+&token=[A-Za-z0-9]+")
            .find(hubHtml)?.value ?: return emptyList()
        val gamerHtml = try { app.get(gamerUrl, headers = browserHeaders).text } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<HubServer>()
        val seen = mutableSetOf<String>()
        fun add(server: String, url: String) {
            val u = url.trim()
            if (u.isNotEmpty() && seen.add(u)) out.add(HubServer(server, u))
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
        var found = false
        val entries = ep.links ?: return false

        // One entry's pre-resolved gate: the HubCloud servers (if any),
        // the GDFlix URL (if any) and the fallback stream URLs. Resolved
        // CONCURRENTLY (see loadLinks): each entry costs a gate GET plus
        // ~4-6 HubCloud hops, and resolving them one after another (4K
        // first, then 1080p) regularly blows the app's 30s loadLinks
        // timeout, so 1080p never arrives.
        // amap is CloudStream's own concurrent map (no kotlinx import
        // needed — the gradle plugin does not expose kotlinx-coroutines
        // to provider modules).
        data class ResolvedGate(
            val servers: List<HubServer>,
            val gdflixUrl: String?,
            val streamUrls: List<String>,
        )

        val resolved: List<ResolvedGate> = entries.amap { entry ->
            if (entry.host != "lz" || !entry.id.startsWith("http")) {
                return@amap ResolvedGate(emptyList(), null, emptyList())
            }
            // The linkszilla gate (plain GET, follows a 302) lists
            // the real hosts in its unlocked page HTML: HubCloud
            // + GDFlix.
            val gateHtml = try {
                app.get(entry.id, headers = browserHeaders).text
            } catch (e: Exception) { return@amap ResolvedGate(emptyList(), null, emptyList()) }
            // HubCloud (primary, proven): hubcloud.pw/drive/<code>.
            val hubCode = Regex("hubcloud\\.pw/drive/([A-Za-z0-9]+)")
                .find(gateHtml)?.groupValues?.getOrNull(1)
            val servers = if (!hubCode.isNullOrEmpty()) resolveHubCloudServers(hubCode) else emptyList()
            // GDFlix (best effort via the built-in extractors).
            val gdflixUrl = Regex("https?://[^\"'\\s]*gdflix[^\"'\\s]*/file/[A-Za-z0-9]+")
                .find(gateHtml)?.value
            // Fallback stream hosts on the same gate page.
            val streamUrls = listOf(
                Regex("https?://watch-online\\.mom/e/[A-Za-z0-9]+").find(gateHtml)?.value,
                Regex("https?://streamtape\\.com/v/[A-Za-z0-9]+").find(gateHtml)?.value,
            ).filterNotNull().distinct()
            ResolvedGate(servers, gdflixUrl, streamUrls)
        }

        // Callbacks are emitted sequentially in page order, so the source
        // list order is unchanged.
        entries.zip(resolved).forEach { (entry, res) ->
            if (entry.host != "lz") return@forEach
            if (!entry.id.startsWith("http")) return@forEach
            // HubCloud (primary, proven): hubcloud.pw/drive/<code>.
            // One source per server (FSL, FSLv2, PixelDrain, 10G) so the
            // app shows WHICH server a link is.
            for (server in res.servers) {
                val name = if (server.server == "HubCloud") "HubCloud ${entry.label}"
                    else "HubCloud ${server.server} ${entry.label}"
                callback(
                    newExtractorLink(
                        source = "HubCloud",
                        name = name,
                        url = server.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = entry.quality
                        this.referer = "https://hubcloud.pw/"
                    }
                )
                found = true
            }
            // GDFlix (best effort via the built-in extractors; CF-403
            // from a VM, so unverified here — the phone test is the proof).
            if (!res.gdflixUrl.isNullOrEmpty()) {
                try {
                    loadExtractor(res.gdflixUrl, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) { }
            }
            // Fallback stream hosts on the same gate page (best effort).
            // If HubCloud is unreachable from the phone, one of these
            // usually still resolves to a playable link.
            for (streamUrl in res.streamUrls) {
                try {
                    loadExtractor(streamUrl, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) { }
            }
        }
        return found
    }
}
