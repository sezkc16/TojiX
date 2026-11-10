// Use an integer for version numbers
version = 1

cloudstream {
    // Netflix (via the NetMirror / NewTV API). Hotstar / Prime Video / Disney are
    // intentionally left out of v1: their masters returned empty/incomplete from
    // verification, so shipping them would only produce broken streams.
    description = "Netflix, PrimeVideo, Disney+ Hotstar Contents in Multiple Languages"
    authors = listOf("Toji")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama")
    iconUrl = "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/Logos/CNC%20Verse/icon.png"

    isCrossPlatform = true
}

android {
    namespace = "com.cncverse"
}
