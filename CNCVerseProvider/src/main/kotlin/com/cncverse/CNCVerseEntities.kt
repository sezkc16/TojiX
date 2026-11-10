package com.cncverse

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Entities for the CNC Verse (Netflix / NetMirror) provider.
 *
 * Data shapes observed live from net52.cc (/mobile/home, /mobile/search.php,
 * /mobile/post.php, /mobile/episodes.php) and the NewTV API (tv.imgcdn.kim).
 * All fields are nullable with safe defaults; unknown JSON fields are ignored
 * so the site can add fields without breaking the plugin.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class PostData(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("cast") val cast: String? = null,
    @JsonProperty("desc") val desc: String? = null,
    @JsonProperty("director") val director: String? = null,
    @JsonProperty("episodes") val episodes: List<Episode>? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("match") val match: String? = null,
    @JsonProperty("nextPage") val nextPage: Int? = null,
    @JsonProperty("nextPageSeason") val nextPageSeason: String? = null,
    @JsonProperty("nextPageShow") val nextPageShow: Int? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    @JsonProperty("season") val season: List<Season>? = null,
    @JsonProperty("suggest") val suggest: List<Suggest>? = null,
    @JsonProperty("ua") val ua: String? = null,
    @JsonProperty("year") val year: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Episode(
    @JsonProperty("complate") val complate: String? = null,
    @JsonProperty("ep") val ep: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("s") val s: String? = null,
    @JsonProperty("t") val t: String? = null,
    @JsonProperty("time") val time: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Season(
    @JsonProperty("ep") val ep: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("s") val s: String? = null,
    @JsonProperty("sele") val sele: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Suggest(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("t") val t: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodesData(
    @JsonProperty("episodes") val episodes: List<Episode>? = null,
    @JsonProperty("nextPage") val nextPage: Int? = null,
    @JsonProperty("nextPageSeason") val nextPageSeason: String? = null,
    @JsonProperty("nextPageShow") val nextPageShow: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchData(
    @JsonProperty("head") val head: String? = null,
    @JsonProperty("searchResult") val searchResult: List<SearchResult>? = null,
    @JsonProperty("type") val type: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResult(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("t") val t: String? = null
)

/** Data carried in a search/home card URL: just the post id. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Id(
    @JsonProperty("id") val id: String? = null
)

/** Data carried in an episode URL: title + the id loadLinks resolves. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoadData(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("id") val id: String? = null
)

/** NewTV /newtv/player.php response. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NewTvPlayerResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("video_link") val video_link: String? = null,
    @JsonProperty("referer") val referer: String? = null
)

/** NewTV /newtv/otp.php response. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NewTvOtpResponse(
    @JsonProperty("error_msg") val error_msg: String? = null,
    @JsonProperty("otp") val otp: String? = null,
    @JsonProperty("pub_msg") val pub_msg: String? = null,
    @JsonProperty("pub_msg_color") val pub_msg_color: String? = null,
    @JsonProperty("pub_msg_f_size") val pub_msg_f_size: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("usertoken") val usertoken: String? = null
)

/** NewTV checknewtv.php response; token_hash is a base64 API base URL. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NewTvTokenResponse(
    @JsonProperty("token_hash") val token_hash: String? = null
)
