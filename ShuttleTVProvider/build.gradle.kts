// Use an integer for version numbers
version = 1

cloudstream {
    description = "Movies & TV Shows from ShuttleTV (TMDB catalog + Videasy, Vidlink, VidFast, Xpass, Stremio, DahmerMovies, ProjectFreeTv, Autoembed)"
    authors = listOf("Toji")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://shuttletv.pk/icon.png"

    isCrossPlatform = true
}

android {
    namespace = "com.shuttletv"
}
