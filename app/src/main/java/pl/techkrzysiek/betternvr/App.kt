package pl.techkrzysiek.betternvr

import android.app.Application
import pl.techkrzysiek.betternvr.data.DeviceManager
import pl.techkrzysiek.betternvr.data.DeviceStore
import pl.techkrzysiek.betternvr.data.Thumbnails
import pl.techkrzysiek.betternvr.update.Updater

class App : Application() {
    lateinit var store: DeviceStore
        private set
    lateinit var manager: DeviceManager
        private set
    lateinit var thumbnails: Thumbnails
        private set
    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        store = DeviceStore(this)
        manager = DeviceManager(store)
        thumbnails = Thumbnails(this)
        updater = Updater(this)
    }
}
