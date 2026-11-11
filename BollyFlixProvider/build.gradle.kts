// Use an integer for version numbers
version = 2

cloudstream {
    description = "Movies & Web Series from BollyFlix"
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
    iconUrl = "https://bflixcdn.xyz/wp-content/uploads/2023/05/Bollyflix-movies.png"

    isCrossPlatform = true
}

android {
    namespace = "com.bollyflix"
}
