// Use an integer for version numbers
version = 1

cloudstream {
    description = "Hindi Dubbed Anime (Multi Audio) from AnimeDubHindi"
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
    iconUrl = "https://www.animedubhindi.link/wp-content/uploads/2024/07/Untitled-design.png"

    isCrossPlatform = true
}

android {
    namespace = "com.animedubhindi"
}
