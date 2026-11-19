package com.cinejoy

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CinejoyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CinejoyProvider())
    }
}
