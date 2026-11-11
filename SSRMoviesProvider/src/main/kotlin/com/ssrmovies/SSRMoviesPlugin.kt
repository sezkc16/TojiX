package com.ssrmovies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SSRMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SSRMoviesProvider())
    }
}
