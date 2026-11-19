/**
 * The file-landing extractors at the end of the MoviesMod chain.
 * Same services as the UHDMovies plugin (video-seed.xyz unlock +
 * driveseed / driveleech landing pages) — the chain lands on the
 * same hosts, so the extractors are the same code, re-implemented
 * here per-module (each .cs3 is self-contained).
 */
package com.moviesmod

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * video-seed.xyz — the link-unlocking service at the end of the chain.
 * The gate hands out https://video-seed.xyz/?url=<token> links; this
 * extractor POSTs the token to /api and emits the unlocked file URL.
 */
class UHDMovies : ExtractorApi() {
    override val name = "UHDMovies"
    override val mainUrl = "https://video-seed.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val token = url.substringAfter("https://video-seed.xyz/?url=", "")
        if (token.isBlank()) return
        val res = try {
            app.post(
                "https://video-seed.xyz/api",
                headers = mapOf(
                    "x-token" to "video-seed.xyz",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0"
                ),
                referer = url,
                data = mapOf("keys" to token)
            )
        } catch (_: Exception) {
            return
        }
        val finalLink = res.text
            .substringAfter("url\":\"", "")
            .substringBefore("\",\"name")
            .replace("\\/", "/")
        if (finalLink.isBlank()) return
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = finalLink,
                type = ExtractorLinkType.VIDEO
            ) {
                // The reference implementation sets quality from the
                // extractor's second argument (the referer, normally
                // null here), which always resolves to Unknown — so
                // no quality and no referer are set, and the player
                // keeps the defaults.
            }
        )
    }
}

/**
 * driveseed.org — the file-landing pages. Driveleech (.org/.pro/.net)
 * are the same pages under a different host, so they are thin subclasses.
 *
 * A landing page lists the file name/size plus one button per server:
 * Cloud Download (direct file), Instant Download (video-seed unlock),
 * Resume Worker Bot and Direct Links (Cloudflare-worker links). Each
 * button becomes one source row, tagged with its server so the row
 * shows where the file comes from.
 *
 * "Resume Cloud" is deliberately NOT emitted: its button resolves to
 * a .zip archive (Cloudflare Worker, application/x-zip) — a
 * download-only file the player cannot play, so the row failed
 * silently and Zeus auto-advanced past it (user report 2026-11-19).
 */
open class Driveseed : ExtractorApi() {
    override val name = "Driveseed"
    override val mainUrl = "https://driveseed.org"
    // The reference shows no explicit value here; the default (false) is
    // used. If driveseed links ever fail to play, the referer is the
    // first suspect.
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = getBaseUrl(url)
        val document = try {
            if (url.contains("r?key=")) {
                val res = app.get(url)
                val temp = Regex("replace\\(\\s*[\"']([^\"']+)[\"']")
                    .find(res.text)?.groupValues?.getOrNull(1)
                    ?: res.document.selectFirst("script:containsData(replace)")
                        ?.data()
                        ?.substringAfter("replace(\"", "")
                        ?.substringBefore("\")", "")
                    ?: res.document.selectFirst("script")
                        ?.data()
                        ?.substringAfter("replace(\"", "")
                        ?.substringBefore("\")", "")
                    ?: ""
                if (temp.isBlank()) return
                app.get(fixUrlWithBase(temp.replace("\\/", "/"), baseUrl)).document
            } else {
                app.get(url).document
            }
        } catch (_: Exception) {
            return
        }

        val nameText = document.selectFirst(
            "li.list-group-item:contains(Name), ul > li.list-group-item:contains(Name)"
        )?.text() ?: ""
        val rawFileName = nameText.substringAfter("Name")
            .trimStart(' ', ':').trim()
        val fileName = cleanTitle(removeLeadingIndex(rawFileName))
        val sizeText = document.selectFirst(
            "li.list-group-item:contains(Size), ul > li.list-group-item:contains(Size)"
        )?.text() ?: ""
        val fileSize = sizeText.substringAfter("Size")
            .trimStart(' ', ':').trim()
        var quality = getIndexQuality(fileName)
        if (quality == Qualities.Unknown.value) {
            quality = getIndexQuality(rawFileName)
        }
        if (quality == Qualities.Unknown.value) {
            quality = getIndexQuality(nameText)
        }

