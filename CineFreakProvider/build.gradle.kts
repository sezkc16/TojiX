// Use an integer for version numbers
version = 2

cloudstream {
    description = "Movies, Series & K-Drama from CineFreak"
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
    iconUrl = "https://cinefreak.net/wp-content/uploads/2024/08/cropped-cgk-192x192.png"

    isCrossPlatform = true
}

android {
    namespace = "com.cinefreak"
}
