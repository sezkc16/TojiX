// Use an integer for version numbers
version = 4

cloudstream {
    description = "Bollywood & Hollywood dual audio Hindi movies and web series from SSRMovies"
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
    iconUrl = "https://site2.ssrmovies.page/wp-content/uploads/2024/10/SSRmovies.COM-Icon.png"

    isCrossPlatform = true
}

android {
    namespace = "com.ssrmovies"
}
