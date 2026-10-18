package net.cinehub.data

/**
 * 字节数 → 人话。全应用只有这一份。
 *
 * 抽出来的理由不是「代码复用」四个字，是**同一个量在两屏上必须写成同一个数**：
 * 缓存页写「已下 1.0 MB」、播放器写「0.9 MB/s」，用户会以为这是两件事，
 * 而它们其实是同一条线路的同一批字节。原来播放器那一份是照着缓存页抄的，
 * 抄的时候还特意写了一句「两处各写一套的话，同一条 4kku 的线在播放器里写 1.0 MB/s、
 * 在缓存页写 0.9 MB/s，看着就是两个数」—— 那就该只有一份。
 *
 * 口径：
 * - 1024 进制（KB / MB / GB），与安卓上「文件大小」的通行写法一致；
 * - MB / GB 档留一位小数，**截断而不是四舍五入**：1.99 MB 写「1.9 MB」。
 *   这一条是故意的，而且两边必须一致 —— 有人把它「修」成 round，另一屏就会差 0.1；
 * - KB 档写整数：这一档的小数位本来就在跳（一次 200ms 的采样就能让它变一次），
 *   写出来只是噪音；
 * - 小于 1 KB 照实写 B。
 *
 * 纯 Kotlin，没有 Android 依赖，所以能直接单测。
 */
object ByteFormat {

    /** 不带速率单位的量：`12.3 MB`。 */
    fun bytes(b: Long): String {
        val tenthsGb = b * 10 / (1L shl 30)
        val tenthsMb = b * 10 / (1L shl 20)
        return when {
            b >= 1L shl 30 -> "${tenthsGb / 10}.${tenthsGb % 10} GB"
            b >= 1L shl 20 -> "${tenthsMb / 10}.${tenthsMb % 10} MB"
            b >= 1024 -> "${b / 1024} KB"
            else -> "$b B"
        }
    }

    /**
     * 每秒多少字节：`1.5 MB/s`。
     *
     * 量不出来（<= 0）返回 null 而不是「0 B/s」—— 「零」和「没量到」是两件事，
     * 前者是一句可以写出来的结论，后者只能什么都不说。由调用方决定整项不写。
     */
    fun perSec(bytesPerSec: Long): String? =
        if (bytesPerSec <= 0L) null else bytes(bytesPerSec) + "/s"
}
