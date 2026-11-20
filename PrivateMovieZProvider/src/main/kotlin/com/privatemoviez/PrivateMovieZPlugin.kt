package com.privatemoviez

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PrivateMovieZPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PrivateMovieZProvider())
    }
}
