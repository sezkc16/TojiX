package com.cinefreak

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CineFreakPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CineFreakProvider())
    }
}
