package com.hymt2.app.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class DownloadPhase { DOWNLOADING, VERIFYING }

data class DownloadProgress(val phase: DownloadPhase, val doneBytes: Long, val totalBytes: Long)

/**
 * Downloads a [ModelSpec.remote] file into [dir] as `<first file name>`.
 *
 * Bytes land in `<name>.part` and a later call resumes from it (HTTP Range), so a dropped connection, a cancel or a
 * killed process costs nothing. Sources are tried in order and share the partial file. The checksum is verified before
 * the optional GGUF type rewrite and the final rename, so a file under its real name is always complete and usable.
 */
class ModelDownloader(
    private val sources: List<ModelSource> = ModelSources.default,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 30_000,
) {
    suspend fun download(spec: ModelSpec, dir: File, onProgress: (DownloadProgress) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(run(spec, dir, onProgress))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun run(spec: ModelSpec, dir: File, onProgress: (DownloadProgress) -> Unit): File {
        val remote = requireNotNull(spec.remote) { "${spec.label} has no download source" }
        dir.mkdirs()
        val dest = File(dir, spec.fileNames.first())
        if (dest.isFile && dest.length() > 0) return dest
        val part = File(dir, "${dest.name}.part")

        val missing = remote.sizeBytes - part.length().coerceAtMost(remote.sizeBytes)
        if (dir.usableSpace < missing + SPACE_MARGIN) {
            throw IOException("Not enough storage: need ${missing / MB + SPACE_MARGIN / MB} MB, have ${dir.usableSpace / MB} MB")
        }

        fetchFromAnySource(remote, part, onProgress)

        onProgress(DownloadProgress(DownloadPhase.VERIFYING, 0, remote.sizeBytes))
        val actual = sha256(part) { onProgress(DownloadProgress(DownloadPhase.VERIFYING, it, remote.sizeBytes)) }
        if (!actual.equals(remote.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("Checksum mismatch for ${remote.remoteFile}; the partial file was discarded, try again")
        }
        remote.typeRemap?.let { (from, to) -> GgufTypeRemap.remap(part, from, to) }
        if (!part.renameTo(dest)) throw IOException("Could not move ${part.name} to ${dest.name}")
        return dest
    }

    private suspend fun fetchFromAnySource(remote: RemoteModel, part: File, onProgress: (DownloadProgress) -> Unit) {
        val failures = mutableListOf<String>()
        for (source in sources) {
            try {
                fetch(source.url(remote), remote.sizeBytes, part, onProgress)
                return
            } catch (e: IOException) {
                failures += "${source.name}: ${e.message}"
                if (part.length() > remote.sizeBytes) part.delete()
            }
        }
        throw IOException("Download failed (${failures.joinToString("; ")})")
    }

    private suspend fun fetch(url: String, total: Long, part: File, onProgress: (DownloadProgress) -> Unit) {
        val have = part.length()
        if (have == total) return
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val append = when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false // server ignored Range: start over
                else -> throw IOException("HTTP $code")
            }
            var done = if (append) have else 0L
            var lastReport = 0L
            FileOutputStream(part, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > REPORT_INTERVAL_NS) {
                            lastReport = now
                            onProgress(DownloadProgress(DownloadPhase.DOWNLOADING, done, total))
                        }
                    }
                }
            }
            onProgress(DownloadProgress(DownloadPhase.DOWNLOADING, done, total))
            if (done != total) throw IOException("connection closed at $done of $total bytes")
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun sha256(file: File, onProgress: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastReport = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                done += n
                val now = System.nanoTime()
                if (now - lastReport > REPORT_INTERVAL_NS) { lastReport = now; onProgress(done) }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER = 1 shl 18
        const val MB = 1024L * 1024
        const val SPACE_MARGIN = 64L * MB
        const val REPORT_INTERVAL_NS = 150_000_000L
    }
}
