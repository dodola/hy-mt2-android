package com.hymt2.app.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GgufTypeRemapTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(bytes: ByteArray): File = tmp.newFile().also { it.writeBytes(bytes) }

    @Test fun `rewrites only the requested type and leaves everything else byte-identical`() {
        val original = GgufFixture.build(listOf(42, 0, 42, 14))
        val file = write(original)

        assertEquals(2, GgufTypeRemap.remap(file, 42, 43))

        val types = GgufTypeRemap.tensorTypeFields(file).map { it.type }
        assertEquals(listOf(43, 0, 43, 14), types)
        val changed = file.readBytes().indices.filter { file.readBytes()[it] != original[it] }
        assertEquals("only the two type fields change", 2, changed.size)
        assertArrayEquals(GgufFixture.DATA_TAIL, file.readBytes().takeLast(GgufFixture.DATA_TAIL.size).toByteArray())
    }

    @Test fun `is idempotent`() {
        val file = write(GgufFixture.build(listOf(42, 42)))
        GgufTypeRemap.remap(file, 42, 43)
        assertEquals(0, GgufTypeRemap.remap(file, 42, 43))
    }

    @Test fun `returns zero when no tensor has the type`() {
        val bytes = GgufFixture.build(listOf(0, 14))
        val file = write(bytes)
        assertEquals(0, GgufTypeRemap.remap(file, 42, 43))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun `rejects files that are not GGUF`() {
        assertThrows(IOException::class.java) { GgufTypeRemap.remap(write("<html>404</html>".toByteArray()), 42, 43) }
    }

    @Test fun `rejects a truncated header`() {
        val bytes = GgufFixture.build(listOf(42))
        assertThrows(IOException::class.java) { GgufTypeRemap.remap(write(bytes.copyOf(60)), 42, 43) }
    }
}
