package pl.techkrzysiek.betternvr

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
        // Wi-Fi joined or lost, VPN up or down: recorders reached on a fallback address check their preferred one now
        // (a passive callback; it only nudges sessions that are open, and there are none in the background)
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = manager.networkChanged()
                    override fun onLost(network: Network) = manager.networkChanged()
                },
            )
        }
    }
}
