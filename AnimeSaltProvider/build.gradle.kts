// Use an integer for version numbers
version = 2

cloudstream {
    description = "Hindi, Tamil & Telugu Dubbed Anime from AnimeSalt"
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
    iconUrl = "https://animesalt.cx/wp-content/uploads/cropped-AnimeSalticon.png"

    isCrossPlatform = true
}

android {
    namespace = "com.animesalt"
}
