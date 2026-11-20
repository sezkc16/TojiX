// Use an integer for version numbers
version = 4

cloudstream {
    description = "MoviesMod | Movies Mod 2025, MoviesMod Official"
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
    iconUrl = "https://moviesmod.fi/wp-content/uploads/2024/06/favicon-3-200x200.png"

    isCrossPlatform = true
}

android {
    namespace = "com.moviesmod"
}
