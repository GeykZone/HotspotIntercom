package com.example.hotspotintercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder

/** Foreground service so the intercom keeps working with the screen off. */
class IntercomService : Service() {

    companion object {
        const val EXTRA_HOST = "is_host"
        const val EXTRA_IP = "host_ip"
        const val EXTRA_SPEAKER = "speaker"
        const val EXTRA_HANDS_FREE = "hands_free"
        const val ACTION_STOP = "stop"
        private const val CHANNEL_ID = "intercom"

        @Volatile var engine: IntercomEngine? = null
        @Volatile var statusListener: ((String) -> Unit)? = null
    }

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Intercom", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Alpha-Connect running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock?.release()
        @Suppress("DEPRECATION")
        val lockMode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(lockMode, "intercom").apply {
            setReferenceCounted(false); acquire()
        }

        val isHost = intent?.getBooleanExtra(EXTRA_HOST, false) ?: false
        val ip = intent?.getStringExtra(EXTRA_IP) ?: "192.168.43.1"
        val speaker = intent?.getBooleanExtra(EXTRA_SPEAKER, true) ?: true
        val handsFree = intent?.getBooleanExtra(EXTRA_HANDS_FREE, false) ?: false

        // A hotspot has no internet, so force this app's traffic onto the Wi-Fi network.
        if (!isHost) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val wifi = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            cm.bindProcessToNetwork(wifi)
        }

        engine?.stop()
        engine = IntercomEngine(applicationContext, isHost, ip, speaker, handsFree) {
            statusListener?.invoke(it)
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        engine?.stop()
        engine = null
        wifiLock?.release()
        wifiLock = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null)
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        wifiLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
