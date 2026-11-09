package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HDHub4uPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HDHub4uProvider())
    }
}
