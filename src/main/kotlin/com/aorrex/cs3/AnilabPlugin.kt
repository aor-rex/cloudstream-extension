package com.aorrex.cs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnilabPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnilabProvider())
    }
}
