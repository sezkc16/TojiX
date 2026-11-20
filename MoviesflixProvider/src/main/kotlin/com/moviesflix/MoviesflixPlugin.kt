package com.moviesflix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MoviesflixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MoviesflixProvider())
    }
}
