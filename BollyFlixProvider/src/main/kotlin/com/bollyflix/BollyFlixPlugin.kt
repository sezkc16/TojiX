package com.bollyflix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BollyFlixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BollyFlixProvider())
    }
}
