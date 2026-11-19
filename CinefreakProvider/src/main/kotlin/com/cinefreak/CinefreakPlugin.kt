package com.cinefreak

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CinefreakPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CinefreakProvider())
    }
}
