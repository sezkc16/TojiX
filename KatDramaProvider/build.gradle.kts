// Use an integer for version numbers
version = 13

cloudstream {
    description = "Watch K-Dramas, C-Dramas and Hindi Dubbed series from KatDrama"
    authors = listOf("Toji")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("TvSeries")
    iconUrl = "https://catimages.org/images/2026/05/05/cropped-KATDRAMA-fab.png"

    isCrossPlatform = true
}

android {
    namespace = "com.katdrama"
}
