// Use an integer for version numbers
version = 5

cloudstream {
    description = "Watch Movies & Web Series from MultiMovies"
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
    iconUrl = "https://multimovies.wtf/fav/apple-touch-icon.png"

    isCrossPlatform = true
}

android {
    namespace = "com.multimovies"
}
