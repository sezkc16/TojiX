package com.cncverse

import com.lagradost.nicehttp.Requests
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.UUID

/**
 * Shared helpers for the CNC Verse (Netflix / NetMirror) provider.
 *
 * Chain verified live (2026-11-11):
 *  1. POST https://net52.cc/verify.php (g-recaptcha-response = random UUID)
 *     -> 301 + Set-Cookie: t_hash_t=... (the verification cookie)
 *  2. Catalogue (mobile/home, mobile/search.php, mobile/post.php,
 *     mobile/episodes.php) with that cookie + ott=nf + hd=on
 *  3. GET https://<mobiledetect*>/newtv/checknewtv.php (with the
 *     X-Requested-With: NetmirrorNewTV v1.0 header — without it the server
 *     404s) -> {"token_hash": "<base64 api base>"} (e.g. https://tv.imgcdn.kim)
 *  4. GET <apiBase>/newtv/otp.php (otp: 111111) -> {"usertoken": "..."}
 *  5. GET <apiBase>/newtv/player.php?id=<id> (Ott: nf, Usertoken: <token>)
 *     -> {"status": "ok", "video_link": "https://tv.imgcdn.kim/newtv/hls/nf/<id>.m3u8?...",
 *         "referer": "https://net52.cc"}
 *
 * No SharedPreferences / Context is used: the cookie and the API base are
 * cached in memory (15h / 24h, matching the original) and simply re-resolved
 * after an app restart. No donation code is included.
 */
object CNCVerseUtils {

    // ------------------------------------------------------------------ cache

    private const val COOKIE_TTL_MS = 54_000_000L // 15h
    private const val API_BASE_TTL_MS = 86_400_000L // 24h

    @Volatile
    private var cachedCookie: String? = null

    @Volatile
    private var cachedCookieAt: Long = 0L

    @Volatile
    private var cachedApiBase: String? = null

    @Volatile
    private var cachedApiBaseAt: Long = 0L

    // ------------------------------------------------------------- constants

    /** Chrome 147 (Windows) UA, used for the verify.php bypass. */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

    /** Firefox 136 UA, used for the NewTV API calls. */
    private const val NEWTV_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0"

    /** Firefox 136 UA, used for the otp.php call. */
    private const val OTP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.Gatu v1.0"

    /**
     * Base64 of the full https:// URL of each candidate NewTV check domain.
     * Copied exactly from the CNC Verse source. The first live domain wins.
     */
    private val newTvDomains = listOf(
        "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
        "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNpdGU=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNwYWNl",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LnN0b3Jl",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LnZpcA==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0Lndpa2k=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0Lnh5eg==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5hcnQ=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5jYw==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbmZv",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbms=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5saXZl",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5wcm8=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5zdG9yZQ==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy50b3A=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0cy54eXo="
    )

    /** Headers for the NewTV check / player calls. The X-Requested-With header is required. */
    private val newTvBaseHeaders: Map<String, String> = mapOf(
        "Cache-Control" to "no-cache, no-store, must-revalidate",
        "Pragma" to "no-cache",
        "Expires" to "0",
        "X-Requested-With" to "NetmirrorNewTV v1.0",
        "User-Agent" to NEWTV_UA,
        "Accept" to "application/json, text/plain, */*"
    )

    // ------------------------------------------------------------ base64 util

    /** Decodes a base64 string, tolerating missing padding. Returns "" on failure. */
    fun decodeBase64(value: String): String {
        return try {
            val padded = value.trim().let { it + "=".repeat((4 - it.length % 4) % 4) }
            String(Base64.getDecoder().decode(padded))
        } catch (_: Exception) {
            ""
        }
    }

    // ---------------------------------------------------------------- bypass

