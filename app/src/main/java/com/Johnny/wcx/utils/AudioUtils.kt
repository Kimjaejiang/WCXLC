package com.Johnny.wcx.utils

import java.io.File

object AudioUtils {

    external fun anyToSilk(mp3Path: String, silkPath: String): Boolean
    external fun silkToPcm(silkPath: String, pcmPath: String): Boolean

    /**
     * PCM → MP3。注意参数语义：native 侧 (pcm_path, mp3_path)，
     * 这里的形参名沿用了历史上的错误命名，调用时按「PCM 在前、MP3 在后」理解。
     */
    external fun pcmToMp3(pcmPath: String, mp3Path: String): Boolean

    /**
     * native 时长解析。对微信 SILK 语音返回 0（原因见 [getDurationMsSafe]），
     * 除非确实需要 native 原始行为，否则请改用 [getDurationMsSafe]。
     */
    external fun getDurationMs(path: String): Long

    private val SILK_MAGIC = "#!SILK_V3".toByteArray(Charsets.US_ASCII)
    private const val SILK_FRAME_MS = 20

    private const val SILK_SAMPLE_RATE = 24000

    /**
     * 把微信 SILK 语音转成可被 MediaPlayer 播放的 MP3。
     *
     * 收藏/聊天里的语音都是 SILK 容器，MediaPlayer 直接解不了（prepare 报 status=0x1）。
     * 这里走 native 的两步转换：SILK → PCM（24kHz 单声道）→ MP3。
     * 结果按源文件内容缓存到 [cacheDir]，同一段语音只转一次。
     *
     * @return 可播放的 MP3 文件路径；失败返回 null。
     */
    fun silkToPlayableMp3(silkPath: String, cacheDir: java.io.File): String? = runCatching {
        val src = File(silkPath)
        if (!src.exists()) return@runCatching null

        // 用路径+大小+修改时间做缓存键：内容变了自然失效，且不需要读全文件
        val key = "${src.absolutePath}|${src.length()}|${src.lastModified()}".hashCode().toUInt().toString(16)
        val mp3 = File(cacheDir, "voice_$key.mp3")
        if (mp3.exists() && mp3.length() > 0) return@runCatching mp3.absolutePath

        cacheDir.mkdirs()
        val pcm = File(cacheDir, "voice_$key.pcm")
        try {
            if (!silkToPcm(silkPath, pcm.absolutePath)) {
                WeLogger.w("AudioUtils", "silkToPcm failed for $silkPath")
                return@runCatching null
            }
            if (!pcmToMp3(pcm.absolutePath, mp3.absolutePath)) {
                WeLogger.w("AudioUtils", "pcmToMp3 failed for $silkPath")
                return@runCatching null
            }
            if (!mp3.exists() || mp3.length() == 0L) return@runCatching null
            mp3.absolutePath
        } finally {
            pcm.delete()
        }
    }.getOrNull()

    /** SILK 解码后的采样率，供播放侧参考。 */
    val silkSampleRate: Int get() = SILK_SAMPLE_RATE

    /**
     * 语音时长（毫秒），native 失败时用纯 Kotlin 解析兜底。
     *
     * native 的 [getDurationMs] 对微信语音返回 0：它用 SKP_Silk_SDK_get_TOC 逐包解析，
     * 而微信的包结构让 TOC 判定为 corrupt，于是每个包都被跳过，最终 total_ms 保持 0
     * 且不报错。后果是转发语音时 durationMs=0，sendVoice 内部 coerceIn(1, 60000) 把它
     * 夹成 1ms，服务端视为无效语音而拒收（表现为「已转发(部分失败)」、收方收不到）。
     * 这里在 native 拿不到有效值时，自己按包结构数数：每个 SILK 包按一帧 20ms 计。
     */
    fun getDurationMsSafe(path: String): Long {
        val native = runCatching { getDurationMs(path) }.getOrDefault(0L)
        if (native > 0) return native
        return runCatching { durationMsFromSilk(path) }.getOrDefault(0L)
    }

    /** 按微信 SILK 容器结构统计时长：0x02 + "#!SILK_V3" 头，随后是 [2字节长度][payload] 重复。 */
    private fun durationMsFromSilk(path: String): Long {
        val f = File(path)
        if (!f.exists()) return 0L
        f.inputStream().buffered().use { input ->
            val header = ByteArray(1 + SILK_MAGIC.size)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) return 0L
                read += n
            }
            // 微信格式：先一个 0x02，再是 "#!SILK_V3"
            if (header[0] != 0x02.toByte()) return 0L
            for (i in SILK_MAGIC.indices) {
                if (header[i + 1] != SILK_MAGIC[i]) return 0L
            }

            var packets = 0L
            val lenBuf = ByteArray(2)
            while (true) {
                var n = 0
                while (n < 2) {
                    val r = input.read(lenBuf, n, 2 - n)
                    if (r <= 0) return packets * SILK_FRAME_MS
                    n += r
                }
                val packetLen = (lenBuf[0].toInt() and 0xFF) or ((lenBuf[1].toInt() and 0xFF) shl 8)
                if (packetLen <= 0) return packets * SILK_FRAME_MS
                var skipped = 0L
                while (skipped < packetLen) {
                    val s = input.skip(packetLen - skipped)
                    if (s <= 0) return packets * SILK_FRAME_MS
                    skipped += s
                }
                packets++
            }
        }
    }
}
