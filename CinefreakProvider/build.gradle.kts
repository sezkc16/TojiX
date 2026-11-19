// Use an integer for version numbers
version = 1

cloudstream {
    description = "Free Download Hollywood, Bollywood, Bangla, TV Series, Turkish/Chinese Movies, Korean Drama Series in Hindi Dubbed, English (Dual Audio), HEVC 10bit, K-Drama, Anime in Hindi — Watch Online"
    authors = listOf("Toji")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://cinefreak.net/wp-content/uploads/2024/08/CineFeak.webp"

    isCrossPlatform = true
}

android {
    namespace = "com.cinefreak"
}

// Plain Kotlin only — no Compose anywhere. The Zeus engine ships no
// Jetpack Compose runtime, and its loader resolves the whole dex eagerly,
// so any Compose reference breaks the load (see the b94 diagnosis:
// Phisher's Cinefreak v25 fails to load for exactly this reason).
// No settings UI, matching all other TojiX plugins.
