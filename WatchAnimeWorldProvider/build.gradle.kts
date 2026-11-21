// Use an integer for version numbers
version = 2

cloudstream {
    description = "Hindi, Tamil, Telugu dubbed Anime & Cartoons from WatchAnimeWorld"
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
    iconUrl = "https://watchanimeworld.pro/wp-content/uploads/AWI-SiteTitle-1.png"

    isCrossPlatform = true
}

android {
    namespace = "com.watchanimeworld"
}
