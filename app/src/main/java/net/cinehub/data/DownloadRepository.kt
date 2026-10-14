package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.cinehub.data.model.DownloadStatus
import net.cinehub.data.model.DownloadTask
import net.cinehub.data.model.Episode
import net.cinehub.data.model.Media
import net.cinehub.data.model.SourceConfig
import net.cinehub.data.model.StreamResolution
import net.cinehub.data.model.userNote
import net.cinehub.data.net.Http
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

private val Context.downloadStore by preferencesDataStore(name = "cinehub_downloads")

/**
 * 离线缓存。
 *
 * 落在应用专属的外部目录（getExternalFilesDir），不需要任何存储权限，
 * 卸载即清 —— 这是缓存而不是「把片存到你手机上」，边界上就该这么划。
 *
 * 下载前照样走各源的 resolveStream：缓存和在线播放取的是同一条直链，
 * 不给「下载」单开一条绕过站点规则的路。
 */
class DownloadRepository(
    private val context: Context,
    private val sources: SourceRepository,
    private val media: MediaRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 在跑的那一笔。
     *
     * 用一层壳而不是直接存 Job：Job 要 launch 之后才拿得到，而协程可能在存进 map 之前
     * 就跑完了 —— 那一刻按 id 盲删会把**新**那一笔摘没。壳在 launch 之前就占好位，
     * 谁在跑认壳的身份，不认协程对象。
     */
    private class Run {
        @Volatile
        var job: Job? = null
    }

    private val jobs = ConcurrentHashMap<String, Run>()

    /**
     * 一个任务手上还在飞的请求。
     *
     * 分片并发之后一个任务同时有好几个连接，所以这里是一对多：暂停 / 删除时要**整组**掐掉，
     * 只掐第一个的话其余几个还在往同一个目录里写。
     */
    private val calls = ConcurrentHashMap<String, MutableSet<okhttp3.Call>>()

    /** pump() 会从主线程和 IO 线程两侧进来，占位与发射得看着同一把锁。 */
    private val queueLock = Any()

    /** 用户主动暂停过的任务：OkHttp 被打断时会抛 IOException，那不该记成失败。 */
    private val pausedByUser = ConcurrentHashMap.newKeySet<String>()

    private val json = Json { ignoreUnknownKeys = true }
    private val KEY = stringPreferencesKey("downloads_v1")
    private val KEY_SEG_PARALLEL = intPreferencesKey("seg_parallel")
    private var lastSaveAt = 0L
    private var pendingSave: Job? = null

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _space = MutableStateFlow(0L)

    /**
     * 已占用的字节数，给下载页顶部那句「共 x MB」用。
     *
     * 算一次放在这儿，让界面去读这个值 —— File.walk 是把整棵目录树点一遍的同步遍历，
     * 直接在组合里调就是拿主线程去数文件，而下载页每 500ms 就在刷新。
     */
    val space: StateFlow<Long> = _space.asStateFlow()

    private val _speed = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * 每个任务当前的下载速度（字节 / 秒）。
     *
     * 只按最近 [SPEED_WINDOW_MS] 的样本算，而且**不落盘** —— 落盘的是字节数，
     * 进程重启后速度本来就该从 0 重新算，把上次的速度存下来显示是假数字。
     */
    val speed: StateFlow<Map<String, Long>> = _speed.asStateFlow()

    /** 任务 id → 最近的速度样本 (时刻, 累计字节)。 */
    private val speedWin = ConcurrentHashMap<String, ArrayDeque<Pair<Long, Long>>>()

    private val _segParallel = MutableStateFlow(DEFAULT_SEG_PARALLEL)

    /** 单集内同时下多少个分片（用户在「下载设置」里调，对正在下的任务不生效）。 */
    val segParallel: StateFlow<Int> = _segParallel.asStateFlow()

    private val root: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")

    init {
        scope.launch {
            load()
            refreshSpace()
        }
    }

    // ---------- 对外 ----------

    fun taskOf(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    fun taskOf(sourceId: String, mediaId: String, episodeIndex: Int): DownloadTask? =
        _tasks.value.firstOrNull { it.id == keyOf(sourceId, mediaId, episodeIndex) }

    /** 已占用的字节数。只在 [refreshSpace] 的协程里数，别拿到组合里直接调。 */
    private fun spaceUsed(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun refreshSpace() {
        scope.launch { _space.value = runCatching { spaceUsed() }.getOrDefault(0L) }
    }

    suspend fun enqueue(cfg: SourceConfig, m: Media, ep: Episode) {
        val id = keyOf(cfg.id, m.id, ep.index)
        _tasks.value.firstOrNull { it.id == id }?.let {
            if (it.status == DownloadStatus.Done || it.status == DownloadStatus.Running ||
                it.status == DownloadStatus.Queued
            ) return
        }
        val task = DownloadTask(
            id = id,
            mediaId = m.id,
            mediaTitle = m.title,
            mediaKey = m.key,
            mediaYear = m.year,
            mediaCover = m.cover,
            sourceId = cfg.id,
            sourceName = cfg.name,
            episodeName = ep.name,
            episodeIndex = ep.index,
            episodeUrl = ep.playUrl,
        )
        // 重下同一集（上一次是 Failed/Paused）时要把「用户按过暂停」这个名字摘掉，
        // 否则这一笔真失败了会被当成用户暂停，界面上一个原因都不写。
        pausedByUser -= id
        _tasks.update { list -> list.filterNot { it.id == id } + task }
        saveNow()
        pump()
    }

    /**
     * 整条线路一起排队，不是同时开跑。
     *
     * 「缓存全部」点下去可能有几十集，全发出去就是拿手机当下载机：连接互相抢带宽，
     * 站点那边也会被当成刷接口。排在后面的显示「排队中」，跑完一格补一格。
     */
    suspend fun enqueueAll(cfg: SourceConfig, m: Media, eps: List<Episode>) {
        eps.forEach { enqueue(cfg, m, it) }
    }

    fun pause(id: String) {
        pausedByUser += id
        patch(id, persist = true) { it.copy(status = DownloadStatus.Paused) }
        synchronized(queueLock) {
            cancelCalls(id)
            jobs.remove(id)?.job?.cancel()
        }
        forgetSpeed(id)
        pump()
    }

    fun resume(id: String) {
        val t = taskOf(id) ?: return
        pausedByUser -= id
        _tasks.update { list ->
            list.map { if (it.id == id) it.copy(status = DownloadStatus.Queued, error = null) else it }
        }
        saveNow()
        pump()
    }

    fun delete(id: String) {
        pausedByUser += id
        val run = synchronized(queueLock) {
            cancelCalls(id)
            jobs.remove(id)
        }
        run?.job?.cancel()
        _tasks.update { list -> list.filterNot { it.id == id } }
        saveNow()
        scope.launch {
            // 先等这一条真正停下来再清目录，否则它手上那半截写入会把删空的目录又填回去
            run?.job?.join()
            runCatching { dirOf(id).deleteRecursively() }
            // 任务没了，名字也得跟着从「用户自己按的暂停」里摘掉：id 是按（源、片、集）算的，
            // 留着它，以后重下同一集时真失败会被当成用户暂停而一句原因都不写。
            pausedByUser -= id
            forgetSpeed(id)
            refreshSpace()
            pump()
        }
    }

    /** 离线可播的地址；还没下完返回 null。 */
    fun localUri(id: String): String? {
        val name = taskOf(id)?.fileName ?: return null
        val f = File(dirOf(id), name)
        return if (f.isFile && f.length() > 0) "file://" + f.absolutePath else null
    }

    // ---------- 执行 ----------

    /**
     * 有空位就把排队的发出去。
     *
     * 占位（写进 [jobs]）和发射必须在同一把锁里：pump 会从主线程和 IO 线程两侧进来，
     * 慢一步就会把同一集发出去两遍，两笔写同一个文件。
     */
    private fun pump() = synchronized(queueLock) {
        val free = MAX_PARALLEL - jobs.size
        if (free <= 0) return
        _tasks.value.filter { it.status == DownloadStatus.Queued }.take(free).forEach { t ->
            val run = Run()
            if (jobs.putIfAbsent(t.id, run) == null) {
                run.job = scope.launch { runTask(t.id, run) }
                // 占位期间被 pause/delete 摘走了就掐掉自己这一笔：map 里已经换了人
                if (jobs[t.id] !== run) run.job?.cancel()
            }
        }
    }

    private suspend fun runTask(id: String, self: Run) {
        patch(id, persist = true) { it.copy(status = DownloadStatus.Running, error = null) }
        try {
            download(id)
            // 「下完了」以文件真的在盘上为准：删掉一条再立刻重下同一集时，删除那次的
            // 递归清目录可能落在新写的分片之后，而往已被 unlink 的文件句柄里写是不报错的 ——
            // 少这一步，界面就会亮着一句假的「可离线播放」。
            localUri(id) ?: error("缓存文件没能留在盘上（可能和一次删除撞上了），重新缓存这一集")
            patch(id, persist = true) {
                it.copy(
                    status = DownloadStatus.Done,
                    error = null,
                    segmentsDone = it.segmentsTotal.coerceAtLeast(it.segmentsDone),
                    bytesDone = if (it.bytesTotal > 0) it.bytesTotal else it.bytesDone,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 这一笔已经被摘走（暂停、删除、重下时被新的接管）就别写状态：
            // 被 cancel 的 OkHttp 抛的是 IOException，照原样记会写成一条假的「连接被切断」
            if (id in pausedByUser || jobs[id] !== self) return
            patch(id, persist = true) { it.copy(status = DownloadStatus.Failed, error = reasonOf(e)) }
        } finally {
            synchronized(queueLock) {
                // 只摘自己这一笔：重开同一集时 map 里已经换成新的壳了，按 id 盲删会把
                // 新那笔摘没，之后暂停和删除就管不住真正在跑的那一组连接。
                if (jobs[id] === self) {
                    jobs.remove(id)
                    calls.remove(id)
                }
            }
            forgetSpeed(id)
            refreshSpace()
            pump()
        }
    }

    /**
     * 网络异常的原文是英文（`connection closed`、`failed to connect to …`），
     * 直接显示等于把日志甩到界面上。自己抛的 IllegalStateException 已经带中文，原样放行。
     *
     * 别把这类失败都推给「Referer 没带对」。实测 dytt 系直链的 CDN 是按访问地区放行的：
     * 境外出口 IP 取 `vip.dytt-*.com` / `play.phimgood.com` 会拿到 403
     * 「The region has been denied.」，或在 TLS 握手阶段直接被断开 —— 换请求头、重试都没用，
     * 影枢也不去绕它。该做的是把「换条线路 / 换个源」说成用户真能执行的下一步：
     * 有些源（如北觅）每条片子只给一条线路，这时候能换的只有源。
     */
    private fun reasonOf(e: Exception): String = when {
        e is IllegalStateException -> e.message ?: "下载失败"
        e is java.net.SocketTimeoutException ->
            "连不上这个地址（超时）—— 换一条线路，或换一个源再缓存"
        e is javax.net.ssl.SSLException ->
            "对方 CDN 在建立加密连接时就断了 —— 这类直链常按访问地区放行，你当前的网络容易被拒。换一条线路，或换一个源。"
        e is java.io.IOException ->
            "连接被对方切断 —— 这条线路对你当前的网络不开放，或地址已失效。换一条线路，或换一个源再缓存。"
        else -> e.message ?: "下载失败"
    }

    private suspend fun download(id: String) {
        val t = taskOf(id) ?: return
        val cfg = sources.all().firstOrNull { it.id == t.sourceId }
            ?: error("播放源「${t.sourceName}」已经不在了，删掉这条重下")
        val page = t.episodeUrl?.takeIf { it.isNotBlank() }
            ?: error("这一集没有给出播放页地址，解析不了直链")

        val res = media.resolveStream(cfg, page)
        val direct = (res as? StreamResolution.Ready)?.url ?: error(res.userNote() ?: "解析不到可下载的地址")

        val dir = dirOf(id)
        if (t.segmentsDone == 0 && t.bytesDone == 0L && t.fileName == null) {
            // 全新一次缓存：从空目录开始。否则上一次（或上一次被删除时）残留的分片会被
            // 当成「已经下好了」直接跳过，进度是真的、文件却是空的。
            dir.deleteRecursively()
        }
        dir.mkdirs()
        if (isHls(direct)) downloadHls(id, dir, direct, cfg)
        else downloadSingle(id, dir, direct, cfg)
    }

    /**
     * 只看后缀名会漏：列表地址也可能长成 `/m3u8/xxxxx`，末尾没有 `.m3u8`。
     * 那种地址落到单文件分支，存下来的就是 playlist 正文冒充的 video.mp4 ——
     * 进度走到 100%、标着「可离线播放」，点开是坏的，看起来正是「缓存没起作用」。
     */
    private fun isHls(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return pathExt(url, "").equals(".m3u8", true) ||
            path.substringAfterLast('/', path).contains("m3u8", true) ||
            path.contains("/m3u8/", true)
    }

    private suspend fun downloadSingle(id: String, dir: File, url: String, cfg: SourceConfig) {
        withContext(Dispatchers.IO) {
            // 写入落在 .part 上，只有整条走完了才换成正式文件名：
            // 「文件在而且非空」从此等于「这一条是完整的」，本地播放不会去点一个半截的视频。
            val target = File(dir, "video" + pathExt(url, ".mp4"))
            val out = File(dir, target.name + ".part")
            val have = if (out.isFile) out.length() else 0L
            // 单文件不做多段并行：那要对方支持 Range，而这类站的单文件地址常常不给，
            // 失败模式多、收益小 —— 界面上一句「按字节续传」说的就是这一条。
            val call = newCall(id, url, cfg, rangeFrom = have.takeIf { it > 0 })
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) error(Http.failureReason(r.code))
                    val resumed = have > 0 && r.code == 206
                    var done = if (resumed) have else 0L
                    if (!resumed) { out.delete(); done = 0L }
                    val remaining = r.body?.contentLength() ?: -1L
                    // Content-Length 给了就是准确值，不用像 HLS 那样估
                    val total = if (remaining > 0) done + remaining else 0L
                    patch(id) { it.copy(bytesDone = done, bytesTotal = total, sizeEstimated = false) }

                    val src = r.body?.byteStream() ?: error("空响应")
                    FileOutputStream(out, resumed).use { os ->
                        val buf = ByteArray(64 * 1024)
                        var tick = done
                        while (true) {
                            // 协程被摘走时 ensureActive 会抛 CancellationException：
                            // 不检查的话这里会一直写到下完，和新起的那一笔一起磨同一个文件
                            ensureActive()
                            val n = src.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (done - tick >= 256 * 1024) {
                                tick = done
                                val d = done
                                reportBytes(id, d)
                                patch(id) { it.copy(bytesDone = d) }
                            }
                        }
                    }
                    if (total > 0 && done < total) error("下到 ${done * 100 / total}% 时断了，再点继续")
                    val d = done
                    reportBytes(id, d)
                    promote(out, target)
                    patch(id, persist = true) {
                        it.copy(
                            bytesDone = d,
                            bytesTotal = total.coerceAtLeast(d),
                            sizeEstimated = false,
                            fileName = target.name,
                        )
                    }
                }
            } finally {
                untrackCall(id, call)
            }
        }
    }

    /** 把写完了的 .part 换成正式文件名。 */
    private fun promote(part: File, into: File) {
        if (into.exists() && !into.delete()) error("${into.name} 删不掉，这一条缓存没法收尾")
        if (!part.renameTo(into)) {
            // 同目录改名几乎不会失败；真失败了（个别文件系统不允许）就整份搬过去
            part.copyTo(into, overwrite = true)
            part.delete()
        }
    }

    private suspend fun downloadHls(id: String, dir: File, url: String, cfg: SourceConfig) {
        // 上一次中断留下的 .part 一律作废：半截分片没有续传的意义，重来这一段就是
        dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        // 分片地址是相对**它自己那份列表**的。主列表和变体常常不在同一个目录
        // （实测 mux 的测试流：变体在 url_8/，分片又在 url_8/url_590/），
        // 用主列表的地址去拼就会 404 —— 播放器自己能播、这里缓存不了，就是这么来的。
        var listUrl = url
        // 分片用的是 newCall（带源站给的鉴权头），列表这两次拉取也得带上，
        // 否则要登录的源会在这里 401/403 —— 在线能播、缓存却起不来。
        val auth = Http.authHeaders(cfg)
        var playlist = Http.get(url, referer(cfg), auth)
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val best = bestVariant(playlist, url) ?: error("这份 m3u8 里只有主列表，没有可下载的分片")
            listUrl = best
            playlist = Http.get(best, referer(cfg), auth)
        }
        // 加密流：站点把密钥连同列表一起发给它自己的播放器，取钥解密就是标准 HLS 客户端
        // 要做的那一步，影枢照做。但它只用自己的会话去取 —— 站点不给的密钥不去换、不猜。
        // SAMPLE-AES / 私有 METHOD 属于 DRM 那一类（要还原对方的解密方案才成文件），不碰。
        requirePlainOrAes128(playlist)

        val segs = parseSegments(playlist, listUrl)
        if (segs.isEmpty()) error("这份 m3u8 里没有分片")
        val init = initMap(playlist, listUrl)

        // (密钥字节, IV)。同一条流通常全程一把。并发之后多个 worker 会同时来要，
        // 所以用 ConcurrentHashMap：拿两遍同一把密钥无害，边写边读才会出事。
        val keys = ConcurrentHashMap<String, ByteArray>()
        // 不用 computeIfAbsent：那个 lambda 不是 suspend 的，取密钥这一步放进去就编不过。
        // 两个 worker 同时撞上同一把密钥时最多重复取一次，比加锁简单、也无害。
        suspend fun keyOf(c: Crypt?): Pair<ByteArray, ByteArray>? = c?.let { cr ->
            val k = keys[cr.uri] ?: fetchKey(cr.uri, cfg, auth).also { keys.putIfAbsent(cr.uri, it) }
            k to cr.iv
        }

        patch(id) { it.copy(segmentsTotal = segs.size, segmentsDone = 0) }

        var initName: String? = null
        var initBytes = 0L
        if (init != null) {
            val (initUrl, initCrypt) = init
            val f = File(dir, "init" + pathExt(initUrl, ".mp4"))
            initName = f.name
            initBytes = if (f.isFile && f.length() > 0L) f.length()
            else fetchBytes(id, initUrl, cfg, f, keyOf(initCrypt))
        }

        /*
         * 分片并发。
         *
         * 分片之间没有依赖（每片自己解自己），串行下载就是让一条本来能跑满的连接
         * 在那儿排队等一个个 RTT。这里开 N 个 worker 从同一个 Channel 领活：
         * 领走即出队，所以一片不会被两个 worker 重复取。
         *
         * 完成数和字节数用原子量累加，每 4 片才往状态里写一次 ——
         * 每片都写的话下载页每片抖一下，而那一行数字本来就读不出名堂。
         */
        val doneN = AtomicInteger(0)
        val bytesN = AtomicLong(initBytes)
        val workers = _segParallel.value.coerceIn(1, MAX_SEG_PARALLEL)
        coroutineScope {
            val ch = Channel<Segment>(capacity = Channel.UNLIMITED)
            segs.forEach { ch.trySend(it) }
            ch.close()
            repeat(workers) {
                launch(Dispatchers.IO) {
                    for (seg in ch) {
                        val f = File(dir, seg.name)
                        val wrote = if (f.isFile && f.length() > 0L) f.length()
                        else fetchBytes(id, seg.url, cfg, f, keyOf(seg.crypt))
                        val d = doneN.incrementAndGet()
                        val b = bytesN.addAndGet(wrote)
                        if (d % 4 == 0 || d == segs.size) {
                            reportBytes(id, b)
                            patch(id) {
                                it.copy(
                                    segmentsDone = d,
                                    bytesDone = b,
                                    bytesTotal = estimateTotal(b, d, segs.size),
                                    sizeEstimated = d < segs.size,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 本地列表也是「写完才算数」：它一在，localUri 就会说这一集可以离线播了
        val playlistFile = File(dir, "index.m3u8")
        val tmp = File(dir, "index.m3u8.part")
        tmp.writeText(localPlaylist(segs, initName))
        promote(tmp, playlistFile)
        val all = bytesN.get()
        patch(id, persist = true) {
            it.copy(
                fileName = playlistFile.name,
                segmentsDone = segs.size,
                bytesDone = all,
                // 全在盘上了，总数就是实际落下来的字节 —— 不再需要估，「约」字去掉
                bytesTotal = all,
                sizeEstimated = false,
            )
        }
    }

    /**
     * HLS 的总大小只能估。
     *
     * 分片列表不给字节数，要准确值得对每一片发一次 HEAD —— 一部 2000 片的剧就是 2000 个
     * 额外请求，那是拿别人的站换界面上的一个数字。所以按「已下字节 ÷ 已完成片数 × 总片数」
     * 估一个，界面上带「约」，下完之后换成实际落盘的字节。
     */
    private fun estimateTotal(bytesDone: Long, segsDone: Int, segsTotal: Int): Long =
        if (segsDone > 0) bytesDone * segsTotal / segsDone else 0L

    private suspend fun fetchBytes(
        id: String,
        url: String,
        cfg: SourceConfig,
        into: File,
        key: Pair<ByteArray, ByteArray>? = null,
    ): Long = withContext(Dispatchers.IO) {
        ensureActive()
        val call = newCall(id, url, cfg, rangeFrom = null)
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) error(Http.failureReason(r.code))
                val bytes = r.body?.bytes() ?: error("空响应")
                val plain = if (key == null) bytes else aesCbcDecrypt(bytes, key.first, key.second)
                // 先落到 .part 再改名：这一段的完成判据是「正式名字的文件的字节非空」，
                // 直接写进正式名字，就是让一段半截的分片冒充一片完整的。
                val part = File(into.parentFile, into.name + ".part")
                part.writeBytes(plain)
                promote(part, into)
                plain.size.toLong()
            }
        } finally {
            untrackCall(id, call)
        }
    }

    private fun newCall(id: String, url: String, cfg: SourceConfig, rangeFrom: Long?): okhttp3.Call {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", Http.UA)
            .header("Referer", referer(cfg))
        Http.authHeaders(cfg).forEach { (k, v) -> b.header(k, v) }
        if (rangeFrom != null && rangeFrom > 0) b.header("Range", "bytes=$rangeFrom-")
        val call = Http.client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
            .newCall(b.build())
        // 挂到这一组里，暂停 / 删除时才掐得住：分片并发之后一个任务同时有好几个连接在飞
        calls.getOrPut(id) { java.util.Collections.synchronizedSet(mutableSetOf()) } += call
        return call
    }

    private fun cancelCalls(id: String) {
        calls.remove(id)?.forEach { runCatching { it.cancel() } }
    }

    /** 请求结束（成功、失败、被取消都算）后把它从组里摘掉，别攒着一堆死对象。 */
    private fun untrackCall(id: String, call: okhttp3.Call) {
        calls[id]?.remove(call)
    }

    /**
     * 报一次累计字节，顺带算速度。
     *
     * 取最近 3 秒那个窗口：更长的窗口会把「刚连上时的慢」平均进来，显示出一个永远在爬的假速度；
     * 更短则抖得没法读。窗口里样本太少（不到半秒）时保留上一个值，不显示 0。
     */
    private fun reportBytes(id: String, totalBytes: Long) {
        val now = System.currentTimeMillis()
        val dq = speedWin.getOrPut(id) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(now to totalBytes)
            while (dq.size > 1 && now - dq.first().first > SPEED_WINDOW_MS) dq.removeFirst()
            val span = now - dq.first().first
            if (span >= 500L) {
                val perSec = ((totalBytes - dq.first().second) * 1000 / span).coerceAtLeast(0L)
                _speed.update { it + (id to perSec) }
            }
        }
    }

    private fun forgetSpeed(id: String) {
        speedWin.remove(id)
        _speed.update { it - id }
    }

    private fun referer(cfg: SourceConfig) = cfg.baseUrl.trimEnd('/') + "/"

    // ---------- m3u8 ----------

    private data class Segment(val dur: Double, val url: String, val name: String, val crypt: Crypt?)

    /** 一把密钥：地址 + 这一段的 IV。 */
    private data class Crypt(val uri: String, val iv: ByteArray)

    /**
     * 只放行「不加密」和 AES-128。
     *
     * AES-128 的密钥是站点随列表一起发给任何播放器的，取来解密就是 HLS 客户端的标准动作。
     * SAMPLE-AES 那类要把对方的条件解密方案复刻一遍才成文件 —— 那是 DRM，不是缓存。
     */
    private fun requirePlainOrAes128(playlist: String) {
        val bad = playlist.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#EXT-X-KEY") }
            .map { it.substringAfter("METHOD=", "").substringBefore(',').trim() }
            .firstOrNull { it.isNotEmpty() && it != "NONE" && it != "AES-128" }
        if (bad != null) {
            error(
                if (bad == "AES-256") "这条线路用的是 AES-256，本机 Android 版本不支持解密。换一条线路试试。"
                else "这条线路是 $bad 加密（DRM 那一类），影枢不还原对方的解密方案，缓存不了。换一条不加密的线路试试。"
            )
        }
    }

    /**
     * 从一行 `#EXT-X-KEY` 读出 (密钥地址, 显式 IV)。
     *
     * METHOD=NONE 或没有 URI 时给 null，表示这一段不加密。IV 可能是 null ——
     * 协议规定此时用分片序号当 IV，由调用方补。
     */
    private fun parseKey(line: String, base: String): Pair<String, ByteArray?>? {
        val method = line.attr("METHOD") ?: return null
        if (method == "NONE") return null
        val uri = line.attr("URI")?.let { resolve(base, it) } ?: return null
        val iv = line.attr("IV")?.removePrefix("0x")?.removePrefix("0X")
            ?.chunked(2)?.mapNotNull { it.toIntOrNull(16)?.toByte() }?.toByteArray()
        return uri to iv?.takeIf { it.size == 16 }
    }

    private fun String.attr(name: String): String? =
        // 引号形式要先试：密钥地址里可能带逗号，那时不引号的写法会在逗号处截断
        Regex("$name=\"([^\"]*)\"").find(this)?.groupValues?.get(1)
            ?: Regex("$name=([^,]+)").find(this)?.groupValues?.get(1)?.trim()

    /** 没写 IV 时按协议用分片序号当 IV（128 位大端）。 */
    private fun seqIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0..7) iv[15 - i] = (seq shr (8 * i) and 0xFF).toByte()
        return iv
    }

    private suspend fun fetchKey(uri: String, cfg: SourceConfig, auth: Map<String, String>): ByteArray =
        withContext(Dispatchers.IO) {
            val call = Request.Builder().url(uri)
                .header("User-Agent", Http.UA)
                .header("Referer", referer(cfg))
                .apply { auth.forEach { (k, v) -> header(k, v) } }
                .build()
            Http.client.newCall(call).execute().use { r ->
                if (!r.isSuccessful)
                    error(Http.failureReason(r.code) + " —— 站点不给这把密钥，这一条缓存不了")
                val b = r.body?.bytes() ?: error("密钥是空的")
                if (b.size != 16) error("站点给的密钥不是 16 字节（${b.size} 字节），解不了")
                b
            }
        }

    /** AES-128-CBC。末字节是填充长度；对不上就原样留着（有的打包器不填）。 */
    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val out = c.doFinal(data)
        val pad = out.lastOrNull()?.toInt()?.and(0xFF) ?: 0
        return if (pad in 1..16 && pad <= out.size && out.takeLast(pad).all { (it.toInt() and 0xFF) == pad })
            out.copyOf(out.size - pad) else out
    }

    private fun bestVariant(playlist: String, base: String): String? {
        val lines = playlist.lines()
        var bestBw = -1L
        var best: String? = null
        for (i in lines.indices) {
            val l = lines[i].trim()
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }
                ?.trim() ?: continue
            if (bw >= bestBw) { bestBw = bw; best = resolve(base, uri) }
        }
        return best
    }

    /**
     * 逐行扫分片，同时把「这一段用哪把密钥」记下来。
     *
     * HLS 的写法是 `#EXT-X-KEY` 从它那一行起对后续分片生效，中途可以换钥；
     * 没给 IV 的分片按协议拿自己的序号当 IV，所以序号也要一路数着。
     */
    private fun parseSegments(playlist: String, base: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var dur = 0.0
        var key: Pair<String, ByteArray?>? = null
        var seq = 0L
        playlist.lineSequence().forEach { raw ->
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L

                l.startsWith("#EXTINF") ->
                    dur = l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                l.startsWith("#EXT-X-KEY") -> key = parseKey(l, base)

                l.isEmpty() || l.startsWith("#") -> Unit
                else -> {
                    val idx = out.size
                    val abs = resolve(base, l) ?: return@forEach
                    out += Segment(
                        dur, abs, "seg_%05d%s".format(idx, pathExt(abs, ".ts")),
                        key?.let { Crypt(it.first, it.second ?: seqIv(seq + idx)) },
                    )
                }
            }
        }
        return out
    }

    /** 头段（fMP4 的 init）。它跟着列表里当时那把密钥走。 */
    private fun initMap(playlist: String, base: String): Pair<String, Crypt?>? {
        var key: Pair<String, ByteArray?>? = null
        var seq = 0L
        for (raw in playlist.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L

                l.startsWith("#EXT-X-KEY") -> key = parseKey(l, base)

                l.startsWith("#EXT-X-MAP") -> {
                    val uri = l.attr("URI")?.let { resolve(base, it) } ?: return null
                    return uri to key?.let { Crypt(it.first, it.second ?: seqIv(seq)) }
                }

                else -> Unit
            }
        }
        return null
    }

    /**
     * 本地 playlist。init 段写的是它**实际落盘的文件名** ——
     * 对方给的是 `.m4s` 时硬写 `init.mp4` 会让离线播放找不到头段。
     */
    private fun localPlaylist(segs: List<Segment>, initName: String?): String = buildString {
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:4\n")
        val max = segs.maxOf { it.dur }.coerceAtLeast(1.0)
        append("#EXT-X-TARGETDURATION:").append(max.toLong() + 1).append('\n')
        append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        initName?.let { append("#EXT-X-MAP:URI=\"").append(it).append("\"\n") }
        segs.forEach {
            append("#EXTINF:").append(it.dur).append(",\n")
            append(it.name).append('\n')
        }
        append("#EXT-X-ENDLIST\n")
    }

    private fun resolve(base: String, ref: String): String? = runCatching {
        if (ref.startsWith("http")) ref else URI(base).resolve(ref).toString()
    }.getOrNull()

    private fun pathExt(url: String, fallback: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot).lowercase() else ""
        return if (ext.length in 3..5 && ext.all { it.isLetterOrDigit() || it == '.' }) ext else fallback
    }

    // ---------- 持久化 ----------

    private fun dirOf(id: String): File = File(root, id)

    private fun patch(id: String, persist: Boolean = false, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
        if (persist) saveNow() else saveSoon()
    }

    /** 同 [WatchRepository]：窗口内的写改成延后补一次，别把最后一笔丢掉。 */
    private fun saveSoon() {
        val wait = 1_500L - (System.currentTimeMillis() - lastSaveAt)
        if (wait <= 0L) {
            saveNow()
            return
        }
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(wait)
            saveNow()
        }
    }

    private fun saveNow() {
        lastSaveAt = System.currentTimeMillis()
        val snapshot = _tasks.value
        scope.launch { runCatching { context.downloadStore.edit { it[KEY] = json.encodeToString(snapshot) } } }
    }

    private suspend fun load() {
        val p = context.downloadStore.data.first()
        _segParallel.value = (p[KEY_SEG_PARALLEL] ?: DEFAULT_SEG_PARALLEL.toLong()).toInt()
            .coerceIn(1, MAX_SEG_PARALLEL)
        val raw = p[KEY]
        val stored = raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<List<DownloadTask>>(it) }.getOrElse { emptyList() }
        }.orEmpty()
        // 进程被系统回收后没有协程还在跑，界面上却显示「下载中」是骗人 —— 落回已暂停
        val fixed = stored.map {
            if (it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued)
                it.copy(status = DownloadStatus.Paused) else it
        }
        // 落盘读回来之前用户可能已经排了一个任务：整份覆盖会把那一笔吞掉，
        // 之后任何一次保存再把「没有它」的状态写回磁盘。所以合并，内存里的优先。
        val merged = (_tasks.value + fixed).distinctBy { it.id }
        _tasks.value = merged
        if (merged != stored) {
            lastSaveAt = System.currentTimeMillis()
            context.downloadStore.edit { it[KEY] = json.encodeToString(merged) }
        }
    }

    /**
     * 改「单集内同时下几个分片」。
     *
     * 只影响**之后**开始的任务：正在下的那一集已经按旧档数开好了 worker，
     * 中途改档要么杀掉正在写的分片、要么凭空多开一路，两种都比等它下完更糟。
     */
    fun setSegParallel(n: Int) {
        val v = n.coerceIn(1, MAX_SEG_PARALLEL)
        _segParallel.value = v
        scope.launch { context.downloadStore.edit { it[KEY_SEG_PARALLEL] = v } }
    }

    private companion object {
        /** 同时在跑几集。多了没意义：带宽就那么宽，站点那边也容易把并发当成刷接口。 */
        const val MAX_PARALLEL = 3

        /** 一档最多给到 8：再多就是拿别人的 CDN 试上限，被拒的是用户自己。 */
        const val MAX_SEG_PARALLEL = 8
        const val DEFAULT_SEG_PARALLEL = 4

        /** 速度取多长的窗口：太长会把刚连上时的慢平均进来，太短抖得读不出。 */
        const val SPEED_WINDOW_MS = 3_000L

        fun keyOf(sourceId: String, mediaId: String, episodeIndex: Int) =
            "dl_${sourceId}_${mediaId}_$episodeIndex".replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
