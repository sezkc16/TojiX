// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch Bollywood, Hollywood, Hindi Dubbed movies and web series from KatMovieHD"
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
    iconUrl = "https://new.katmoviehd.top/favicon.png"

    isCrossPlatform = true
}

android {
    namespace = "com.katmoviehd"
}
