package dev.kitlangton.scrawl.sample

import android.app.Application
import android.util.Log
import dev.kitlangton.scrawl.Scrawl

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val scrawl = Scrawl.install(this)
        scrawl.startupObserver = { Log.i("ScrawlStartup", "${it.stage} ${"%.1f".format(it.milliseconds)}ms main=${it.onMainThread}") }
    }
}
