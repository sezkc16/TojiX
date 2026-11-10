// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch Anime Sub & Dub from Anikoto"
    authors = listOf("Toji")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie")
    iconUrl = "https://anikoto.cz/AnikotoTheme/assets/images/favicon.png"

    isCrossPlatform = true
}

android {
    namespace = "com.anikoto"
}
