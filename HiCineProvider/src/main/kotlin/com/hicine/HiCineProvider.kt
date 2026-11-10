package com.hicine

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

class HiCineProvider : MainAPI() {

    override var mainUrl = "https://hicine.biz"
    override var name = "HiCine"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json",
        "Referer" to "https://hicine.biz/",
    )

    // ---- API models (Jackson) ----

    data class TitleItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("full") val full: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("latest") val latest: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("imdb") val imdb: String? = null,
    )

    data class ListResponse(
        @JsonProperty("items") val items: List<TitleItem>? = null,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("hasMore") val hasMore: Boolean? = null,
    )

    // "Batch/Zip" season-pack download option. The API sends a list of
    // OBJECTS here ({label, k}) — declaring it as List<String> made
    // Jackson throw on any title with a non-empty zip list, which broke
    // the whole title parse -> load() returned null ("HiCine returned
    // nothing") for Naruto, Rings of Power, Crystal Lake, etc.
    data class ZipItem(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("k") val k: String? = null,
    )

    data class TitleGroup(
        @JsonProperty("head") val head: String? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("size") val size: String? = null,
        @JsonProperty("k") val k: String? = null,
        @JsonProperty("zip") val zip: List<ZipItem>? = null,
    )

    data class TitleMeta(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("cast") val cast: List<String>? = null,
    )

    data class TitleDetail(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("full") val full: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("imdb") val imdb: String? = null,
        @JsonProperty("plot") val plot: String? = null,
        @JsonProperty("shots") val shots: List<String>? = null,
        @JsonProperty("groups") val groups: List<TitleGroup>? = null,
        @JsonProperty("meta") val meta: TitleMeta? = null,
    )

    data class HostItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("k") val k: String? = null,
    )

    data class EpisodeItem(
        @JsonProperty("ep") val ep: Int? = null,
        @JsonProperty("hosts") val hosts: List<HostItem>? = null,
    )

    data class EpisodesResponse(
        @JsonProperty("zip") val zip: Boolean? = null,
        @JsonProperty("items") val items: List<EpisodeItem>? = null,
    )

    data class DlOption(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("k") val k: String? = null,
        @JsonProperty("ks") val ks: String? = null,
    )

    data class DlResponse(
        @JsonProperty("host") val host: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("size") val size: String? = null,
        @JsonProperty("options") val options: List<DlOption>? = null,
        @JsonProperty("best") val best: String? = null,
        @JsonProperty("exp") val exp: Long? = null,
    )

    data class GoResponse(
        @JsonProperty("url") val url: String? = null,
    )

    data class StreamResponse(
        @JsonProperty("gw") val gw: String? = null,
        @JsonProperty("t") val t: String? = null,
    )

    // Data passed to loadLinks (as JSON string).
    // kind "group": a movie quality group — loadLinks fetches its episodes
    //   (/hapi/g) then each host's dl, then resolves direct options.
    // kind "host": a series episode's host — loadLinks fetches its dl
    //   directly, then resolves direct options.
    data class LinkEntry(
        @JsonProperty("label") val label: String,
        @JsonProperty("kind") val kind: String,   // "group" or "host"
        @JsonProperty("k") val k: String,
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
        return (raw ?: "").replace(Regex("\\s+"), " ").trim()
    }

    private fun fixPoster(poster: String?): String? {
        if (poster.isNullOrEmpty()) return null
        return if (poster.startsWith("http")) poster else mainUrl + poster
    }

    private fun parseItem(item: TitleItem): com.lagradost.cloudstream3.SearchResponse? {
        val id = item.id ?: return null
        val title = cleanTitle(item.title)
        if (title.isEmpty()) return null
        val url = "$mainUrl/title/$id"
        val poster = fixPoster(item.poster)
        val isSeries = item.kind == "series" || !item.season.isNullOrEmpty()
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private suspend fun fetchList(url: String): ListResponse? {
        return try {
            app.get(url, headers = apiHeaders).parsed()
        } catch (e: Exception) { null }
    }

    // Main page categories map to /hapi/list?src=<src>&cat=<cat>&page=<n>.
    // src: v = Hollywood/OTT, r = Bollywood/MX. Verified from /hapi/home rows.
    override val mainPage = mainPageOf(
        "v|latest" to "Latest in Hollywood",
        "r|latest" to "Latest in Bollywood",
        "v|netflix" to "Netflix",
        "v|prime" to "Prime Video",
        "v|hotstar" to "JioHotstar",
        "v|anime" to "Anime",
        "v|korean" to "K-Drama",
        "r|mx" to "MX Player",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split("|")
        val src = parts.getOrNull(0) ?: "v"
        val cat = parts.getOrNull(1) ?: "latest"
        val url = "$mainUrl/hapi/list?src=$src&cat=$cat&page=$page"
        val res = fetchList(url)
        val list = res?.items?.mapNotNull { parseItem(it) } ?: emptyList()
        return newHomePageResponse(
            HomePageList(request.name, list),
            hasNext = res?.hasMore == true
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/hapi/search?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}&page=$page"
        val res = fetchList(url) ?: return null
        return res.items?.mapNotNull { parseItem(it) }?.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("/title/").substringBefore("/").substringBefore("?").trim()
        if (id.isEmpty()) return null
        val detail: TitleDetail = try {
            app.get("$mainUrl/hapi/t?c=$id", headers = apiHeaders).parsed()
        } catch (e: Exception) { return null }
        val title = cleanTitle(detail.title)
        if (title.isEmpty()) return null
        val poster = fixPoster(detail.meta?.poster) ?: fixPoster(null)
        val isSeries = detail.kind == "series"

        if (isSeries) {
            // Build one CloudStream episode per (season, episode) at the
            // BEST quality available for that season, so the list is not
            // duplicated across qualities. Group keys are fetched lazily
            // per season below (one /hapi/g call each).
            val groups = detail.groups ?: emptyList()
            // Best group per season: 1080p > 720p > 480p, then larger size.
            val bestBySeason = mutableMapOf<String, TitleGroup>()
            for (g in groups) {
                val season = g.season ?: continue
                val cur = bestBySeason[season]
                if (cur == null) {
                    bestBySeason[season] = g
                } else {
                    val curQ = qualityValue(cur.quality)
                    val newQ = qualityValue(g.quality)
                    if (newQ > curQ) bestBySeason[season] = g
                }
            }
            val episodes = mutableListOf<Episode>()
            for ((seasonStr, group) in bestBySeason) {
                val gk = group.k ?: continue
                val seasonNum = seasonStr.toIntOrNull() ?: continue
                val eps: EpisodesResponse = try {
                    app.get("$mainUrl/hapi/g?k=${java.net.URLEncoder.encode(gk, "UTF-8")}", headers = apiHeaders).parsed()
                } catch (e: Exception) { continue }
                for (item in eps.items ?: emptyList()) {
                    val epNum = item.ep ?: continue
                    val hosts = item.hosts ?: continue
                    if (hosts.isEmpty()) continue
                    val links = hosts.mapNotNull { h ->
                        val hk = h.k ?: return@mapNotNull null
                        LinkEntry(
                            label = "${group.quality ?: "HD"} ${h.name ?: h.id ?: ""}".trim(),
                            kind = "host",
                            k = hk,
                            quality = qualityValue(group.quality)
                        )
                    }
                    if (links.isEmpty()) continue
                    val dataJson = toJson(EpisodeData(links = links)) ?: continue
                    episodes.add(newEpisode(dataJson) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = seasonNum
                        this.posterUrl = poster
                    })
                }
            }
            if (episodes.isEmpty()) return null
            episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = detail.plot ?: detail.meta?.description
                this.year = detail.year
            }
        } else {
            // Movie: one link entry per quality group. loadLinks resolves
            // each group (episodes -> hosts -> dl -> direct options).
            val links = (detail.groups ?: emptyList()).mapNotNull { g ->
                val gk = g.k ?: return@mapNotNull null
                val q = qualityValue(g.quality)
                val size = g.size?.takeIf { it.isNotEmpty() }?.let { " [$it]" } ?: ""
                LinkEntry(
                    label = "${g.quality ?: "HD"}$size",
                    kind = "group",
                    k = gk,
                    quality = q
                )
            }
            if (links.isEmpty()) return null
            val dataJson = toJson(EpisodeData(links = links)) ?: return null
            return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = poster
                this.plot = detail.plot ?: detail.meta?.description
                this.year = detail.year
            }
        }
    }

    // ---- Link resolution (mirrors the site's own JS flow) ----

    // Direct option ids the site prefers for watching, in order.
    private val watchOrder = listOf("pixel", "fsl2", "fsl", "server1", "ten", "gdirect")

    private suspend fun fetchDl(hostKey: String): DlResponse? {
        return try {
            app.get("$mainUrl/hapi/dl?k=${java.net.URLEncoder.encode(hostKey, "UTF-8")}", headers = apiHeaders).parsed()
        } catch (e: Exception) { null }
    }

    // Resolve ONE direct option to a playable URL.
    // 1) /hapi/go?k=<ks>&m=u -> JSON {url}
    // 2) /hapi/stream?k=<ks> -> JSON {gw, t} -> "$gw/?t=$t"
    // 3) fall back to the /hapi/go?k=<k>&m=d download URL itself.
    private suspend fun resolveOption(opt: DlOption): String? {
        val ks = opt.ks
        val k = opt.k
        if (!ks.isNullOrEmpty()) {
            // 1) go?m=u
            try {
                val go: GoResponse = app.get(
                    "$mainUrl/hapi/go?k=${java.net.URLEncoder.encode(ks, "UTF-8")}&m=u",
                    headers = apiHeaders
                ).parsed()
                val u = go.url?.takeIf { it.isNotEmpty() }
                if (u != null) return u
            } catch (e: Exception) { }
            // 2) stream
            try {
                val st: StreamResponse = app.get(
                    "$mainUrl/hapi/stream?k=${java.net.URLEncoder.encode(ks, "UTF-8")}",
                    headers = apiHeaders
                ).parsed()
                val gw = st.gw?.trimEnd('/')
                val t = st.t
                if (!gw.isNullOrEmpty() && !t.isNullOrEmpty()) {
                    return "$gw/?t=${java.net.URLEncoder.encode(t, "UTF-8")}"
                }
            } catch (e: Exception) { }
        }
        // 3) direct download URL (CloudStream follows it)
        if (!k.isNullOrEmpty()) {
            return "$mainUrl/hapi/go?k=${java.net.URLEncoder.encode(k, "UTF-8")}&m=d"
        }
        return null
    }

    // Resolve a host (gdirect/vcloud/vdrive) to its direct options.
    // Instant/Resumable hosts (gdirect, vcloud) give kind="direct" options.
    // Mirrors (vdrive) give kind="page" options — not directly playable,
    // so they are skipped (CloudStream cannot open their pages).
    private suspend fun resolveHost(hostKey: String, label: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val dl = fetchDl(hostKey) ?: return false
        val directs = (dl.options ?: emptyList())
            .filter { it.kind == "direct" }
            .sortedBy { watchOrder.indexOf(it.id ?: "").let { idx -> if (idx < 0) 99 else idx } }
        var found = false
        for (opt in directs) {
            val url = resolveOption(opt) ?: continue
            val name = opt.name ?: opt.id ?: "HiCine"
            callback(
                newExtractorLink(
                    source = "HiCine",
                    name = "$name $label",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = "$mainUrl/"
                }
            )
            found = true
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
            when (entry.kind) {
                "host" -> {
                    if (resolveHost(entry.k, entry.label, entry.quality, callback)) found = true
                }
                "group" -> {
                    // Movie quality group: episodes (1 item, ep=null) -> hosts
                    val eps: EpisodesResponse = try {
                        app.get("$mainUrl/hapi/g?k=${java.net.URLEncoder.encode(entry.k, "UTF-8")}", headers = apiHeaders).parsed()
                    } catch (e: Exception) { return@forEach }
                    val hosts = eps.items?.firstOrNull()?.hosts ?: return@forEach
                    // Instant first (gdirect), then Resumable (vcloud).
                    // Mirrors (vdrive) are page links, skipped by resolveHost.
                    for (h in hosts.sortedBy { if (it.id == "gdirect") 0 else if (it.id == "vcloud") 1 else 2 }) {
                        val hk = h.k ?: continue
                        if (resolveHost(hk, entry.label, entry.quality, callback)) found = true
                    }
                }
            }
        }
        return found
    }
}