        document.select("div.text-center > a, a.btn").amap { element ->
            try {
                dispatchButton(
                    element, subtitleCallback, callback,
                    fileName, fileSize, quality, baseUrl
                )
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun dispatchButton(
        element: Element,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        fileName: String,
        fileSize: String,
        quality: Int,
        baseUrl: String
    ) {
        val text = element.text()
        val href = element.attr("href")
        if (href.isBlank()) return
        when {
            text.contains("Cloud Download", ignoreCase = true) -> {
                emitLink(callback, fileName, fileSize, quality, href, "[Cloud]")
            }
            text.contains("Instant Download", ignoreCase = true) -> {
                val instant = try {
                    instantLink(href)
                } catch (_: Exception) {
                    null
                } ?: return
                emitLink(
                    callback, fileName, fileSize, quality,
                    instant, "[Instant(Download)]"
                )
            }
            text.contains("Resume Worker Bot", ignoreCase = true) ||
                text.contains("ResumeBot", ignoreCase = true) -> {
                val resumeLink = try {
                    resumeBot(href)
                } catch (_: Exception) {
                    null
                } ?: return
                emitLink(
                    callback, fileName, fileSize, quality,
                    resumeLink, "[ResumeBot]"
                )
            }
            text.contains("Direct Links", ignoreCase = true) -> {
                val links = try {
                    cfType(fixUrlWithBase(href, baseUrl))
                } catch (_: Exception) {
                    emptyList()
                }
                for (link in links) {
                    emitLink(callback, fileName, fileSize, quality, link, "[CF]")
                }
            }
            text.contains("gofile", ignoreCase = true) -> {
                loadExtractor(href, subtitleCallback, callback)
            }
        }
    }

    private suspend fun emitLink(
        callback: (ExtractorLink) -> Unit,
        fileName: String,
        fileSize: String,
        quality: Int,
        link: String,
        server: String
    ) {
        val labelExtras = buildString {
            if (fileName.isNotEmpty()) append(" [$fileName]")
            if (fileSize.isNotEmpty()) append(" [$fileSize]")
        }
        callback(
            newExtractorLink(
                source = "$name$server",
                name = "$name$server$labelExtras",
                url = link,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
            }
        )
    }

    /** Quality from a file name / tag string. */
    private fun getIndexQuality(str: String?): Int {
        if (str.isNullOrBlank()) return Qualities.Unknown.value
        val lower = str.lowercase(Locale.ROOT)
        if (Regex("\\b(?:8k|4320p?)\\b").containsMatchIn(lower)) return 4320
        if (Regex("\\b(?:4k|2160p?|uhd)\\b")
                .containsMatchIn(lower.replace("uhdmovies", ""))
        ) return Qualities.P2160.value
        if (Regex("\\b(?:2k|1440p?|qhd)\\b").containsMatchIn(lower)) {
            return Qualities.P1440.value
        }
        if (Regex("\\b(?:1080p?|fhd)\\b").containsMatchIn(lower)) {
            return Qualities.P1080.value
        }
        if (Regex("\\b(?:720p?|hd)\\b").containsMatchIn(
                lower.replace("uhdmovies", "")
                    .replace("uhd", "").replace("fhd", "")
            )
        ) return Qualities.P720.value
        if (Regex("\\b(?:480p?|sd)\\b").containsMatchIn(lower)) {
            return Qualities.P480.value
        }
        if (Regex("\\b(?:360p?)\\b").containsMatchIn(lower)) {
            return Qualities.P360.value
        }
        val num = Regex("(\\d{3,4})[pP]").find(str)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return num ?: Qualities.Unknown.value
    }

    /** "Direct Links": try ?type=1 and ?type=2, collect the green
     *  download buttons. Failures on one type are swallowed. */
    private suspend fun cfType(url: String): List<String> {
        val downloadLinks = mutableListOf<String>()
        for (t in listOf("1", "2")) {
            try {
                val doc = app.get("$url?type=$t").document
                for (el in doc.select("a.btn-success")) {
                    val href = el.attr("href")
                    if (href.startsWith("http")) downloadLinks.add(href)
                }
            } catch (_: Exception) {
            }
        }
        return downloadLinks
    }

    /** "Resume Worker Bot": the page plants a token + download id in JS;
     *  POST them back with the PHPSESSID cookie. */
    private suspend fun resumeBot(url: String): String? {
        return try {
            val res = app.get(url)
            val doc = res.document.toString()
            val session = res.cookies["PHPSESSID"].orEmpty()
            val token = Regex("formData\\.append\\('token', '([a-f0-9]+)'\\)")
                .find(doc)?.groupValues?.getOrNull(1).orEmpty()
            val id = Regex("fetch\\('/download\\?id=([a-zA-Z0-9/+]+)'\\)")
                .find(doc)?.groupValues?.getOrNull(1).orEmpty()
            if (token.isEmpty() || id.isEmpty()) return null
            val origin = url.substringBefore("/download")
            val body = FormBody.Builder().addEncoded("token", token).build()
            val postRes = app.post(
                "$origin/download?id=$id",
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to origin,
                    "Sec-Fetch-Site" to "same-origin"
                ),
                referer = url,
                cookies = mapOf("PHPSESSID" to session),
                requestBody = body
            )
            JSONObject(postRes.text).optString("url")
                .takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * "Instant Download" — the main playable path. The button redirects
     * (no redirect follow) to a video-seed / video-leech / video-plex
     * ?url=<token> link; POST the token to that host's /api and the
     * unlocked file URL comes back. If the token itself is already a
     * direct http link, it is returned as-is. If the API call fails,
     * the ?url= link itself is returned so the UHDMovies extractor
     * gets a second chance at unlocking it.
     */
    private suspend fun instantLink(url: String): String? {
        return try {
            val res = app.get(url, allowRedirects = false)
            val redirected = res.headers["location"] ?: url
            val token = if (redirected.contains("?url=")) {
                redirected.substringAfter("?url=")
            } else {
                null
            }
            if (token != null && token.startsWith("http")) return token
            if (token == null) return null
            val host = when {
                redirected.contains("video-leech") -> "video-leech.xyz"
                redirected.contains("video-plex") -> "video-plex.xyz"
                else -> "video-seed.xyz"
            }
            val postRes = try {
                app.post(
                    "https://$host/api",
                    headers = mapOf(
                        "x-token" to host,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    ),
                    referer = redirected,
                    data = mapOf("keys" to token)
                )
            } catch (_: Exception) {
                return redirected
            }
            val direct = JSONObject(postRes.text).optString("url")
                .replace("\\/", "/")
            if (direct.isNotBlank()) direct else redirected
        } catch (_: Exception) {
            null
        }
    }
}

class Driveleech : Driveseed() {
    override val name = "Driveleech"
    override val mainUrl = "https://driveleech.org"
}

class DriveleechPro : Driveseed() {
    override val name = "Driveleech"
    override val mainUrl = "https://driveleech.pro"
}

class DriveleechNet : Driveseed() {
    override val name = "Driveleech"
    override val mainUrl = "https://driveleech.net"
}

/** Strip a leading "1. " / "[1] " / "01 - " style index from a file name. */
private fun removeLeadingIndex(title: String): String {
    return Regex("^[\\[(]?\\s*\\d+\\s*[])\\-_.]*\\s*").replace(title, "")
}

/**
 * Reduce a file name to its encode tags (source / codec / audio / HDR).
 * The reference implementation returns the tag string, not the title —
 * it is what the source rows display next to the file size, and the
 * quality is re-derived from the raw file name instead.
 */
private fun cleanTitle(title: String): String {
    var name = Regex("\\.[a-zA-Z0-9]{2,4}$").replace(title, "")
    name = Regex("WEB[-_. ]?DL", RegexOption.IGNORE_CASE).replace(name, "WEB-DL")
    name = Regex("WEB[-_. ]?RIP", RegexOption.IGNORE_CASE).replace(name, "WEBRIP")
    name = Regex("H[ .]?265", RegexOption.IGNORE_CASE).replace(name, "H265")
    name = Regex("H[ .]?264", RegexOption.IGNORE_CASE).replace(name, "H264")
    name = Regex("DDP[ .]?([0-9]\\.[0-9])", RegexOption.IGNORE_CASE)
        .replace(name, "DDP$1")
    val parts = name.split(" ", "_", ".")
    val sourceTags = setOf(
        "WEB-DL", "WEBRIP", "BLURAY", "HDRIP", "DVDRIP",
        "HDTV", "CAM", "TS", "BRRIP", "BDRIP"
    )
    val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC")
    val audioTags = setOf(
        "AAC", "AC3", "DTS", "MP3", "FLAC", "DD", "DDP", "EAC3"
    )
    val audioExtras = setOf("ATMOS")
    val hdrTags = setOf("SDR", "HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION")
    val tags = mutableListOf<String>()
    for (part in parts) {
        val p = part.uppercase(Locale.ROOT)
        when {
            p in sourceTags -> tags.add(p)
            p in codecTags -> tags.add(p)
            audioTags.any { p.startsWith(it) } -> tags.add(p)
            p in audioExtras -> tags.add(p)
            p in hdrTags -> tags.add(if (p == "DV") "DOLBYVISION" else p)
            p == "NF" || p == "CR" -> tags.add(p)
            else -> {
                // Not a tag — part of the title, dropped here.
            }
        }
    }
    return tags.distinct().joinToString(" ")
}
