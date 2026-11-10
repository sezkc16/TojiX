package com.fourkhdhub

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FourKHDHubPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FourKHDHubProvider())
    }
}