    /**
     * Runs the NetMirror human-verification bypass and returns the t_hash_t
     * cookie value, or "" if it fails. It is a plain HTTP POST (no WebView):
     * the server sets the cookie and 301-redirects to /home. The cookie is
     * cached for 15h.
     */
    suspend fun getCookie(mainUrl: String): String {
        val now = System.currentTimeMillis()
        val cached = cachedCookie
        if (!cached.isNullOrBlank() && now - cachedCookieAt < COOKIE_TTL_MS) return cached
        val fresh = bypass(mainUrl)
        if (fresh.isNotBlank()) {
            cachedCookie = fresh
            cachedCookieAt = now
        }
        return fresh
    }

    private fun bypass(mainUrl: String): String {
        return try {
            val client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val body = FormBody.Builder()
                .add("g-recaptcha-response", UUID.randomUUID().toString())
                .build()
            val request = Request.Builder()
                .url("$mainUrl/verify.php")
                .post(body)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "max-age=0")
                .header("Connection", "keep-alive")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://net22.cc")
                .header("Referer", "https://net22.cc/verify2")
                .header("sec-ch-ua", "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .header("User-Agent", DESKTOP_UA)
                .build()
            client.newCall(request).execute().use { response ->
                response.headers("Set-Cookie")
                    .firstOrNull { it.startsWith("t_hash_t=") }
                    ?.substringAfter("t_hash_t=")
                    ?.substringBefore(";")
                    ?.trim()
                    .orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    // --------------------------------------------------------- NewTV API base

    /**
     * Resolves the live NewTV API base URL (e.g. https://tv.imgcdn.kim).
     * Tries the candidate check domains in order; the first that returns a
     * non-blank token_hash wins. Cached for 24h. Returns "" if none works.
     */
    suspend fun resolveApiUrl(app: Requests): String {
        val now = System.currentTimeMillis()
        val cached = cachedApiBase
        if (!cached.isNullOrBlank() && now - cachedApiBaseAt < API_BASE_TTL_MS) return cached
        for (encoded in newTvDomains) {
            val base = decodeBase64(encoded).trimEnd('/')
            if (base.isBlank()) continue
            val resolved = try {
                val res = app.get(
                    "$base/newtv/checknewtv.php",
                    headers = newTvBaseHeaders
                ).parsed<NewTvTokenResponse>()
                val tokenHash = res.token_hash
                if (!tokenHash.isNullOrBlank()) decodeBase64(tokenHash).trimEnd('/') else ""
            } catch (_: Exception) {
                ""
            }
            if (resolved.isNotBlank()) {
                cachedApiBase = resolved
                cachedApiBaseAt = now
                return resolved
            }
        }
        return ""
    }

    // ------------------------------------------------------------ NewTV token

    /** Headers for the NewTV player.php call: base headers + Ott + Usertoken. */
    fun buildNewTvHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        return newTvBaseHeaders + mapOf("Ott" to ott) + extra
    }

    /**
     * Fetches the per-session NewTV user token from <apiBase>/newtv/otp.php.
     * Returns "" if it fails (the player call will then fail and loadLinks
     * returns false, so no broken link is ever emitted).
     */
    suspend fun getNewTvUserToken(app: Requests, apiBase: String, ott: String): String {
        return try {
            val otpHeaders = mapOf(
                "accept" to "application/json, text/plain, */*",
                "cache-control" to "no-cache, no-store, must-revalidate",
                "Connection" to "Keep-Alive",
                "expires" to "0",
                "otp" to "111111",
                "pragma" to "no-cache",
                "user-agent" to OTP_UA
            )
            val res = app.get("$apiBase/newtv/otp.php", headers = otpHeaders)
                .parsed<NewTvOtpResponse>()
            res.usertoken.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    // ----------------------------------------------------------------- misc

    /** "2h 58m" -> 178, "71m" -> 71. */
    fun convertRuntimeToMinutes(runtime: String): Int {
        var totalMinutes = 0
        for (part in runtime.split(" ")) {
            when {
                part.endsWith("h") -> totalMinutes += (part.removeSuffix("h").trim().toIntOrNull() ?: 0) * 60
                part.endsWith("m") -> totalMinutes += part.removeSuffix("m").trim().toIntOrNull() ?: 0
            }
        }
        return totalMinutes
    }
}
