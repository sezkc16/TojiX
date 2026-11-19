package com.cinefreak

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
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// Cinefreak (cinefreak.net) — plain Kotlin, no Compose anywhere.
//
// Written from the live site only (home / search-api / post pages /
// cinecloud /f/ pages, all fetched 2026-11-20). No Phisher code.
//
// Site shape (WordPress):
// - Home: post cards (a.movie-card), paginated /page/N/.
// - Search: GET /search-api.php?q=<query>&pg=<page> (Accept: application/json)
//   -> {results: [{t, l, d, c, i, q}], page, total_pages, suggested}.
//   (A POST st=1 key dance exists; the plain GET works, key is a fallback.)
// - Post page: per-quality blocks (h4.movie-title + a.dlbtn-watch) each
//   pointing at a cinecloud.top/f/<token> "Watch Online" page. Series posts
//   have no Watch links; they carry an inline `dataset` JSON with seasons /
//   episodes (sources = the PLAY_API episode code).
//
// Stream wall (all paths Turnstile-gated):
// - cinecloud /f/ "Watch Online": the page renders Turnstile
//   (appearance: 'interaction-only', in #cfTsBox), then POSTs a=play
//   (+ts + CSRF) to itself and sets the real file as #cfVlc.href (on
//   iOS there is no #cfPlayer element, only a JS reference to it).
//   Without ts the server returns a 60 s DECOY mp4 (see isDecoyUrl —
//   NEVER emitted). The Turnstile checkbox needs a human tick, so when
//   the widget is waiting the script returns SHOW_SHEET and the app
//   (b96+) shows the page in its check sheet for the person to tick.
// - PLAY_API (embedded player, series): POST op=play&ep=<code> (+ts)
//   -> a vidzer URL. Best effort; proven only on the phone.
// - "Download Links": Turnstile + Google Drive OAuth — not usable, skipped.
//
// Turnstile is solved through the engine's WebViewResolver (real WKWebView
// on iOS). The script polls the Turnstile token input + the player iframe
// src and fires a same-origin signal request when either appears, so the
// resolve ends the moment the token/file is ready instead of waiting out
// the whole timeout.
class CinefreakProvider : MainAPI() {

    override var mainUrl = "https://cinefreak.net"
    override var name = "Cinefreak"
    override val hasMainPage = true
    override var lang = "bn"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // TMDB only enriches load() metadata (poster/plot). Browse + search use
    // the site's own pages/API, exactly like the site's frontend does.
    // Same TojiX key the Cinejoy provider uses (verified live 2026-11-19).
    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbKey = "8476a7ab80ad76f0936744df0430e67c"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    private val candidateDomains = listOf(
        "https://cinefreak.net",
        "https://www.cinefreak.net"
    )
    private var cachedDomain: String = mainUrl
    private var lastResolvedTime: Long = 0L
    private val domainCacheTtlMs: Long = 1000L * 60 * 60 * 3 // 3 hours

    private val browserUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val browserHeaders = mapOf("User-Agent" to browserUa)

    // ---- decoy guard (hard requirement) ----
    // Without a Turnstile token the server answers the cinecloud a=play POST
    // with a 60 s anti-app slate mp4 instead of the real file. That object
    // must NEVER be returned as a playable link: a decoy response means
    // "no token" — retry via WebViewResolver or fail cleanly.
    private val decoyHost = "pub-67a36bb45be6423e8a3908ef5d2ea5f8.r2.dev"
    private val decoyName = "cinefreak.net - watch online (hd).mp4"

