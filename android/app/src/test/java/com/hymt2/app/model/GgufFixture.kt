package com.hymt2.app.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds a tiny but structurally real GGUF v3 file: string/array/scalar metadata, tensors, and a data tail. */
object GgufFixture {
    val DATA_TAIL = ByteArray(64) { (it * 7).toByte() }

    fun build(tensorTypes: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) { u64(s.length.toLong()); out.write(s.toByteArray()) }

        out.write("GGUF".toByteArray()); u32(3); u64(tensorTypes.size.toLong()); u64(4)
        str("general.name"); u32(8); str("Hy-MT2-test")
        str("tokenizer.tokens"); u32(9); u32(8); u64(3); str("a"); str("bb"); str("ccc")
        str("tokenizer.scores"); u32(9); u32(6); u64(3); repeat(3) { u32(0x3f800000) }
        str("general.alignment"); u32(4); u32(32)
        tensorTypes.forEachIndexed { i, t ->
            str("blk.$i.ffn_down.weight"); u32(2); u64(64); u64(32); u32(t); u64(i * 64L)
        }
        out.write(DATA_TAIL)
        return out.toByteArray()
    }
}
