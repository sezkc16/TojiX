package com.katdrama

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KatDramaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(KatDramaProvider())
    }
}