    private fun isDecoyUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.contains(decoyHost)) return true
        // The decoy filename, URL-encoded or plain.
        if (lower.contains("cinefreak.net%20-%20watch%20online%20(hd).mp4")) return true
        if (lower.contains(decoyName)) return true
        if (lower.contains("watch%20online%20(hd).mp4")) return true
        return false
    }

    private data class QualityLink(val label: String, val watchUrl: String)

    private suspend fun resolveDomain(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && lastResolvedTime > 0 && (now - lastResolvedTime) < domainCacheTtlMs) {
            mainUrl = cachedDomain
            return cachedDomain
        }
        val candidates = listOf(cachedDomain) + candidateDomains.filter { it != cachedDomain }
        for (candidate in candidates) {
            try {
                val res = app.get(candidate, timeout = 4)
                if (res.isSuccessful || res.code in 200..399) {
                    val finalUrl = res.okhttpResponse.request.url
                    val detected = "${finalUrl.scheme}://${finalUrl.host}".removeSuffix("/")
                    if (detected.isNotBlank() && detected.startsWith("http")) {
                        cachedDomain = detected
                        lastResolvedTime = now
                        mainUrl = detected
                        return detected
                    }
                }
            } catch (_: Exception) {
            }
        }
        mainUrl = cachedDomain.ifBlank { "https://cinefreak.net" }
        return mainUrl
    }

    private fun withResolvedDomain(url: String): String {
        return try {
            val uri = URI(url)
            val path = (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
            if (path.isBlank()) url else mainUrl + path
        } catch (_: Exception) {
            url
        }
    }

    private fun typeOfTitle(title: String): TvType {
        val lower = title.lowercase(Locale.ROOT)
        return if (lower.contains("season") || lower.contains("web series") ||
            lower.contains("tv series") || lower.contains("episode")
        ) TvType.TvSeries else TvType.Movie
    }

    // "The Odyssey (2026) Dual Audio [Hindi & English] BluRay ..." ->
    // name "The Odyssey", year 2026, audio "[Hindi & English]".
    private fun cleanTitle(raw: String): Triple<String, Int?, String?> {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        val yearMatch = Regex("""^(.*?)\s*\((\d{4})\)""").find(text)
        val name = yearMatch?.groupValues?.getOrNull(1)?.trim()?.ifBlank { text } ?: text
        val year = yearMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
        val audio = Regex("""\[([^\]]*(?:Hindi|English|Dual Audio|Bengali|Bangla)[^\]]*)\]""")
            .find(text)?.groupValues?.getOrNull(0)
        return Triple(name, year, audio)
    }

    private fun displayTitle(name: String, year: Int?, audio: String?): String {
        val base = if (year != null) "$name ($year)" else name
        return if (!audio.isNullOrBlank()) "$base $audio" else base
    }

    private fun qualityLabelFromH4(h4: String, cleanTitleText: String): String {
        val stripped = h4.removePrefix(cleanTitleText).trim()
        if (stripped.isNotBlank()) return stripped
        val m = Regex("""(SD|HD|HEVC|HQ|BluRay|4K-?2160p|2160p|1080p|720p|480p)\b.*""").find(h4)
        return m?.value?.trim() ?: h4
    }

    private fun parseQualities(doc: Document, cleanTitleText: String): List<QualityLink> {
        val out = mutableListOf<QualityLink>()
        val seen = mutableSetOf<String>()
        // One block per quality: an h4.movie-title followed by its
        // div.dlbtn-container. Only the FIRST block is wrapped in its own
        // div (div.c37b704); the rest are bare h4 + container siblings
        // under div.download-links-div — so the v1 selector
        // (div.download-links-div > div) found only the first block
        // (480p) and missed the other five. Pair each h4 with the
        // container that follows it instead. (418, 2026-11-20: 6 watch
        // links — 480p, HEVC 720p, HD 720p, HD 1080p, HQ 1080p, 4K-2160p;
        // the bare 480p block carries Download only, no Watch Online.)
        val heads = doc.select("div.download-links-div h4.movie-title")
            .ifEmpty { doc.select("h4.movie-title") }
        for (h4 in heads) {
            val label = qualityLabelFromH4(h4.text().trim(), cleanTitleText)
            val container = h4.nextElementSiblings()
                .firstOrNull { it.tagName() == "div" && it.hasClass("dlbtn-container") }
                ?: continue
            val watch = container.selectFirst("a.dlbtn-watch")?.attr("href")?.trim()
                ?: continue
            if (!watch.startsWith("http")) continue
            if (!seen.add(label)) continue
            out.add(QualityLink(label, watch))
        }
        return out
    }

    // The inline player dataset: {"type":"movie","sources":"m"} or
    // {"type":"series","seasons":[{"season_name":"Season 02","episodes":[
    //   {"k":"e","ep_num":"01","ep_title":"Episode 01",
    //    "ep_meta":"Season 02 • Hindi & English","sources":"e0_0","h":[...]}]}]}
    private fun extractDataset(html: String): JSONObject? {
        val start = html.indexOf("const dataset = ")
        if (start < 0) return null
        var i = html.indexOf('{', start)
        if (i < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        var j = i
        while (j < html.length) {
            val c = html[j]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return try {
                                JSONObject(html.substring(i, j + 1))
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }
            }
            j++
        }
        return null
    }

    private fun extractPlayApi(html: String): String? {
        val key = Regex("""wp-json(?:\\?/)+cf(?:\\?/)+v1(?:\\?/)+pl(?:\\?/)+([A-Za-z0-9_-]+)""")
            .find(html)?.groupValues?.getOrNull(1) ?: return null
        return "$mainUrl/wp-json/cf/v1/pl/$key"
    }

    private suspend fun tmdbEnrich(
        name: String,
        year: Int?,
        isTv: Boolean
    ): Pair<String?, String?> {
        return try {
            val kind = if (isTv) "tv" else "movie"
            val q = java.net.URLEncoder.encode(name, "UTF-8")
            val yearParam = if (isTv) {
                year?.let { "&first_air_date_year=$it" } ?: ""
            } else {
                year?.let { "&year=$it" } ?: ""
            }
            val json = JSONObject(
                app.get("$tmdbBase/search/$kind?query=$q&api_key=$tmdbKey$yearParam").text
            )
            val first = json.optJSONArray("results")?.optJSONObject(0) ?: return null to null
            val poster = first.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { "$imageBase$it" }
            val overview = first.optString("overview").takeIf { it.isNotBlank() }
            poster to overview
        } catch (_: Exception) {
            null to null
        }
    }

    private fun toSearchResponse(
        title: String,
        url: String,
        poster: String?
    ): SearchResponse? {
        val (name, year, audio) = cleanTitle(title)
        if (name.isBlank()) return null
        val display = displayTitle(name, year, audio)
        return if (typeOfTitle(title) == TvType.TvSeries) {
            newTvSeriesSearchResponse(display, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(display, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Latest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()
        // Build from the current mainUrl (request.data was frozen at
        // construction time and would go stale if the domain moved).
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url, headers = browserHeaders).document
        val items = mutableListOf<SearchResponse>()
        for (card in doc.select("a.movie-card")) {
            val href = card.attr("href").trim()
            if (href.isBlank()) continue
            val title = card.selectFirst("h3.movie-card-title")?.text()?.trim() ?: continue
            val poster = card.selectFirst("img")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
            toSearchResponse(title, href, poster)?.let { items.add(it) }
        }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    // The site's search API. A POST st=1 key dance exists, but the plain GET
    // works; the key is only fetched as a fallback (and retried once on renew).
    private suspend fun searchApiJson(query: String, page: Int): JSONObject? {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val apiUrl = "$mainUrl/search-api.php?q=$encoded&pg=$page"
        fun parse(text: String): JSONObject? {
            return try {
                JSONObject(text)
            } catch (_: Exception) {
                null
            }
        }
        try {
            val res = app.get(apiUrl, headers = mapOf("Accept" to "application/json"))
            val json = parse(res.text)
            if (json != null && !json.has("error") && !json.optBoolean("renew", false)) return json
            if (json?.optBoolean("renew", false) == true) {
                // Fall through to the key dance below.
            } else if (json != null) {
                return json
            }
        } catch (_: Exception) {
        }
        return try {
            val keyJson = parse(
                app.post(
                    "$mainUrl/search-api.php",
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = mapOf("st" to "1")
                ).text
            )
            val key = keyJson?.optString("k").orEmpty()
            val res = app.get(
                apiUrl,
                headers = mapOf("Accept" to "application/json", "X-CF-ST" to key)
            )
            parse(res.text)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        resolveDomain()
        val json = searchApiJson(query, page) ?: return newSearchResponseList(emptyList())
        val results = json.optJSONArray("results") ?: JSONArray()
        val totalPages = json.optInt("total_pages", 1)
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val title = item.optString("t").trim()
            val slug = item.optString("l").trim()
            if (title.isBlank() || slug.isBlank()) continue
            val poster = item.optString("i").trim().takeIf { it.isNotBlank() }
            toSearchResponse(title, "$mainUrl/$slug/", poster)?.let { items.add(it) }
        }
        return newSearchResponseList(items, page < totalPages)
    }

    override suspend fun load(data: String): LoadResponse? {
        resolveDomain()
        val postUrl = data.substringBefore("#cfep=").trim()
        val res = app.get(withResolvedDomain(postUrl), headers = browserHeaders)
        val doc = res.document
        val html = res.text
        val h1 = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val (name, year, audio) = cleanTitle(h1)
        if (name.isBlank()) return null
        val display = displayTitle(name, year, audio)
        val dataset = extractDataset(html)
        val isSeries = dataset?.optString("type") == "series" ||
            typeOfTitle(h1) == TvType.TvSeries
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?.trim()?.takeIf { it.isNotBlank() }

        // Poster: the post embeds its TMDB poster; TMDB enriches plot/poster.
        var poster = doc.select("img[src*=image.tmdb.org]").firstOrNull()
            ?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
        var overview = plot
        val (tmdbPoster, tmdbOverview) = tmdbEnrich(name, year, isSeries)
        if (poster.isNullOrBlank()) poster = tmdbPoster
        if (overview.isNullOrBlank()) overview = tmdbOverview
        if (poster.isNullOrBlank() && tmdbPoster != null) poster = tmdbPoster

        if (!isSeries) {
            return newMovieLoadResponse(display, data, TvType.Movie, data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.year = year
                this.plot = overview
            }
        }

        // Series: episodes from the inline dataset.
        val episodes = mutableListOf<Episode>()
        val seasons = dataset?.optJSONArray("seasons") ?: JSONArray()
        for (s in 0 until seasons.length()) {
            val seasonObj = seasons.optJSONObject(s) ?: continue
            val seasonNum = Regex("""(\d+)""")
                .find(seasonObj.optString("season_name")).let {
                    it?.groupValues?.getOrNull(1)?.toIntOrNull()
                } ?: (s + 1)
            val eps = seasonObj.optJSONArray("episodes") ?: continue
            for (e in 0 until eps.length()) {
                val epObj = eps.optJSONObject(e) ?: continue
                val epNum = epObj.optString("ep_num").toIntOrNull() ?: (e + 1)
                val epCode = epObj.optString("sources").trim()
                if (epCode.isBlank()) continue
                val epTitle = epObj.optString("ep_title").trim().ifBlank { "Episode $epNum" }
                val epData = "$postUrl#cfep=$epCode"
                episodes.add(newEpisode(epData) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = epNum
                })
            }
        }
        // A series post with no parseable dataset still lists the title;
        // the episode list stays empty and loadLinks fails cleanly.
        return newTvSeriesLoadResponse(display, data, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.year = year
            this.plot = overview
        }
    }

    // ---- loadLinks ----

    private data class SolveResult(val token: String?, val directUrl: String?)

    // The marker the injected script returns when the Turnstile widget is
    // waiting for a person to tick its checkbox (it renders
    // appearance: 'interaction-only'): the app (b96+) shows the page in
    // its check sheet so the person can tick it. Older apps ignore this
    // and the resolve just times out; the script callback ignores it too.
    private val showSheetMarker = "SHOW_SHEET"

    // Solves Turnstile for one page in the engine's hidden browser (real
    // WKWebView on iOS) and returns either the Turnstile token or a direct
    // file URL the page itself resolved (its #cfVlc href on iOS — the iOS
    // /f/ page has no #cfPlayer element). The injected script polls both
    // after every request; when either appears it fires a same-origin
    // signal request, which the intercept regex matches so the resolve
    // ends at once instead of timing out. It also clicks the post page's
    // .cft-start play overlay, because the embedded player is
    // click-to-play (nothing loads until clicked). 60 s (v2, was 30 s):
    // the widget can take a while to render on a slow page.
    private suspend fun solveTurnstile(pageUrl: String, timeoutMs: Long = 60_000L): SolveResult {
        val script = """
            (function(){
              var t = document.querySelector('input[name="cf-turnstile-response"]');
              var tok = (t && t.value) ? t.value : '';
              var f = document.getElementById('cfPlayer');
              var src = (f && f.src && f.src !== 'about:blank') ? f.src : '';
              var v = document.getElementById('cfVlc');
              var vlc = (v && v.href && v.href.indexOf('vlc') === 0) ? v.href : '';
              if (!tok && !src && !vlc) {
                var b = document.querySelector('.cft-start');
                if (b) { try { b.click(); } catch(e) {} }
                // The Turnstile widget is waiting for a person (it renders
                // interaction-only, and the hidden page cannot tick it):
                // ask the app to show this page in its check sheet.
                var w = document.querySelector('#cfTsBox iframe') ||
                        document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                if (w) return 'SHOW_SHEET';
                return '';
              }
              var out = tok ? ('TS:' + tok) : ('URL:' + (src || vlc));
              try { fetch('/__cf_done__?v=' + encodeURIComponent(out)); } catch(e) {}
              return out;
            })()
        """.trimIndent()
        val viaCallback = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val resolver = WebViewResolver(
            interceptUrl = Regex("__cf_done__"),
            script = script,
            // The engine hands the script's return value back as JSON text
            // (a string comes back quoted, e.g. '"TS:..."'), so strip the
            // quotes; the SHOW_SHEET marker is not a result, ignore it.
            scriptCallback = { result ->
                val clean = result.trim().removeSurrounding("\"")
                if (clean.isNotBlank() && clean != showSheetMarker) viaCallback.set(clean)
            },
            timeout = timeoutMs
        )
        val raw: String? = try {
            val request = Request.Builder().url(pageUrl).get().build()
            val (found, _) = resolver.resolveUsingWebView(request)
            found?.url?.toString()
                ?.substringAfter("__cf_done__?v=", "")
                ?.takeIf { it.isNotBlank() }
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: viaCallback.get()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            viaCallback.get()?.takeIf { it.isNotBlank() }
        }
        return when {
            raw == null -> SolveResult(null, null)
            raw.startsWith("TS:") -> SolveResult(
                raw.removePrefix("TS:").takeIf { it.isNotBlank() },
                null
            )

            // The iOS /f/ page has no #cfPlayer element (only a JS
            // reference to it), so the file arrives as #cfVlc.href — a
            // vlc:// URL. mpv cannot play vlc:// raw: unwrap it to the
            // real file URL first.
            raw.startsWith("URL:") -> SolveResult(
                null,
                raw.removePrefix("URL:").takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("vlc")) extractFileFromVlc(it) else it }
            )

            else -> SolveResult(null, null)
        }
    }

    private suspend fun fetchCsrf(watchUrl: String): String? {
        return try {
            val doc = app.get(watchUrl, headers = browserHeaders).document
            doc.selectFirst("meta[name=X-CSRF-TOKEN]")?.attr("content")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // The cinecloud a=play POST: body a=play&ts=<token>&X-CSRF-TOKEN=<csrf>.
    // Returns the real file URL, or null (401 = token rejected, not_ready,
    // no ok, or a decoy — the decoy is never returned).
    private suspend fun postPlay(watchUrl: String, token: String, csrf: String): String? {
        val res = try {
            app.post(
                watchUrl,
                headers = mapOf(
                    "Origin" to "https://cinecloud.top",
                    "Referer" to watchUrl,
                    "User-Agent" to browserUa,
                    "Accept" to "application/json"
                ),
                referer = watchUrl,
                data = mapOf("a" to "play", "ts" to token, "X-CSRF-TOKEN" to csrf)
            )
        } catch (_: Exception) {
            return null
        }
        if (res.code == 401) return null
        val json = try {
            JSONObject(res.text)
        } catch (_: Exception) {
            return null
        }
        if (json.optString("state") == "not_ready") return null
        if (!json.optBoolean("ok", false)) return null
        json.optString("url").takeIf { it.isNotBlank() }?.let { return it }
        val vlc = json.optString("vlc").takeIf { it.isNotBlank() } ?: return null
        return extractFileFromVlc(vlc)
    }

    private fun extractFileFromVlc(vlc: String): String? {
        val decoded = try {
            java.net.URLDecoder.decode(vlc, "UTF-8")
        } catch (_: Exception) {
            vlc
        }
        val param = Regex("""[?&]url=([^&]+)""").find(decoded)?.groupValues?.getOrNull(1)
        if (param != null) {
            return try {
                java.net.URLDecoder.decode(param, "UTF-8")
            } catch (_: Exception) {
                param
            }
        }
        return decoded.removePrefix("vlc://").removePrefix("vlc-x-callback://")
            .takeIf { it.startsWith("http") }
    }

    private suspend fun emitLink(
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>,
        label: String,
        fileUrl: String,
        referer: String
    ): Boolean {
        if (isDecoyUrl(fileUrl)) return false
        if (!emitted.add(fileUrl)) return false
        val type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(
                source = "Cinefreak",
                name = "Cinefreak $label",
                url = fileUrl,
                type = type
            ) {
                this.quality = getQualityFromName(label)
                this.referer = referer
                this.headers = mapOf(
                    "Origin" to "https://cinecloud.top",
                    "Referer" to referer,
                    "User-Agent" to browserUa
                )
            }
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveDomain()
        val epCode = data.substringAfter("#cfep=", "").takeIf { it.isNotBlank() }
        val postUrl = withResolvedDomain(data.substringBefore("#cfep=").trim())
        val found = AtomicBoolean(false)
        val emitted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        if (epCode != null) {
            // Series episode: PLAY_API with the episode code.
            return loadEpisodeLinks(postUrl, epCode, found, emitted, callback)
        }

        // Movie: qualities from the post page.
        val res = try {
            app.get(postUrl, headers = browserHeaders)
        } catch (_: Exception) {
            return false
        }
        val doc = res.document
        val h1 = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val (name, year, audio) = cleanTitle(h1)
        val display = displayTitle(name, year, audio)
        val qualities = parseQualities(doc, display)
        if (qualities.isEmpty()) return false

        // One Turnstile solve; the token is per-visitor and works for every
        // quality on the post (the site: "One Turnstile per visitor").
        var token: String? = null
        val first = qualities.first()
        var solve = solveTurnstile(first.watchUrl)
        token = solve.token
        solve.directUrl?.let { url ->
            if (emitLink(callback, emitted, first.label, url, first.watchUrl)) found.set(true)
        }
        // Nothing came back (no token, no direct file): retry once on the
        // same page. On a b96+ app the first solve already showed the page
        // to the person, so the retry reuses the clearance they gave; on
        // an older app it is a second chance for a slow widget.
        if (token == null && !found.get()) {
            solve = solveTurnstile(first.watchUrl)
            token = solve.token
            solve.directUrl?.let { url ->
                if (emitLink(callback, emitted, first.label, url, first.watchUrl)) found.set(true)
            }
        }
        // No token but the first quality resolved directly: try once more on
        // the best remaining quality to get a token for the rest.
        if (token == null && found.get()) {
            val best = qualities.firstOrNull { it.label.contains("1080p") }
                ?: qualities.getOrNull(1)
            if (best != null && best.watchUrl != first.watchUrl) {
                val retry = solveTurnstile(best.watchUrl, timeoutMs = 20_000L)
                token = retry.token
                retry.directUrl?.let { url ->
                    if (emitLink(callback, emitted, best.label, url, best.watchUrl)) found.set(true)
                }
            }
        }
        val ts = token ?: return found.get()
        qualities.amap { q ->
            try {
                val csrf = fetchCsrf(q.watchUrl) ?: return@amap
                val fileUrl = postPlay(q.watchUrl, ts, csrf) ?: return@amap
                if (emitLink(callback, emitted, q.label, fileUrl, q.watchUrl)) found.set(true)
            } catch (_: Exception) {
            }
        }
        return found.get()
    }

    // Series episode via the embedded player's PLAY_API. Best effort: the
    // player is click-to-play, so the WebView script clicks .cft-start
    // first; if no token appears the call fails cleanly (no links, no decoy).
    private suspend fun loadEpisodeLinks(
        postUrl: String,
        epCode: String,
        found: AtomicBoolean,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try {
            app.get(postUrl, headers = browserHeaders).text
        } catch (_: Exception) {
            return false
        }
        val playApi = extractPlayApi(html) ?: return false
        val solve = solveTurnstile(postUrl)
        val token = solve.token ?: return false
        val res = try {
            app.post(
                playApi,
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to postUrl,
                    "User-Agent" to browserUa,
                    "Accept" to "application/json"
                ),
                referer = postUrl,
                data = mapOf("op" to "play", "ep" to epCode, "ts" to token)
            )
        } catch (_: Exception) {
            return false
        }
        if (res.code == 401) return false
        val json = try {
            JSONObject(res.text)
        } catch (_: Exception) {
            return false
        }
        if (json.optString("state") == "uploading") return false
        val url = json.optString("url").takeIf { it.isNotBlank() }
            ?: json.optString("vlc").takeIf { it.isNotBlank() }?.let { extractFileFromVlc(it) }
            ?: return false
        if (isDecoyUrl(url)) return false
        // The PLAY_API answer is a vidzer embed page, not always a direct
        // file: scrape it for a direct file as a best effort.
        val fileUrl = if (url.contains(".mp4") || url.contains(".m3u8") || url.contains(".mkv")) {
            url
        } else {
            scrapeFileFromPage(url) ?: return false
        }
        if (emitLink(callback, emitted, "Episode", fileUrl, postUrl)) found.set(true)
        return found.get()
    }

    private suspend fun scrapeFileFromPage(pageUrl: String): String? {
        return try {
            val html = app.get(pageUrl, headers = browserHeaders).text
            Regex("""https?://[^\s"'\\]+\.(?:mp4|m3u8|mkv)(?:\?[^\s"'\\]*)?""")
                .find(html)?.value?.takeIf { !isDecoyUrl(it) }
        } catch (_: Exception) {
            null
        }
    }
}
