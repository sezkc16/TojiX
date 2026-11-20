// Use an integer for version numbers
version = 1

cloudstream {
    description = "Download HD Movies & TV Shows from PrivateMovieZ"
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
    iconUrl = "https://b.privatemoviez.surf/wp-content/uploads/2026/02/Logo.webp"

    isCrossPlatform = true
}

android {
    namespace = "com.privatemoviez"
}
