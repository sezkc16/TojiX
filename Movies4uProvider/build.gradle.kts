// Use an integer for version numbers
version = 1

cloudstream {
    description = "Movies, Web Series & TV Shows from Movies4u"
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
    iconUrl = "https://movies4u.td/wp-content/uploads/2025/09/cropped-faviconV2-300x300.png"

    isCrossPlatform = true
}

android {
    namespace = "com.movies4u"
}
