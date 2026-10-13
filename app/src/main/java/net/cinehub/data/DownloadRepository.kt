package net.cinehub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
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
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()

    /** 用户主动暂停过的任务：OkHttp 被打断时会抛 IOException，那不该记成失败。 */
    private val pausedByUser = ConcurrentHashMap.newKeySet<String>()

    private val json = Json { ignoreUnknownKeys = true }
    private val KEY = stringPreferencesKey("downloads_v1")
    private var lastSaveAt = 0L

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val root: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")

    init {
        scope.launch { load() }
    }

    // ---------- 对外 ----------

    fun taskOf(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    fun taskOf(sourceId: String, mediaId: String, episodeIndex: Int): DownloadTask? =
        _tasks.value.firstOrNull { it.id == keyOf(sourceId, mediaId, episodeIndex) }

    /** 已占用的字节数，给下载页顶部那句「共 x MB」用。 */
    fun spaceUsed(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

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
            sourceId = cfg.id,
            sourceName = cfg.name,
            episodeName = ep.name,
            episodeIndex = ep.index,
            episodeUrl = ep.playUrl,
        )
        _tasks.update { list -> list.filterNot { it.id == id } + task }
        saveNow()
        start(task)
    }

    suspend fun enqueueAll(cfg: SourceConfig, m: Media, eps: List<Episode>) {
        eps.forEach { enqueue(cfg, m, it) }
    }

    fun pause(id: String) {
        pausedByUser += id
        patch(id, persist = true) { it.copy(status = DownloadStatus.Paused) }
        calls.remove(id)?.cancel()
        jobs.remove(id)?.cancel()
    }

    fun resume(id: String) {
        val t = taskOf(id) ?: return
        pausedByUser -= id
        start(t)
    }

    fun delete(id: String) {
        pausedByUser += id
        calls.remove(id)?.cancel()
        val job = jobs.remove(id)
        job?.cancel()
        _tasks.update { list -> list.filterNot { it.id == id } }
        saveNow()
        scope.launch {
            // 先等这一条真正停下来再清目录，否则它手上那半截写入会把删空的目录又填回去
            job?.join()
            runCatching { dirOf(id).deleteRecursively() }
        }
    }

    /** 离线可播的地址；还没下完返回 null。 */
    fun localUri(id: String): String? {
        val name = taskOf(id)?.fileName ?: return null
        val f = File(dirOf(id), name)
        return if (f.isFile && f.length() > 0) "file://" + f.absolutePath else null
    }

    // ---------- 执行 ----------

    private fun start(task: DownloadTask) {
        jobs[task.id]?.cancel()
        jobs[task.id] = scope.launch { runTask(task.id) }
    }

    private suspend fun runTask(id: String) {
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
            if (id in pausedByUser) return
            val why = reasonOf(e)
            patch(id, persist = true) { it.copy(status = DownloadStatus.Failed, error = why) }
        } finally {
            jobs.remove(id)
            calls.remove(id)
        }
    }

    /**
     * 网络异常的原文是英文（`connection closed`、`failed to connect to …`），
     * 直接显示等于把日志甩到界面上。自己抛的 IllegalStateException 已经带中文，原样放行。
     */
    private fun reasonOf(e: Exception): String = when {
        e is IllegalStateException -> e.message ?: "下载失败"
        e is java.net.SocketTimeoutException -> "连不上这个地址（超时），换一条线路再试"
        e is java.io.IOException ->
            "连接被对方切断 —— 这类 CDN 通常只认本站页面的访问，或者这条线路已经失效。换一条线路再缓存。"
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
        if (pathExt(direct, "").equals(".m3u8", true)) downloadHls(id, dir, direct, cfg)
        else downloadSingle(id, dir, direct, cfg)
    }

    private suspend fun downloadSingle(id: String, dir: File, url: String, cfg: SourceConfig) {
        withContext(Dispatchers.IO) {
            val out = File(dir, "video" + pathExt(url, ".mp4"))
            val have = if (out.isFile) out.length() else 0L
            val call = newCall(url, cfg, rangeFrom = have.takeIf { it > 0 })
            calls[id] = call
            call.execute().use { r ->
                if (!r.isSuccessful) error(Http.failureReason(r.code))
                val resumed = have > 0 && r.code == 206
                var done = if (resumed) have else 0L
                if (!resumed) { out.delete(); done = 0L }
                val remaining = r.body?.contentLength() ?: -1L
                val total = if (remaining > 0) done + remaining else 0L
                patch(id) { it.copy(bytesDone = done, bytesTotal = total) }

                val src = r.body?.byteStream() ?: error("空响应")
                FileOutputStream(out, resumed).use { os ->
                    val buf = ByteArray(64 * 1024)
                    var tick = done
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        if (done - tick >= 512 * 1024) {
                            tick = done
                            val d = done
                            patch(id) { it.copy(bytesDone = d) }
                        }
                    }
                }
                if (total > 0 && done < total) error("下到 ${done * 100 / total}% 时断了，再点继续")
                val d = done
                patch(id, persist = true) { it.copy(bytesDone = d, bytesTotal = total.coerceAtLeast(d), fileName = out.name) }
            }
        }
    }

    private suspend fun downloadHls(id: String, dir: File, url: String, cfg: SourceConfig) {
        var playlist = Http.get(url, referer = referer(cfg))
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val best = bestVariant(playlist, url) ?: error("这份 m3u8 里只有主列表，没有可下载的分片")
            playlist = Http.get(best, referer = referer(cfg))
        }
        // 加密流要拿站点给的密钥、按它的方案解密才成文件。
        // 影枢不做这件事 —— 那是绕过对方的内容保护，不是缓存。
        if (playlist.contains("#EXT-X-KEY")) {
            error("这条线路是加密 HLS（EXT-X-KEY），影枢不解站点的内容加密，缓存不了。换一条不加密的线路试试。")
        }

        val segs = parseSegments(playlist, url)
        if (segs.isEmpty()) error("这份 m3u8 里没有分片")
        val init = initMap(playlist, url)

        patch(id) { it.copy(segmentsTotal = segs.size, segmentsDone = 0) }

        var n = 0
        if (init != null) {
            val f = File(dir, "init" + pathExt(init, ".mp4"))
            if (!f.isFile || f.length() == 0L) fetchBytes(init, cfg, f)
        }
        segs.forEachIndexed { i, seg ->
            val f = File(dir, seg.name)
            if (!f.isFile || f.length() == 0L) fetchBytes(seg.url, cfg, f)
            n++
            val d = n
            patch(id) { it.copy(segmentsDone = d) }
        }

        File(dir, "index.m3u8").writeText(localPlaylist(segs, init != null))
        patch(id, persist = true) { it.copy(fileName = "index.m3u8", segmentsDone = segs.size) }
    }

    private suspend fun fetchBytes(url: String, cfg: SourceConfig, into: File) {
        withContext(Dispatchers.IO) {
            val call = newCall(url, cfg, rangeFrom = null)
            call.execute().use { r ->
                if (!r.isSuccessful) error(Http.failureReason(r.code))
                val bytes = r.body?.bytes() ?: error("空响应")
                into.writeBytes(bytes)
            }
        }
    }

    private fun newCall(url: String, cfg: SourceConfig, rangeFrom: Long?): okhttp3.Call {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", Http.UA)
            .header("Referer", referer(cfg))
        Http.authHeaders(cfg).forEach { (k, v) -> b.header(k, v) }
        if (rangeFrom != null && rangeFrom > 0) b.header("Range", "bytes=$rangeFrom-")
        return Http.client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
            .newCall(b.build())
    }

    private fun referer(cfg: SourceConfig) = cfg.baseUrl.trimEnd('/') + "/"

    // ---------- m3u8 ----------

    private data class Segment(val dur: Double, val url: String, val name: String)

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

    private fun parseSegments(playlist: String, base: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var dur = 0.0
        playlist.lineSequence().forEach { raw ->
            val l = raw.trim()
            when {
                l.startsWith("#EXTINF") ->
                    dur = l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                l.isEmpty() || l.startsWith("#") -> Unit
                else -> {
                    val idx = out.size
                    val abs = resolve(base, l) ?: return@forEach
                    out += Segment(dur, abs, "seg_%05d%s".format(idx, pathExt(abs, ".ts")))
                }
            }
        }
        return out
    }

    private fun initMap(playlist: String, base: String): String? {
        val line = playlist.lineSequence().firstOrNull { it.trim().startsWith("#EXT-X-MAP") } ?: return null
        val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: return null
        return resolve(base, uri)
    }

    private fun localPlaylist(segs: List<Segment>, hasInit: Boolean): String = buildString {
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:4\n")
        val max = segs.maxOf { it.dur }.coerceAtLeast(1.0)
        append("#EXT-X-TARGETDURATION:").append(max.toLong() + 1).append('\n')
        append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        if (hasInit) append("#EXT-X-MAP:URI=\"init.mp4\"\n")
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

    private fun saveSoon() {
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < 1_500L) return
        saveNow()
    }

    private fun saveNow() {
        lastSaveAt = System.currentTimeMillis()
        val snapshot = _tasks.value
        scope.launch { runCatching { context.downloadStore.edit { it[KEY] = json.encodeToString(snapshot) } } }
    }

    private suspend fun load() {
        val raw = context.downloadStore.data.map { it[KEY] }.first()
        val stored = raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<List<DownloadTask>>(it) }.getOrElse { emptyList() }
        }.orEmpty()
        // 进程被系统回收后没有协程还在跑，界面上却显示「下载中」是骗人 —— 落回已暂停
        val fixed = stored.map {
            if (it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued)
                it.copy(status = DownloadStatus.Paused) else it
        }
        _tasks.value = fixed
        if (fixed != stored) {
            lastSaveAt = System.currentTimeMillis()
            context.downloadStore.edit { it[KEY] = json.encodeToString(fixed) }
        }
    }

    private companion object {
        fun keyOf(sourceId: String, mediaId: String, episodeIndex: Int) =
            "dl_${sourceId}_${mediaId}_$episodeIndex".replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
