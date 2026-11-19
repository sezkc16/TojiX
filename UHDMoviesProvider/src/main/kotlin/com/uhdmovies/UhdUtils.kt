package com.uhdmovies

import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import okhttp3.CookieJar
import okhttp3.Headers
import java.net.URI
import java.util.Locale

/** Small URL helpers for the UHDMovies chain. */
fun getBaseUrl(url: String): String {
    val uri = URI(url)
    return "${uri.scheme}://${uri.host}"
}

/** Resolve a possibly-relative [url] against a [domain] (scheme + host). */
fun fixUrlWithBase(url: String, domain: String): String {
    if (url.startsWith("http")) return url
    if (url.isEmpty()) return ""
    if (url.startsWith("//")) return "https:$url"
    if (url.startsWith("/")) return domain + url
    return "$domain/$url"
}

/** Search-card quality from a post title, same mapping the site's own
 *  listings imply (4K / 1080p / 720p / WEB-DL / Blu-ray / cam / ...). */
fun getSearchQuality(check: String?): SearchQuality? {
    val lower = check?.lowercase(Locale.ROOT) ?: return null
    if (lower.contains("4k") || lower.contains("uhd") || lower.contains("2160p")) {
        return SearchQuality.FourK
    }
    if (lower.contains("1440p") || lower.contains("qhd")) {
        return SearchQuality.BlueRay
    }
    if (lower.contains("1080p") || lower.contains("fullhd")) {
        return SearchQuality.HD
    }
    if (lower.contains("720p")) {
        return SearchQuality.SD
    }
    if (lower.contains("webrip") || lower.contains("web-dl")) {
        return SearchQuality.WebRip
    }
    if (lower.contains("bluray")) {
        return SearchQuality.BlueRay
    }
    if (lower.contains("hdts") || lower.contains("hdcam") || lower.contains("hdtc")) {
        return SearchQuality.HdCam
    }
    if (lower.contains("dvd")) {
        return SearchQuality.DVD
    }
    if (lower.contains("camrip") || lower.contains("rip")) {
        return SearchQuality.CamRip
    }
    if (lower.contains("cam")) {
        return SearchQuality.Cam
    }
    if (lower.contains("hdrip") || lower.contains("hdtv")) {
        return SearchQuality.HD
    }
    if (lower.contains("hq")) {
        return SearchQuality.HQ
    }
    return null
}

// ---------------------------------------------------------------------------
// Gate bypass.
//
// The download buttons on uhdmovies.ceo point at a short-link gate
// (thenaukriadda.in and friends). The gate is generic, not site-specific:
// it serves "Please wait..." auto-submit forms, plants tokens via JS
// (lp_go / ?go=), and finally redirects at a driveleech / driveseed
// landing page or a video-seed.xyz/?url=<token> link.
//
// The bypass runs on an isolated HTTP client (NO_COOKIES jar, its own
// LinkedHashMap jar, Chrome 126 UA) with manual redirect handling, so
// no gate cookie ever leaks into the app's shared client.
// ---------------------------------------------------------------------------

private const val GATE_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/**
 * Follow the gate chain starting at [url] and return the final link
 * (a driveleech/driveseed landing page or a video-seed.xyz/?url= link),
 * or null when the chain cannot be resolved.
 */
