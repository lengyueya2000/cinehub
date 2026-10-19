package net.cinehub.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import net.cinehub.R

/**
 * 离线缓存的前台服务。
 *
 * 存在的理由只有一个：**别让系统回收进程**。缓存一集十几分钟，用户按 Home 切走之后
 * 这个进程在系统眼里就是「没人在看的后台进程」，内存一紧就被收掉 —— 那一刻还在跑的
 * 几笔任务连暂停都来不及写，下次进来由 [DownloadRepository.load] 把「下载中」一律
 * 落回「已暂停」。有了这块牌子，进程被划进「用户看得见的那类」，缓存能一路下完。
 *
 * 通知本身只是牌子的**内容**，不是目的：低重要度（不响、不弹横幅）、常驻、点开回应用。
 *
 * 生命周期完全由 [DownloadRepository] 牵着：有任务在跑就 start，一个不剩就 stop ——
 * 服务自己不知道任何任务状态，这样两边不会各说各话（见 [DownloadRepository.syncService]）。
 */
class DownloadService : Service() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        starting = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 低重要度：这一条只该安安静静待在通知栏里跟着进度走，
            // 不该有声音、有横幅 —— 用户切走就是为了干别的。
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "离线缓存", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        starting = false
        // 用**最新**的那一句，不读 intent extra：start 与 update 可能交错，
        // extra 携带的是 start 那一刻的旧值，照它显示就会回跳。
        //
        // type 必须显式给（34+ 不给直接 SecurityException），且与 manifest 里声明的
        // dataSync 一致 —— dataSync 正是「替用户把文件取下来」这一类。
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification(latestText),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        // start 与 stop 交叠的那一瞬（stop 先到、start 的 intent 后到）：
        // 由 startForegroundService 进来的必须先把牌子举起来（不举系统会追问超时），
        // 举完发现已经没人要了，就地放下。
        if (!wanted) {
            suppressStopForeground()
            stopSelf()
        }
        // 任务列表是仓库自己在跑的，服务死了不该拉它重来 —— 仓库下一次 pump() 会重新举牌。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        starting = false
        // 把牌子摘干净：stopSelf 走的是这条路，系统直接杀也是这条路。
        // 留着一条「正在缓存 N 集」而底下什么都没跑，比没有通知更糟。
        suppressStopForeground()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 按当前 [latestText] 刷一次通知，两次之间至少隔 [UPDATE_MIN_MS]。 */
    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifiedAt < UPDATE_MIN_MS) return
        lastNotifiedAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(latestText))
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("正在缓存")
            .setContentText(text)
            // 每次进度都响一声是噪音；这条通知只在任务状态变化时才值得看一眼
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .apply {
                // 点一下回到应用。取不到启动 Intent 就不挂点击（没内容Intent的通知点了没反应，
                // 比不给入口更让人困惑 —— 这里只是不添，不拦着显示）。
                launchIntent()?.let { setContentIntent(it) }
            }
            .build()

    private fun launchIntent(): PendingIntent? {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Suppress("DEPRECATION")
    private fun suppressStopForeground() {
        // 布尔那一版从 API 5 就在（int 带 flag 的 34+ 才强制），minSdk 24 上最稳的一句
        stopForeground(true)
    }

    companion object {
        private const val CHANNEL_ID = "cinehub_downloads"

        /** 只和这一条通知自己比，别撞上别的模块的通知 id。 */
        private const val NOTIF_ID = 0x0D1_705

        /**
         * 两次刷新之间最少隔多久。
         *
         * 同时在跑 3 集，每集又按秒报进度 —— 不隔开就是每秒重建三次通知、
         * 通知栏每秒重排三回，而这些字节本来就是给用户「扫一眼」的，不是看帧率的。
         */
        private const val UPDATE_MIN_MS = 800L

        /** 在跑的服务实例；null = 没在跑。 */
        @Volatile
        private var instance: DownloadService? = null

        /** startForegroundService 已经发出去了、实例还没建起来 —— 挡住重复发起。 */
        @Volatile
        private var starting = false

        /**
         * 「现在**应该**在跑」。
         *
         * 服务实例和这个意图可能短暂对不上（stop 的实例已走、新一次 start 又来了，
         * 反过来也一样）。onStartCommand 以它为准收尾，start/stop 只写它 ——
         * 谁先谁后都收敛到同一边。
         */
        @Volatile
        private var wanted = false

        @Volatile
        private var latestText = "正在缓存…"

        @Volatile
        private var lastNotifiedAt = 0L

        /**
         * 举牌（并刷新那一行字）。
         *
         * 幂等：实例在就只是刷字，不会反复 startForegroundService。
         *
         * `starting` 那一路**照样要更新 [latestText]**：startForegroundService 发出去到
         * onStartCommand 之间隔着一小会儿，那期间仓库每秒都在报进度 —— 丢掉了它，
         * 通知一露头就是发起那一刻的旧字，然后才被下一次刷新纠正。
         */
        @Synchronized
        fun start(context: Context, text: String) {
            wanted = true
            latestText = text
            val app = context.applicationContext
            instance?.let { it.refresh(); return }
            if (starting) return
            starting = true
            // 31+ 在后台起前台服务是被拦的（ForegroundServiceStartNotAllowedException）。
            // 拦下就当这一次没牌子：下载照跑，只是回到「进程可能被回收」的老状态 ——
            // 为一块牌子把用户的下载砸了才是本末倒置。
            val ok = runCatching {
                val i = Intent(app, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i)
                else app.startService(i)
            }.isSuccess
            if (!ok) starting = false
        }

        /** 只刷那一行字：进度每秒都在变，不能每次都去 start 一遍服务。 */
        @Synchronized
        fun update(context: Context, text: String) {
            if (!wanted) return
            latestText = text
            // 实例还没建起来（startForegroundService 刚发出去）时，只把字存着 ——
            // onStartCommand 读的是 [latestText]，它一露头就是最新的那句。
            instance?.refresh()
        }

        /** 放牌。没在跑就是一句空操作。 */
        @Synchronized
        fun stop(context: Context) {
            wanted = false
            starting = false
            // 不在这里 cancel 通知：摘牌子统一走 onDestroy（stopSelf、被系统杀都汇到那），
            // 两处各摘一次就会出现「服务还活着、通知没了」的中间态。
            instance?.stopSelf()
            // 实例是 null 但通知可能还在（进程活着、服务被系统单独收了）：兜一次底。
            // 对一个已经不存在的 id 做 cancel 是空操作，不会误删别人的。
            if (instance == null) {
                context.applicationContext.getSystemService(NotificationManager::class.java)
                    .cancel(NOTIF_ID)
            }
        }
    }
}
