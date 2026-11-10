// Use an integer for version numbers
version = 3

cloudstream {
    description = "Movies, Series & Anime from HiCine"
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
    iconUrl = "https://hicine.biz/favicon.svg"

    isCrossPlatform = true
}

android {
    namespace = "com.hicine"
}
