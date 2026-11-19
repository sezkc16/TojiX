// Use an integer for version numbers
version = 1

cloudstream {
    description = "Download 4k Dual Audio Movies, 1080p Movies, 2160p HDR Movies & TV Series"
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
    iconUrl = "https://uhdmovies.ceo/wp-content/uploads/2021/03/cropped-output-onlinepngtools-1-192x192.png"

    isCrossPlatform = true
}

android {
    namespace = "com.uhdmovies"
}
