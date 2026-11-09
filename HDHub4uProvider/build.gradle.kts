// Use an integer for version numbers
version = 1

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
    iconUrl = "https://new12.hdhub4u.cl/wp-content/uploads/2024/10/hdhub4u.png"

    isCrossPlatform = true
}

android {
    namespace = "com.hdhub4u"
}
