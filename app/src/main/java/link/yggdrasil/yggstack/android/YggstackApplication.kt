package link.yggdrasil.yggstack.android

import android.app.Application
import link.yggdrasil.yggstack.android.worker.PeerCacheUpdateWorker

class YggstackApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Schedule periodic peer cache updates
        PeerCacheUpdateWorker.schedulePeriodicUpdate(this)
    }
}

