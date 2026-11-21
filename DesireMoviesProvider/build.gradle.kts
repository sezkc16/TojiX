// Use an integer for version numbers
version = 1

cloudstream {
    description = "Download Bollywood, Hollywood & South Movies from DesireMovies"
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
    iconUrl = "https://1desiremovies.xyz/wp-content/uploads/2025/01/Desiremovies.my_.png"

    isCrossPlatform = true
}

android {
    namespace = "com.desiremovies"
}
