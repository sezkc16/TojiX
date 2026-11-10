// Use an integer for version numbers
version = 1

cloudstream {
    description = "Download HD Movies & TV Shows from CineVood"
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
    iconUrl = "https://new2.cinevood.company/wp-content/uploads/2020/07/favicon2.png"

    isCrossPlatform = true
}

android {
    namespace = "com.cinevood"
}
