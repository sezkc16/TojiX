package com.gokuhd

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class GokuHDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(GokuHDProvider())
    }
}
