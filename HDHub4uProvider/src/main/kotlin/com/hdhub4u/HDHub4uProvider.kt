package com.hdhub4u

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

class HDHub4uProvider : MainAPI() {

    override var mainUrl = "https://new12.hdhub4u.cl"
    override var name = "HDHub4u"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Episode data passed to loadLinks (as JSON string).
    // A LIST of links (not a map) so every quality variant shows
    // separately with its size, e.g. "1080p x264 [4.6GB]" and
    // "HQ 1080p (SOURCE) [32.8GB]" are two different entries.
    data class LinkEntry(
        @JsonProperty("label") val label: String,  // full button text, e.g. "HQ 1080p (SOURCE) [32.8GB]"
        @JsonProperty("host") val host: String,    // "hubcdn" or "hubdrive"
        @JsonProperty("id") val id: String,        // file id
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
            ?: doc.selectFirst("div.post-content img, article img, .post img")?.attr("src")
        val isSeries = url.contains("-full-series/") || title.contains("Season", true)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            // Per-episode: "EPiSODE N" heading, then a 720p and a 1080p
            // hubdrive link. Walk the links in document order, tracking the
            // current episode number from the surrounding headings.
            // (Note: this theme does NOT use a div.entry-content container,
            // so parse the whole page body.)
            val bodyHtml = doc.body()?.html() ?: html
            // Split the content on "EPiSODE N" markers
            val epSplit = Regex("(?i)EPiSODE\\s*(\\d+)").findAll(bodyHtml).toList()
            for ((idx, m) in epSplit.withIndex()) {
                val epNum = m.groupValues[1].toIntOrNull() ?: (idx + 1)
                val segStart = m.range.last
                val segEnd = if (idx + 1 < epSplit.size) epSplit[idx + 1].range.first else bodyHtml.length
                val seg = bodyHtml.substring(segStart, segEnd)
                val links = mutableListOf<LinkEntry>()
                val seenIds = mutableSetOf<String>()
                for (lm in Regex("https://hubdrive\\.blog/file/(\\d+)").findAll(seg)) {
                    val id = lm.groupValues[1]
                    if (!seenIds.add(id)) continue
                    // The 720p link comes first, 1080p second in each segment
                    val label = if (links.isEmpty()) "720p" else "1080p"
                    links.add(LinkEntry(label, "hubdrive", id, qualityValue(label)))
                }
                if (links.isEmpty()) continue
                val dataJson = toJson(EpisodeData(links = links)) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }
            // Season packs (1080p / 4K) as extra episodes
            val packLinks = Regex("https://hubdrive\\.blog/file/(\\d+)").findAll(bodyHtml)
                .map { it.groupValues[1] }.distinct().toList()
            val usedIds = episodes.flatMap { ep ->
                val d = tryParseJson<EpisodeData>(ep.data) ?: return@flatMap emptyList<String>()
                (d.links?.map { it.id } ?: emptyList())
            }.toSet()
            for (pid in packLinks) {
                if (pid in usedIds) continue
                // Only treat it as a pack if it appears near a "PACK" label
                val pos = bodyHtml.indexOf(pid)
                val ctx = if (pos >= 0) bodyHtml.substring(maxOf(0, pos - 120), minOf(bodyHtml.length, pos + 40)) else ""
                if (!ctx.contains("PACK", true)) continue
                val label = when {
                    ctx.contains("2160", true) || ctx.contains("4K", true) -> "2160p Pack"
                    ctx.contains("1080", true) -> "1080p Pack"
                    else -> "Pack"
                }
                val dataJson = toJson(EpisodeData(links = listOf(LinkEntry(label, "hubdrive", pid, qualityValue(label))))) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = "Season Pack ($label)"
                    this.posterUrl = poster
                })
            }
            // Pack/batch layout (e.g. Study Group): no "EPiSODE N"
            // sections at all. The page offers hubdrive.blog/packs/<slug>
            // EP packs ("480p EP – PACK" ...), greenmotors batch ZIPs
            // ("480p x264 [1.6GB]" ...) and hubdrive file packs
            // ("1080p WEB-DL PACK [21.7GB]"). The context-window pack
            // check above misses these (the label sits AFTER the href,
            // outside its 40-char lookahead), so handle them here with
            // anchor text instead of a raw context window.
            val season = Regex("(?i)Season\\s*(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val pass2UsedIds = mutableSetOf<String>()
            for (ep in episodes) {
                val d = tryParseJson<EpisodeData>(ep.data) ?: continue
                d.links?.forEach { pass2UsedIds.add(it.id) }
            }
            val usedGm = mutableSetOf<String>()
            // (a) EP packs -> per-episode entries, merged by episode
            // number across the quality packs (only when the EPiSODE
            // pass found nothing, so episodes are never duplicated).
            if (episodes.isEmpty()) {
                val byEpisode = sortedMapOf<Int, MutableList<LinkEntry>>()
                for (a in doc.select("a[href*=hubdrive.blog/packs/]")) {
                    var packUrl = a.attr("href")
                    if (packUrl.isEmpty()) continue
                    if (!packUrl.startsWith("http")) packUrl = "https://hubdrive.blog$packUrl"
                    val btnText = cleanTitle(a.text())
                    val qToken = Regex("(?i)(2160|1080|720|480)p").find(btnText)?.value ?: continue
                    fetchHubDrivePack(packUrl, qToken, byEpisode, pass2UsedIds)
                }
                for ((epNum, links) in byEpisode) {
                    val dataJson = toJson(EpisodeData(links = links)) ?: continue
                    episodes.add(newEpisode(dataJson) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = season
                        this.posterUrl = poster
                    })
                }
            }
            // (b) hubdrive file pack/batch links as extra episodes.
            for (a in doc.select("a[href*=hubdrive.blog/file/]")) {
                val href = a.attr("href")
                val id = href.substringAfter("hubdrive.blog/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isEmpty() || id in pass2UsedIds) continue
                val txt = cleanTitle(a.text())
                if (txt.isEmpty()) continue
                pass2UsedIds.add(id)
                val dataJson = toJson(EpisodeData(links = listOf(LinkEntry(txt, "hubdrive", id, qualityValue(txt))))) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = txt
                    this.season = season
                    this.posterUrl = poster
                })
            }
            // (c) greenmotors batch ZIP links as extra episodes
            // (host "gm" resolves via the 4KHDHub greenmotors method).
            for (a in doc.select("a[href*=greenmotors.club]")) {
                val gmUrl = a.attr("href")
                if (gmUrl.isEmpty() || gmUrl in usedGm) continue
                val txt = cleanTitle(a.text())
                if (txt.isEmpty()) continue
                usedGm.add(gmUrl)
                val dataJson = toJson(EpisodeData(links = listOf(LinkEntry(txt, "gm", gmUrl, qualityValue(txt))))) ?: continue
                episodes.add(newEpisode(dataJson) {
                    this.name = txt
                    this.season = season
                    this.posterUrl = poster
                })
            }
            if (episodes.isEmpty()) return null
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        } else {
            // Movie: collect EVERY quality download link (hubcdn + hubdrive),
            // keeping the full button text (with size) so the user can pick
            // the highest, e.g. "HQ 1080p (SOURCE) [32.8GB]".
            // greenmotors links are NOT resolvable, so they are skipped.
            // (Note: this theme does NOT use a div.entry-content container,
            // so scan all links in the page.)
            val links = mutableListOf<LinkEntry>()
            val seenIds = mutableSetOf<String>()
            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                val txt = cleanTitle(a.text())
                if (txt.isEmpty()) continue
                if (!txt.contains("480") && !txt.contains("720") && !txt.contains("1080") && !txt.contains("2160") && !txt.contains("4K", true)) continue
                when {
                    href.contains("hubcdn.club/file/") -> {
                        val id = href.substringAfter("hubcdn.club/file/").substringBefore("/").substringBefore("?").trim()
                        if (id.isNotEmpty() && seenIds.add("cdn:$id")) {
                            links.add(LinkEntry(txt, "hubcdn", id, qualityValue(txt)))
                        }
                    }
                    href.contains("hubdrive.blog/file/") -> {
                        val id = href.substringAfter("hubdrive.blog/file/").substringBefore("/").substringBefore("?").trim()
                        if (id.isNotEmpty() && seenIds.add("drive:$id")) {
                            links.add(LinkEntry(txt, "hubdrive", id, qualityValue(txt)))
                        }
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

    // Fetch one hubdrive.blog/packs/<slug> page (a file listing whose
    // rows are <div class="pack-clean-file"> with
    // .pack-clean-title = "Show.S01E01.480p...mkv",
    // .pack-clean-size = "171.55 MB" and the file id in the row's
    // onclick "window.open('/file/<id>', '_blank')") and merge its
    // per-episode hubdrive file links into byEpisode (keyed by episode
    // number), so each episode accumulates every quality pack.
    private suspend fun fetchHubDrivePack(
        packUrl: String,
        qToken: String,
        byEpisode: MutableMap<Int, MutableList<LinkEntry>>,
        usedIds: MutableSet<String>,
    ) {
        val html = try {
            app.get(packUrl, headers = browserHeaders).text
        } catch (e: Exception) { return }
        val doc = Jsoup.parse(html)
        val q = qualityValue(qToken)
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
            val label = if (size.isNotEmpty()) "$qToken [$size]" else qToken
            byEpisode.getOrPut(epNum) { mutableListOf() }
                .add(LinkEntry(label, "hubdrive", id, q))
        }
    }

    // ---- Link resolvers ----

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
    // (Ported from FourKHDHubProvider; verified live on HDHub4u series
    // tokens — the target here is an hblinks.lol page, resolved further
    // in resolveGmEntry.)
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

    // Resolve one greenmotors button to the direct video file(s) — one
    // entry per HubCloud server, so each server shows as its own source.
    // HDHub4u series tokens decode to an hblinks.lol page, which lists
    // a hubcloud.pw/drive/<code> (preferred) or hubdrive.blog/file/<id>.
    private suspend fun resolveGmEntry(gmUrl: String): List<HubServer> {
        val target = resolveGreenMotors(gmUrl) ?: return emptyList()
        return when {
            target.contains("hubcloud.pw/drive/") -> {
                val code = target.substringAfter("hubcloud.pw/drive/").substringBefore("/").substringBefore("?").trim()
                if (code.isEmpty()) emptyList() else resolveHubCloudServers(code)
            }
            target.contains("hubdrive.blog/file/") -> {
                val id = target.substringAfter("hubdrive.blog/file/").substringBefore("/").substringBefore("?").trim()
                if (id.isEmpty()) emptyList() else resolveHubDrive(id)
            }
            target.contains("hblinks.lol/") -> {
                val html = try {
                    app.get(target, headers = browserHeaders).text
                } catch (e: Exception) { return emptyList() }
                val code = Regex("hubcloud\\.pw/drive/([A-Za-z0-9]+)").find(html)?.groupValues?.getOrNull(1)
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
        // reurl = https://inventoryidea.com/?r=<b64>/<b64>
        val parts = reurl.substringAfter("?r=", "").split("/")
        if (parts.size < 2) return null
        val decoded = b64decode(parts[1]) ?: return null
        return decoded.removePrefix("link=").trim().takeIf { it.startsWith("http") }
    }

    // hubdrive.blog/file/<id> -> HubDrive page with a hubcloud.pw/drive/<code>
    // link, resolved by the same HubCloud chain as KatDrama.
    private suspend fun resolveHubDrive(fileId: String): List<HubServer> {
        val html = try {
            app.get("https://hubdrive.blog/file/$fileId", headers = browserHeaders).text
        } catch (e: Exception) { return emptyList() }
        val code = Regex("hubcloud\\.pw/drive/([A-Za-z0-9]+)").find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        return resolveHubCloudServers(code)
    }

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
        val hubUrl = "https://hubcloud.ist/drive/$hubCode"
        val hubHtml = try { app.get(hubUrl, headers = browserHeaders).text } catch (e: Exception) { return emptyList() }
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

        // Resolve every link entry; the source name keeps the full button
        // text (with size) so the user can pick the highest, e.g.
        // "HubCloud HQ 1080p (SOURCE) [32.8GB]".
        ep.links?.forEach { entry ->
            // HubCdn is a different host (hubcdn.club) with its own single
            // direct file — unchanged. HubCloud entries emit one source
            // per server (FSL, FSLv2, PixelDrain, 10G) so the app shows
            // WHICH server a link is.
            if (entry.host == "hubcdn") {
                val direct = resolveHubCdn(entry.id) ?: return@forEach
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
                return@forEach
            }
            val servers = when (entry.host) {
                "hubdrive" -> resolveHubDrive(entry.id)
                "gm" -> resolveGmEntry(entry.id)
                else -> emptyList()
            }
            for (server in servers) {
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
        }

        return found
    }
}
