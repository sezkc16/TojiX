// Use an integer for version numbers
version = 3

cloudstream {
    description = "Movies & TV Shows in 4K HDR, 2160p, 1080p from 4KHDHub"
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
    iconUrl = "https://4khdhub.one/apple-touch-icon.png"

    isCrossPlatform = true
}

android {
    namespace = "com.fourkhdhub"
}