suspend fun bypassHrefli(url: String): String? {
    val isolatedApp = Requests(
        app.baseClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    )
    val jar = linkedMapOf<String, String>()
    val baseUrl = getBaseUrl(url)
    var currentUrl = url
    var res = fetchManual(
        isolatedApp, jar, url,
        isPost = false, postData = null, referer = null
    )
    var lastFormData: Map<String, String> = emptyMap()

    // Form rounds (max 5): submit the first form whose inputs carry an
    // _lp / _wp field — the gate's auto-submit forms.
    var formRounds = 0
    while (formRounds < 5) {
        val form = res.document.select("form").firstOrNull { f ->
            f.select("input").any {
                it.attr("name").startsWith("_lp") ||
                    it.attr("name").startsWith("_wp")
            }
        } ?: break
        val action = form.attr("action").ifBlank { currentUrl }
        val formUrl = fixUrlWithBase(action, baseUrl)
        val data = form.select("input")
            .associate { it.attr("name") to it.attr("value") }
        if (data.isEmpty()) break
        res = fetchManual(
            isolatedApp, jar, formUrl,
            isPost = true, postData = data, referer = currentUrl
        )
        currentUrl = formUrl
        lastFormData = data
        formRounds++
    }

    val body = res.text

    // lp_go token: the gate plants a cookie via sc("lp-...", "...") and
    // expects a GET on ?lp_go=<token>.
    val lpToken = Regex("lp_go=(lp-[a-zA-Z0-9]+)")
        .find(body)?.groupValues?.getOrNull(1)
    if (lpToken != null) {
        val cookieVal = Regex(
            "sc\\(\\s*[\"']${Regex.escape(lpToken)}[\"']\\s*,\\s*[\"']([^\"']+)[\"']"
        ).find(body)?.groupValues?.getOrNull(1)
        if (cookieVal != null) {
            jar[lpToken] = cookieVal
                .replace("\\/", "/").replace(" ", "+").trim()
            val targetUrl = "$baseUrl/?lp_go=$lpToken"
            val targetRes = fetchManual(
                isolatedApp, jar, targetUrl,
                isPost = false, postData = null, referer = currentUrl
            )
            val targetBody = targetRes.text
            val destination = extractDestination(targetBody, targetRes)
            if (!destination.isNullOrBlank() && destination != "/404") {
                val base = getBaseUrl(targetRes.url.ifBlank { targetUrl })
                return fixUrlWithBase(
                    destination.replace("\\/", "/").trim(), base
                )
            }
            if (targetRes.url.isNotBlank() && targetRes.url != targetUrl &&
                !targetRes.url.contains("lp_go=")
            ) {
                return targetRes.url
            }
        }
    }

    // ?go=<token>: a script carries the token; the _wp_http2 form value
    // rides along as a cookie under the token's name.
    val skToken = res.document.selectFirst("script:containsData(?go=)")
        ?.data()
        ?.substringAfter("?go=", "")
        ?.substringBefore("\"")
        ?.substringBefore("'")
        ?.takeIf { it.isNotBlank() }
    if (skToken != null) {
        val wpHttp2 = lastFormData["_wp_http2"].orEmpty()
        if (wpHttp2.isNotBlank()) jar[skToken] = wpHttp2
        val driveRes = fetchManual(
            isolatedApp, jar, "$baseUrl?go=$skToken",
            isPost = false, postData = null, referer = currentUrl
        )
        val driveUrl = driveRes.document
            .selectFirst("meta[http-equiv=refresh]")
            ?.attr("content")
            ?.substringAfter("url=", "")
            ?.takeIf { it.isNotBlank() }
            ?: driveRes.url
        if (driveUrl.isNullOrBlank()) return null
        val drivePage = try {
            app.get(driveUrl).text
        } catch (_: Exception) {
            return null
        }
        val path = Regex("replace\\(\\s*[\"']([^\"']+)[\"']")
            .find(drivePage)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
        if (!path.isNullOrBlank() && path != "/404") {
            return fixUrlWithBase(path, getBaseUrl(driveUrl))
        }
        return null
    }

    // Last resort: a bare replace("...") or meta refresh on the last page.
    val direct = Regex("replace\\(\\s*[\"']([^\"']+)[\"']\\s*\\)")
        .find(body)?.groupValues?.getOrNull(1)
        ?: res.document.selectFirst("meta[http-equiv=refresh]")
            ?.attr("content")?.substringAfter("url=", "")
    val cleaned = direct?.replace("\\/", "/")?.trim()
    if (cleaned.isNullOrBlank() || cleaned == "/404" ||
        cleaned.contains("{") || cleaned.contains("}")
    ) return null
    if (cleaned.startsWith("http") || cleaned.startsWith("/")) {
        return fixUrlWithBase(cleaned, baseUrl)
    }
    return null
}

/** Best-effort destination out of a gate page body, in priority order:
 *  replace("...") in scripts, meta refresh, window.location, the
 *  Location header. */
private fun extractDestination(body: String, res: NiceResponse): String? {
    Regex("replace\\(\\s*[\"']([^\"']+)[\"']\\s*\\)")
        .find(body)?.groupValues?.getOrNull(1)?.let { return it }
    res.document.selectFirst("meta[http-equiv=refresh]")
        ?.attr("content")?.substringAfter("url=", "")
        ?.takeIf { it.isNotBlank() }?.let { return it }
    Regex("window\\.location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']")
        .find(body)?.groupValues?.getOrNull(1)?.let { return it }
    Regex("location\\.href\\s*=\\s*[\"']([^\"']+)[\"']")
        .find(body)?.groupValues?.getOrNull(1)?.let { return it }
    return res.headers["location"]
}

/**
 * Manual fetch: no auto-redirect, up to 10 hops. POST only on the first
 * request, then GETs. Set-Cookie headers are absorbed into [jar] and the
 * jar is sent back as one Cookie header on every hop.
 */
private suspend fun fetchManual(
    isolatedApp: Requests,
    jar: MutableMap<String, String>,
    fetchUrl: String,
    isPost: Boolean,
    postData: Map<String, String>?,
    referer: String?
): NiceResponse {
    var nextUrl = fetchUrl
    var usePost = isPost
    var lastRes: NiceResponse? = null
    repeat(10) {
        val hdrs = mutableMapOf("User-Agent" to GATE_UA)
        if (referer != null) hdrs["Referer"] = referer
        val cookie = cookieHeader(jar)
        if (cookie.isNotEmpty()) hdrs["Cookie"] = cookie
        val res = if (usePost && postData != null) {
            isolatedApp.post(
                nextUrl, headers = hdrs,
                data = postData, allowRedirects = false
            )
        } else {
            isolatedApp.get(
                nextUrl, headers = hdrs, allowRedirects = false
            )
        }
        absorbHeaders(jar, res.headers)
        lastRes = res
        // POST responses are returned as-is, without following redirects.
        if (usePost) return res
        val location = res.headers["location"]
        if (res.code !in 300..399 || location == null) return res
        nextUrl = fixUrlWithBase(location, getBaseUrl(nextUrl))
        usePost = false
    }
    return lastRes!!
}

private fun absorbHeaders(jar: MutableMap<String, String>, headers: Headers) {
    for (setCookie in headers.values("set-cookie")) {
        val kv = setCookie.split(";").firstOrNull()?.trim() ?: continue
        val eqIdx = kv.indexOf('=')
        if (eqIdx <= 0) continue
        val k = kv.substring(0, eqIdx).trim()
        val v = kv.substring(eqIdx + 1).trim()
        if (v.lowercase(Locale.ROOT) == "deleted" || v.isEmpty()) {
            jar.remove(k)
        } else {
            jar[k] = v
        }
    }
}

private fun cookieHeader(jar: Map<String, String>): String {
    return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
