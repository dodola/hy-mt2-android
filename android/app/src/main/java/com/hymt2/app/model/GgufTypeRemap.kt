package com.hymt2.app.model

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rewrites tensor type ids in a GGUF header in place; the tensor data is not touched. */
object GgufTypeRemap {
    private const val MAX_STRING = 1L shl 30

    /** Returns how many tensors were rewritten (0 when the file already uses [to] or has no [from] tensors). */
    fun remap(file: File, from: Int, to: Int): Int {
        val hits = tensorTypeFields(file).filter { it.type == from }
        if (hits.isEmpty()) return 0
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(to).array()
        RandomAccessFile(file, "rw").use { raf ->
            hits.forEach { raf.seek(it.position); raf.write(bytes) }
        }
        return hits.size
    }

    internal data class TypeField(val position: Long, val type: Int)

    internal fun tensorTypeFields(file: File): List<TypeField> =
        Reader(BufferedInputStream(FileInputStream(file), 1 shl 20)).use { r ->
            val magic = ByteArray(4).also { r.readFully(it) }
            if (!magic.contentEquals("GGUF".toByteArray())) throw IOException("not a GGUF file")
            val version = r.u32()
            if (version !in 2..3) throw IOException("unsupported GGUF version $version")
            val tensors = r.u64()
            val kvs = r.u64()
            repeat(kvs.toInt()) { r.skipString(); skipValue(r, r.u32()) }
            List(tensors.toInt()) {
                r.skipString()
                repeat(r.u32()) { r.u64() }
                val position = r.position
                val type = r.u32()
                r.u64() // data offset
                TypeField(position, type)
            }
        }

    private fun skipValue(r: Reader, type: Int) {
        when (type) {
            0, 1, 7 -> r.skip(1)
            2, 3 -> r.skip(2)
            4, 5, 6 -> r.skip(4)
            10, 11, 12 -> r.skip(8)
            8 -> r.skipString()
            9 -> {
                val elem = r.u32()
                val count = r.u64()
                when (elem) {
                    0, 1, 7 -> r.skip(count)
                    2, 3 -> r.skip(count * 2)
                    4, 5, 6 -> r.skip(count * 4)
                    10, 11, 12 -> r.skip(count * 8)
                    else -> repeat(count.toInt()) { skipValue(r, elem) }
                }
            }
            else -> throw IOException("unknown GGUF value type $type")
        }
    }

    private class Reader(private val input: InputStream) : Closeable by input {
        var position = 0L
            private set
        private val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        fun readFully(dst: ByteArray, count: Int = dst.size) {
            var read = 0
            while (read < count) {
                val n = input.read(dst, read, count - read)
                if (n < 0) throw EOFException("truncated GGUF header")
                read += n
            }
            position += count
        }

        fun u32(): Int { readFully(scratch.array(), 4); return scratch.getInt(0) }
        fun u64(): Long { readFully(scratch.array(), 8); return scratch.getLong(0) }

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s > 0) { left -= s; position += s; continue }
                if (input.read() < 0) throw EOFException("truncated GGUF header")
                left -= 1; position += 1
            }
        }

        fun skipString() {
            val len = u64()
            if (len < 0 || len > MAX_STRING) throw IOException("corrupt GGUF string length $len")
            skip(len)
        }
    }
}
