package com.hasan.clearvoice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Clean Audio engine: takes ANY audio file the phone can decode
 * (MP3, M4A/AAC, WAV, FLAC, OGG, AMR...) and writes a noise-cleaned,
 * loudness-normalised 48 kHz mono WAV.
 *
 * How: MediaExtractor + MediaCodec (pure Android framework — no
 * FFmpeg) decode the file to PCM, samples are downmixed to mono,
 * resampled to 48 kHz, run through the same [NoiseProcessor] +
 * RNNoise pipeline as live recording, and streamed to a temp file
 * (RAM stays flat even for a 2-hour file), then a second pass applies
 * the measured normalisation gain into the final WAV.
 */
object AudioCleaner {

    class CleanException(message: String) : Exception(message)

    private const val TARGET_RATE = 48000
    private const val TIMEOUT_US = 10_000L
    private const val MAX_DURATION_US = 2L * 3600 * 1_000_000 // 2 hours

    fun clean(
        context: Context,
        uri: Uri,
        mode: NoiseMode,
        level: Int,
        windFilter: Boolean,
        keyboardFilter: Boolean,
        noiseOn: Boolean,
        outFile: File,
        onProgress: (Int) -> Unit
    ): File {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw CleanException("File ta khola jacche na")
        }

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = f
                break
            }
        }
        if (trackIndex < 0 || inputFormat == null) {
            extractor.release()
            throw CleanException("Ei file er moddhe audio pawa jay ni")
        }
        extractor.selectTrack(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        var srcRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var srcChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
            inputFormat.getLong(MediaFormat.KEY_DURATION) else -1L
        if (durationUs > MAX_DURATION_US) {
            extractor.release()
            throw CleanException("File onek lomba — max 2 ghonta")
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            extractor.release()
            throw CleanException("Ei format ta decode kora jacche na")
        }

        val tmpRaw = File(context.cacheDir, "clearvoice_clean_tmp.pcm")
        val processor = NoiseProcessor(
            TARGET_RATE, mode, level, noiseOn,
            if (noiseOn) RnNoise.create() else null,
            windFilter, keyboardFilter
        )
        var peak = 0
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            var resampler = LinearResampler(srcRate, TARGET_RATE)
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            BufferedOutputStream(FileOutputStream(tmpRaw)).use { out ->
                val writeBuf = ByteArray(8192 * 2)
                fun emit(samples: ShortArray, count: Int) {
                    if (count <= 0) return
                    processor.process(samples, count)
                    var i = 0
                    var b = 0
                    while (i < count) {
                        val s = samples[i].toInt()
                        val a = abs(s)
                        if (a > peak) peak = a
                        if (b + 2 > writeBuf.size) {
                            out.write(writeBuf, 0, b)
                            b = 0
                        }
                        writeBuf[b] = (s and 0xFF).toByte()
                        writeBuf[b + 1] = (s shr 8 and 0xFF).toByte()
                        b += 2
                        i++
                    }
                    if (b > 0) out.write(writeBuf, 0, b)
                }

                while (!outputEos) {
                    if (!inputEos) {
                        val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf == null) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                            } else {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEos = true
                                } else {
                                    val timeUs = extractor.sampleTime
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, timeUs, 0)
                                    extractor.advance()
                                    if (durationUs > 0) {
                                        val pct = (timeUs * 95 / durationUs).toInt().coerceIn(0, 95)
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIdx >= 0) {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                val bytes = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(bytes)
                                val mono = downmixToMono(bytes, srcChannels)
                                if (mono.isNotEmpty()) {
                                    emit(resampler.process(mono), resampler.lastCount)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        val newRate = if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else srcRate
                        val newCh = if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else srcChannels
                        if (newRate != srcRate) {
                            srcRate = newRate
                            resampler = LinearResampler(srcRate, TARGET_RATE)
                        }
                        srcChannels = newCh
                    }
                }
                // Flush the resampler tail, then finish.
                emit(resampler.flush(), resampler.lastCount)
            }
        } catch (e: CleanException) {
            throw e
        } catch (e: Exception) {
            throw CleanException("Clean korte giye problem: ${e.message}")
        } finally {
            try {
                codec.stop()
            } catch (e: Exception) {
                // Ignore.
            }
            codec.release()
            extractor.release()
            processor.close()
        }

        if (!tmpRaw.exists() || tmpRaw.length() == 0L) {
            throw CleanException("Ei file theke kono sound ber kora jay ni")
        }

        // Pass 2: apply loudness gain into the final WAV.
        val gain = if (peak > 0) minOf(31129f / peak, 8f) else 1f
        val dataBytes = tmpRaw.length()
        BufferedInputStream(FileInputStream(tmpRaw)).use { input ->
            FileOutputStream(outFile).use { out ->
                out.write(WavFile.buildHeader(dataBytes.toInt(), TARGET_RATE, 1, 16))
                val buf = ByteArray(16384)
                if (gain <= 1.05f) {
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                    }
                } else {
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        var i = 0
                        val usable = read - (read % 2)
                        while (i + 1 < usable) {
                            val v = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                            val s = if (v >= 0x8000) v - 0x10000 else v
                            val g = (s * gain).toInt().coerceIn(-32768, 32767)
                            buf[i] = (g and 0xFF).toByte()
                            buf[i + 1] = (g shr 8 and 0xFF).toByte()
                            i += 2
                        }
                        out.write(buf, 0, usable)
                    }
                }
            }
        }
        tmpRaw.delete()
        onProgress(100)
        return outFile
    }

    /** Interleaved PCM16 -> mono (channel average). */
    private fun downmixToMono(bytes: ByteArray, channels: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = bytes.size / (2 * ch)
        val out = ShortArray(frames)
        var i = 0
        var f = 0
        while (f < frames) {
            var sum = 0
            var c = 0
            while (c < ch) {
                val v = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
                sum += if (v >= 0x8000) v - 0x10000 else v
                i += 2
                c++
            }
            out[f] = (sum / ch).toShort()
            f++
        }
        return out
    }

    /**
     * Streaming linear-interpolation resampler with carry-over state,
     * so chunk boundaries stay click-free.
     */
    private class LinearResampler(private val srcRate: Int, dstRate: Int) {
        private val step: Double = if (dstRate == srcRate) 0.0 else srcRate.toDouble() / dstRate.toDouble()
        private var leftover = ShortArray(0)
        private var frac = 0.0
        var lastCount: Int = 0
            private set

        fun process(input: ShortArray): ShortArray {
            if (step == 0.0) {
                lastCount = input.size
                return input
            }
            val combined = ShortArray(leftover.size + input.size)
            System.arraycopy(leftover, 0, combined, 0, leftover.size)
            System.arraycopy(input, 0, combined, leftover.size, input.size)
            val out = ShortArray(((combined.size - frac) / step).toInt() + 2)
            var outCount = 0
            while (frac + 1.0 <= combined.size - 1) {
                val idx = frac.toInt()
                val t = (frac - idx).toFloat()
                val a = combined[idx].toFloat()
                val b = combined[idx + 1].toFloat()
                out[outCount++] = (a + (b - a) * t).toInt().toShort()
                frac += step
            }
            val consumed = frac.toInt()
            leftover = combined.copyOfRange(consumed, combined.size)
            frac -= consumed
            lastCount = outCount
            return out
        }

        fun flush(): ShortArray {
            if (step == 0.0 || leftover.isEmpty()) {
                lastCount = 0
                return ShortArray(0)
            }
            val last = leftover[leftover.size - 1]
            val out = ShortArray(leftover.size)
            var i = 0
            while (i < leftover.size) {
                out[i] = last
                i++
            }
            leftover = ShortArray(0)
            lastCount = out.size
            return out
        }
    }
}
