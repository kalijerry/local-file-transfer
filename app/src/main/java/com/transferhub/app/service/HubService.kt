package com.transferhub.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.transferhub.app.MainActivity
import com.transferhub.app.R
import com.transferhub.app.data.Settings
import com.transferhub.app.server.TransferServer
import com.transferhub.app.util.LocalAddress
import com.transferhub.app.util.NetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

sealed interface HubStatus {
    data object Stopped : HubStatus
    data object Starting : HubStatus
    data class Running(val port: Int, val token: String, val addresses: List<LocalAddress>) : HubStatus {
        fun urlFor(address: LocalAddress) = "http://${address.ip}:$port/?t=$token"
    }
    data class Failed(val message: String) : HubStatus
}

/**
 * 传送站前台服务：持有 HTTP 服务器、Wi-Fi 锁和唤醒锁，保证息屏后仍能持续收发。
 */
class HubService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: TransferServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(getString(R.string.app_name) + " 正在启动…")
        if (server == null && _status.value !is HubStatus.Starting) {
            _status.value = HubStatus.Starting
            scope.launch { startServer() }
        }
        return START_STICKY
    }

    private fun startServer() {
        val token = randomToken()
        val s = TransferServer(applicationContext, token) { Settings.allowWebDelete.value }
        try {
            val port = s.start()
            server = s
            acquireLocks()
            registerNetworkCallback()
            _status.value = HubStatus.Running(port, token, NetUtils.localAddresses())
            updateNotification()
        } catch (t: Throwable) {
            Log.e(TAG, "启动失败", t)
            _status.value = HubStatus.Failed(t.message ?: "启动失败")
            stopSelf()
        }
    }

    /** 网络变化（切换 Wi-Fi、开关热点）时刷新可访问地址 */
    private fun refreshAddresses() {
        val current = _status.value as? HubStatus.Running ?: return
        val addresses = NetUtils.localAddresses()
        if (addresses != current.addresses) {
            _status.value = current.copy(addresses = addresses)
            updateNotification()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshAddresses()
            override fun onLost(network: Network) = refreshAddresses()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = refreshAddresses()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TransferHub:wifi")
            ?.apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TransferHub:cpu")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        val s = server
        server = null
        // 服务器停止可能耗时，放到后台线程
        Thread { runCatching { s?.stop() } }.start()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        scope.cancel()
        if (_status.value !is HubStatus.Failed) _status.value = HubStatus.Stopped
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    private fun startInForeground(text: String) {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun updateNotification() {
        val st = _status.value as? HubStatus.Running ?: return
        val text = st.addresses.firstOrNull()?.let { "http://${it.ip}:${st.port}" } ?: "未连接 Wi-Fi / 热点"
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification("运行中 · $text"))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HubService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hub)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "传送站运行状态", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val TAG = "HubService"
        private const val CHANNEL_ID = "hub"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.transferhub.app.STOP"

        private val _status = MutableStateFlow<HubStatus>(HubStatus.Stopped)
        val status: StateFlow<HubStatus> = _status.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HubService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HubService::class.java).setAction(ACTION_STOP))
        }

        /** 手动刷新地址（界面上的“刷新”按钮） */
        fun refresh() {
            val current = _status.value as? HubStatus.Running ?: return
            _status.value = current.copy(addresses = NetUtils.localAddresses())
        }

        private fun randomToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..10).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
