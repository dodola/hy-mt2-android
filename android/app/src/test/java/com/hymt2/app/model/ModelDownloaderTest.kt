package com.hymt2.app.model

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: TestHttpServer
    private lateinit var dir: File
    private var honorRange = true
    private var payload = ByteArray(0)

    private val base get() = "http://127.0.0.1:${server.port}"
    private val ranges get() = server.ranges

    @Before fun setUp() {
        dir = tmp.newFolder("models")
        server = TestHttpServer({ payload }, { honorRange })
    }

    @After fun tearDown() = server.close()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun spec(bytes: ByteArray, remap: Pair<Int, Int>? = null, sha: String = sha(bytes)) = ModelSpec(
        "t", "Test", listOf("test-model.gguf"), "Q4_0", false,
        RemoteModel("owner/repo", "Model.gguf", bytes.size.toLong(), sha, remap),
    )

    private fun src(name: String, host: String) = ModelSource(name, "$host/{repo}/{file}")

    private fun download(spec: ModelSpec, vararg sources: ModelSource = arrayOf(src("local", base))) =
        runBlocking { ModelDownloader(sources.toList(), 2_000, 2_000).download(spec, dir) }

    @Test fun `downloads, verifies and publishes under the spec file name`() {
        payload = ByteArray(300_000) { (it % 251).toByte() }
        val result = download(spec(payload))
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals("test-model.gguf", result.getOrThrow().name)
        assertArrayEquals(payload, result.getOrThrow().readBytes())
        assertFalse(File(dir, "test-model.gguf.part").exists())
    }

    @Test fun `resumes from a partial file with a Range request`() {
        payload = ByteArray(300_000) { (it % 251).toByte() }
        File(dir, "test-model.gguf.part").writeBytes(payload.copyOf(100_000))
        val result = download(spec(payload))
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals(listOf<String?>("bytes=100000-"), ranges)
        assertArrayEquals(payload, result.getOrThrow().readBytes())
    }

    @Test fun `restarts cleanly when the server ignores Range`() {
        payload = ByteArray(300_000) { (it % 251).toByte() }
        honorRange = false
        File(dir, "test-model.gguf.part").writeBytes(payload.copyOf(100_000))
        val result = download(spec(payload))
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertArrayEquals(payload, result.getOrThrow().readBytes())
    }

    @Test fun `a checksum mismatch fails, discards the partial file and publishes nothing`() {
        payload = ByteArray(10_000) { 1 }
        val result = download(spec(payload, sha = "0".repeat(64)))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Checksum"))
        assertFalse(File(dir, "test-model.gguf").exists())
        assertFalse(File(dir, "test-model.gguf.part").exists())
    }

    @Test fun `falls back to the next source when the first is unreachable`() {
        payload = ByteArray(50_000) { (it % 13).toByte() }
        val result = download(spec(payload), src("dead", "http://127.0.0.1:1"), src("local", base))
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertArrayEquals(payload, result.getOrThrow().readBytes())
    }

    @Test fun `fails with every source named when all are unreachable`() {
        payload = ByteArray(10) 
        val result = download(spec(payload), src("alpha", "http://127.0.0.1:1"), src("beta", "http://127.0.0.1:2"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("beta"))
    }

    @Test fun `applies the GGUF type rewrite after the checksum passes`() {
        payload = GgufFixture.build(listOf(42, 42, 0))
        val result = download(spec(payload, remap = 42 to 43))
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals(listOf(43, 43, 0), GgufTypeRemap.tensorTypeFields(result.getOrThrow()).map { it.type })
    }

    @Test fun `an already installed model is not downloaded again`() {
        payload = ByteArray(1_000) { 5 }
        File(dir, "test-model.gguf").writeBytes(byteArrayOf(1, 2, 3))
        val result = download(spec(payload))
        assertTrue(result.isSuccess)
        assertTrue(ranges.isEmpty())
    }

    @Test fun `a spec without a remote reports a clear error`() {
        val result = runBlocking { ModelDownloader(listOf(src("local", base))).download(KnownModels.all.first { it.remote == null }, dir) }
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no download source"))
    }

    @Test fun `catalog urls use each source's own branch`() {
        val remote = KnownModels.all.first { it.id == "q4_0" }.remote!!
        assertEquals("https://huggingface.co/unsloth/Hy-MT2-1.8B-GGUF/resolve/main/Hy-MT2-1.8B-Q4_0.gguf", ModelSources.HUGGING_FACE.url(remote))
        assertEquals("https://modelscope.cn/models/unsloth/Hy-MT2-1.8B-GGUF/resolve/master/Hy-MT2-1.8B-Q4_0.gguf", ModelSources.MODELSCOPE.url(remote))
        assertEquals("https://hf-mirror.com/unsloth/Hy-MT2-1.8B-GGUF/resolve/main/Hy-MT2-1.8B-Q4_0.gguf", ModelSources.HF_MIRROR.url(remote))
    }

    @Test fun `ModelScope is the default source with the other two as fallbacks`() {
        assertEquals(listOf(ModelSources.MODELSCOPE, ModelSources.HF_MIRROR, ModelSources.HUGGING_FACE), ModelSources.default)
    }
}
