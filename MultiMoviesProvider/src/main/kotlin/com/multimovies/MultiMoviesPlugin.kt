package com.multimovies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MultiMoviesProvider())
    }
}
