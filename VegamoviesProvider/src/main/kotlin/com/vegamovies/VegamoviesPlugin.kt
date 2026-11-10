package com.vegamovies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class VegamoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(VegamoviesProvider())
    }
}
