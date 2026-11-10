package com.cncverse

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CNCVersePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CNCVerseProvider())
    }
}
