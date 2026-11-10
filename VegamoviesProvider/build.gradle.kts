// Use an integer for version numbers
version = 1

cloudstream {
    description = "Download Movies & Web Series from Vegamovies"
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
    iconUrl = "https://vegamovies8.co.in/templates/vegamovies/images/v-l-200x300.png"

    isCrossPlatform = true
}

android {
    namespace = "com.vegamovies"
}
