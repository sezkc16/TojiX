package com.moviesmod

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MoviesModPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MoviesModProvider())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(Driveseed())
        registerExtractorAPI(UHDMovies())
        registerExtractorAPI(DriveleechPro())
        registerExtractorAPI(DriveleechNet())
    }
}
