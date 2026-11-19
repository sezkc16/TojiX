package com.uhdmovies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class UHDMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(UHDMoviesProvider())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(Driveseed())
        registerExtractorAPI(UHDMovies())
        registerExtractorAPI(DriveleechPro())
        registerExtractorAPI(DriveleechNet())
    }
}
