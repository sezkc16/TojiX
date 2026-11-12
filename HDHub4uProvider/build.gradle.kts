// Use an integer for version numbers
version = 6

cloudstream {
    description = "Download HD Movies & TV Shows from HDHub4u"
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
    iconUrl = "https://new12.hdhub4u.cl/wp-content/uploads/2021/05/cropped-cropped-1-1-1-2-1-192x192.png"

    isCrossPlatform = true
}

android {
    namespace = "com.hdhub4u"
}
