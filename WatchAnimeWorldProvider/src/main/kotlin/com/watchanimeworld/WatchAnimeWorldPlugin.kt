package com.watchanimeworld

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class WatchAnimeWorldPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WatchAnimeWorldProvider())
    }
}
