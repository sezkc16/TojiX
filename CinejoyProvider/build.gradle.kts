// Use an integer for version numbers
version = 2

cloudstream {
    description = "Watch the latest movies and TV series on Cinejoy"
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
    iconUrl = "https://cinejoy.pk/pwa-512.png"

    isCrossPlatform = true
}

android {
    namespace = "com.cinejoy"
}

// v2 removed the Chicory WASM runtime entirely: the seal is now pure
// Kotlin/JCA in CinejoyExtractor (see the file header), so there are
// no extra dependencies and no dex-unpacking wiring any more.
