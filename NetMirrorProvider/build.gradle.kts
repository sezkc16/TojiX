// Use an integer for version numbers
version = 1

cloudstream {
    description = "Movies, Web Series & TV Shows from NetMirror (OTT mirror)"
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
    // Stable-domain logo (netmirror.gg is the official home page and does NOT
    // rotate, unlike the streaming .cc domain). Verified 192x192 PNG.
    iconUrl = "https://netmirror.gg/img/nf2/icon_x192.png"

    isCrossPlatform = true
}

android {
    namespace = "com.netmirror"
}
