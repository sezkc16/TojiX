// Use an integer for version numbers
version = 2

cloudstream {
    description = "Movies & Web Series from MoviesDrive"
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
    iconUrl = "https://new2.moviesdrive.cyou/wp-content/uploads/2026/01/cropped-Gemini_Generated_Image_qt93aqt93aqt93aq-1-192x192.png"

    isCrossPlatform = true
}

android {
    namespace = "com.moviesdrive"
}
