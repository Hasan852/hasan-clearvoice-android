package com.hasan.clearvoice

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal PCM16 WAV reader/writer (no external dependencies). */
object WavFile {

    data class ParsedWav(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedWav) return false
            return sampleRate == other.sampleRate && channels == other.channels &&
                bitsPerSample == other.bitsPerSample && samples.contentEquals(other.samples)
        }

        override fun hashCode(): Int {
            var result = samples.contentHashCode()
            result = 31 * result + sampleRate
            result = 31 * result + channels
            result = 31 * result + bitsPerSample
            return result
        }
    }

    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataBytes: Long
    ) {
        val durationSeconds: Double
            get() {
                val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
                return if (bytesPerSecond > 0) dataBytes.toDouble() / bytesPerSecond.toDouble() else 0.0
            }
    }

    fun writeWav(file: File, samples: ShortArray, sampleRate: Int, channels: Int) {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcm.putShort(s)
        writeWavBytes(file, pcm.array(), sampleRate, channels, 16)
    }

    fun writeWavBytes(file: File, pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            out.write(buildHeader(pcmData.size, sampleRate, channels, bitsPerSample))
            out.write(pcmData)
        }
    }

    fun appendPcmAsWav(target: File, pcmData: ByteArray, sampleRate: Int, channels: Int) {
        writeWavBytes(target, pcmData, sampleRate, channels, 16)
    }

    fun buildHeader(dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        header.putInt(36 + dataSize)
        header.put(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
        header.put(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
        header.putInt(dataSize)
        return header.array()
    }

    /** Read duration/info from a WAV file header without loading all samples. */
    @Throws(IOException::class)
    fun readInfo(file: File): WavInfo? {
        if (!file.exists() || file.length() < 44) return null
        val bytes = file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            val read = stream.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }
        return try {
            parseHeader(bytes, file.length())
        } catch (e: Exception) {
            null
        }
    }

    private fun parseHeader(bytes: ByteArray, totalFileLength: Long): WavInfo? {
        if (bytes.size < 44) return null
        if (!(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte())) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var sampleRate = 48000
        var channels = 1
        var bits = 16
        var dataBytes = 0L
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4).toLong() and 0xFFFFFFFFL
            when (chunkId) {
                "fmt " -> {
                    channels = buffer.getShort(offset + 10).toInt()
                    sampleRate = buffer.getInt(offset + 12)
                    bits = buffer.getShort(offset + 22).toInt()
                }
                "data" -> {
                    // If we only read the first block, data may extend to end of file.
                    dataBytes = if (offset + 8 + chunkSize <= bytes.size) chunkSize
                    else totalFileLength - (offset + 8)
                    return WavInfo(sampleRate, channels, bits, dataBytes)
                }
            }
            offset += (8 + chunkSize + (chunkSize % 2)).toInt()
        }
        return WavInfo(sampleRate, channels, bits, dataBytes)
    }

    /** Parse a complete WAV file held in memory (used for Import & Clean). */
    @Throws(IOException::class)
    fun readWav(bytes: ByteArray): ParsedWav {
        if (bytes.size < 44) throw IOException("File is too small to be a WAV file")
        if (!(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte())
        ) {
            throw IOException("Not a WAV file (missing RIFF/WAVE header). Please pick a .wav file.")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var sampleRate = 48000
        var channels = 1
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4)
            when (chunkId) {
                "fmt " -> {
                    val audioFormat = buffer.getShort(offset + 8).toInt()
                    if (audioFormat != 1) throw IOException("Only PCM WAV files are supported")
                    channels = buffer.getShort(offset + 10).toInt()
                    sampleRate = buffer.getInt(offset + 12)
                    bits = buffer.getShort(offset + 22).toInt()
                }
                "data" -> {
                    dataOffset = offset + 8
                    dataSize = chunkSize
                }
            }
            if (dataOffset >= 0) break
            offset += 8 + chunkSize + (chunkSize % 2)
        }
        if (dataOffset < 0) throw IOException("WAV file has no audio data")
        if (bits != 16) throw IOException("Only 16-bit WAV files are supported")
        val available = minOf(dataSize, bytes.size - dataOffset)
        val count = available / 2
        val samples = ShortArray(count)
        var i = 0
        while (i < count) {
            samples[i] = buffer.getShort(dataOffset + i * 2)
            i++
        }
        return ParsedWav(samples, sampleRate, channels, bits)
    }
}
