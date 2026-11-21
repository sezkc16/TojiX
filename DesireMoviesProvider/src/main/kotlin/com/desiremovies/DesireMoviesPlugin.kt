package com.desiremovies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DesireMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DesireMoviesProvider())
    }
}
