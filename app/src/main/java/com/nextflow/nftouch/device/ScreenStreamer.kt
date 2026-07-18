package com.nextflow.nftouch.device

import android.graphics.Bitmap
import android.graphics.Canvas
import com.nextflow.nftouch.service.ClawAccessibilityService
import com.nextflow.nftouch.utils.XLog
import java.io.ByteArrayOutputStream

/**
 * 屏幕推流器：定时截图 → 缩放 → WebP 压缩 → 通过 [frameSender] 回调发送 Binary Frame。
 *
 * 弱网优化参数：3fps / 720p 缩放 / WebP 30% 质量（等效 JPEG 35%）。
 * 仅支持 Android 11+（API 30+ takeScreenshot）。
 */
class ScreenStreamer(
    private val frameSender: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "ScreenStreamer"
        private const val FPS = 3
        private const val QUALITY = 30        // WebP 质量 30%，等效 JPEG ~35%
        private const val MIN_QUALITY = 15     // 弱网最低质量
        private const val WRITE_TIMEOUT_MS = 5000L  // 单帧写入超时（弱网保底）
        const val FRAME_MIME = "image/webp"

        val WIDTH_TIERS = intArrayOf(240, 360, 480, 540, 720)
    }

    @Volatile var currentMaxWidth = WIDTH_TIERS.last()  // 初始 720，外部可根据 RTT 调整

    @Volatile
    private var running = false
    private var streamThread: Thread? = null
    private var seq = 0
    @Volatile var currentQuality = QUALITY
    private var lastFrameSize = 0

    fun start() {
        if (running) return
        running = true
        seq = 0
        currentQuality = QUALITY
        streamThread = Thread({
            XLog.i(TAG, "Screen stream started (${FPS}fps, WebP ${QUALITY}%, ${currentMaxWidth}px max, mime=$FRAME_MIME)")
            val frameInterval = 1000L / FPS
            while (running) {
                try {
                    val startTime = System.currentTimeMillis()
                    val bitmap = ClawAccessibilityService.getInstance()?.takeScreenshot(2000)
                    if (bitmap != null) {
                        // 1. 缩放到 MAX_WIDTH
                        val scaled = scaleBitmap(bitmap)
                        bitmap.recycle()

                        if (scaled != null) {
                            // 记录缩放后尺寸到 RemoteExecutor 用于坐标转换
                            RemoteExecutor.getInstance()?.let {
                                it.jpgW = scaled.width
                                it.jpgH = scaled.height
                            }

                            // 2. WebP 压缩
                            val webp = compressBitmap(scaled, currentQuality)
                            scaled.recycle()

                            if (webp != null) {
                                // 3. 自适应质量：上一帧 > 100KB 降低质量，反之恢复
                                lastFrameSize = webp.size
                                if (webp.size > 100_000 && currentQuality > MIN_QUALITY) {
                                    currentQuality = maxOf(currentQuality - 5, MIN_QUALITY)
                                } else if (webp.size < 50_000 && currentQuality < QUALITY) {
                                    currentQuality = minOf(currentQuality + 2, QUALITY)
                                }

                                val seqBytes = byteArrayOf(
                                    ((seq shr 24) and 0xFF).toByte(),
                                    ((seq shr 16) and 0xFF).toByte(),
                                    ((seq shr 8) and 0xFF).toByte(),
                                    (seq and 0xFF).toByte()
                                )
                                seq++
                                frameSender(seqBytes + webp)
                            }
                        }
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    val sleepTime = frameInterval - elapsed
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime)
                    }
                } catch (e: InterruptedException) {
                    XLog.i(TAG, "Screen stream interrupted")
                    break
                } catch (e: Exception) {
                    XLog.e(TAG, "Screenshot error: ${e.message}")
                }
            }
            XLog.i(TAG, "Screen stream stopped")
        }, "screen-streamer").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        streamThread?.interrupt()
        streamThread = null
    }

    /** 等比缩放到 currentMaxWidth */
    private fun scaleBitmap(original: Bitmap): Bitmap? {
        return try {
            val w = original.width
            val h = original.height
            if (w <= currentMaxWidth) return original.copy(Bitmap.Config.RGB_565, false)
            val ratio = currentMaxWidth.toFloat() / w
            val newW = currentMaxWidth
            val newH = (h * ratio).toInt()
            val scaled = Bitmap.createScaledBitmap(original, newW, newH, true)
            XLog.d(TAG, "Scale: ${w}x${h} → ${newW}x${newH}")
            scaled
        } catch (e: Exception) {
            XLog.e(TAG, "Scale error: ${e.message}")
            null
        }
    }

    private fun compressBitmap(bitmap: Bitmap, quality: Int): ByteArray? {
        return try {
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, bos)
            bos.toByteArray()
        } catch (e: Exception) {
            XLog.e(TAG, "Compress error: ${e.message}")
            null
        }
    }
}
