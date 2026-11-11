// Use an integer for version numbers
version = 2

cloudstream {
    description = "Hindi Dubbed Anime Series & Anime Movies from GokuHD"
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
    iconUrl = "https://gokuhd.nl/wp-content/uploads/2025/07/Goku-Favicon.png"

    isCrossPlatform = true
}

android {
    namespace = "com.gokuhd"
}
