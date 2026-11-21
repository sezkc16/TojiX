package com.animedubhindi

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeDubHindiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeDubHindiProvider())
    }
}
