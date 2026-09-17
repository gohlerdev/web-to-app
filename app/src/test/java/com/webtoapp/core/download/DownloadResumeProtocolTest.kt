package com.webtoapp.core.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Resume contract of [DependencyDownloadEngine] against a real HTTP/1.1 server:
 * partial bytes must only ever continue the artifact that produced them, and a
 * resume the server cannot honour must heal itself instead of wedging a source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadResumeProtocolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpFixture

    private val artifactA = ByteArray(40_000) { (it % 251).toByte() }
    private val artifactB = ByteArray(40_000) { (((it % 251) + 97) % 256).toByte() }
    private val shrunkArtifact = ByteArray(5_000) { (it % 251).toByte() }
    private val lastModified = "Wed, 20 May 2026 17:42:32 GMT"

    @Before
    fun startServer() {
        server = HttpFixture()
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `interrupted transfer resumes the same source and reassembles the artifact`() = runBlocking {
        val dest = destFile()
        seedPartial("/a.bin", dest, artifactA, sendBytes = 20_000, lastModified = lastModified)

        server.hits.clear()
        server.respond = { hit, out -> serveRange(out, artifactA, hit, lastModified) }

        val outcome = DependencyDownloadEngine.downloadFileEx(
            server.url("/a.bin"), dest, "Artifact", null, expectedSha256 = sha256(artifactA)
        )

        assertThat(outcome).isEqualTo(DependencyDownloadEngine.Outcome.SUCCESS)
        assertThat(dest.readBytes()).isEqualTo(artifactA)
        val hit = server.hits.single()
        assertThat(hit.range).isEqualTo("bytes=20000-")
        // The stored validator is replayed, so a re-cut artifact would come back
        // as a 200 full body instead of splicing onto a stale prefix.
        assertThat(hit.ifRange).isEqualTo(lastModified)
        assertThat(resumeMeta(dest).exists()).isFalse()
    }

    @Test
    fun `source ignoring Range restarts clean instead of appending`() = runBlocking {
        val dest = destFile()
        seedPartial("/a.bin", dest, artifactA, sendBytes = 20_000, lastModified = lastModified)

        server.respond = { _, out -> serveFull(out, artifactA, lastModified) }

        val outcome = DependencyDownloadEngine.downloadFileEx(
            server.url("/a.bin"), dest, "Artifact", null
        )

        assertThat(outcome).isEqualTo(DependencyDownloadEngine.Outcome.SUCCESS)
        assertThat(dest.readBytes()).isEqualTo(artifactA)
    }

    @Test
    fun `refused resume drops the partial so the retry starts from zero`() = runBlocking {
        val dest = destFile()
        seedPartial("/a.bin", dest, artifactA, sendBytes = 20_000, lastModified = lastModified)

        server.hits.clear()
        server.respond = { hit, out ->
            // The artifact shrank: the stored offset is past its end forever.
            if (hit.range != null) serveUnsatisfiable(out, shrunkArtifact.size)
            else serveFull(out, shrunkArtifact, lastModified)
        }

        val ok = DependencyDownloadEngine.downloadFileWithFallback(
            listOf(server.url("/a.bin")), dest, "Artifact", null,
            maxRetryPerUrl = 2, retryDelayMs = 0,
            expectedSha256For = { sha256(shrunkArtifact) },
        )

        assertThat(ok).isTrue()
        assertThat(server.hits.map { it.range }).containsExactly("bytes=20000-", null).inOrder()
        assertThat(dest.readBytes()).isEqualTo(shrunkArtifact)
        assertThat(tempFile(dest).exists()).isFalse()
        assertThat(resumeMeta(dest).exists()).isFalse()
    }

    @Test
    fun `partial bytes of another artifact are discarded instead of spliced`() = runBlocking {
        val dest = destFile()
        seedPartial("/a.bin", dest, artifactA, sendBytes = 20_000, lastModified = lastModified)

        server.hits.clear()
        server.respond = { hit, out -> serveRange(out, artifactB, hit, lastModified) }

        val outcome = DependencyDownloadEngine.downloadFileEx(
            server.url("/b.bin"), dest, "Other artifact", null
        )

        assertThat(outcome).isEqualTo(DependencyDownloadEngine.Outcome.SUCCESS)
        assertThat(server.hits.single().range).isNull()
        assertThat(dest.readBytes()).isEqualTo(artifactB)
    }

    @Test
    fun `mirror serving the same pinned artifact resumes from the stalled offset`() = runBlocking {
        val dest = destFile()
        val pin = sha256(artifactA)
        seedPartial("/mirror-a.bin", dest, artifactA, sendBytes = 20_000, lastModified = lastModified, pin = pin)

        server.hits.clear()
        server.respond = { hit, out -> serveRange(out, artifactA, hit, lastModified = null) }

        val outcome = DependencyDownloadEngine.downloadFileEx(
            server.url("/mirror-b.bin"), dest, "Artifact", null, expectedSha256 = pin
        )

        assertThat(outcome).isEqualTo(DependencyDownloadEngine.Outcome.SUCCESS)
        assertThat(dest.readBytes()).isEqualTo(artifactA)
        val hit = server.hits.single()
        assertThat(hit.range).isEqualTo("bytes=20000-")
        // The validator belongs to the other origin, so it is not replayed here.
        assertThat(hit.ifRange).isNull()
    }

    @Test
    fun `206 granted from a foreign offset is rejected instead of appended`() = runBlocking {
        val dest = destFile()
        seedPartial("/a.bin", dest, artifactA, sendBytes = 20_000, lastModified = lastModified)

        server.respond = { _, out ->
            servePartial(out, artifactA, start = 0, lastModified = lastModified)
        }

        val outcome = DependencyDownloadEngine.downloadFileEx(
            server.url("/a.bin"), dest, "Artifact", null
        )

        assertThat(outcome).isEqualTo(DependencyDownloadEngine.Outcome.FAILED)
        assertThat(dest.exists()).isFalse()
        assertThat(tempFile(dest).exists()).isFalse()
    }

    @Test
    fun `unpinned source without a validator never resumes across a changed artifact`() = runBlocking {
        val dest = destFile()
        seedPartial("/latest.bin", dest, artifactA, sendBytes = 20_000, lastModified = null)

        server.hits.clear()
        // The moving target now serves different content under the same URL.
        server.respond = { hit, out -> serveRange(out, artifactB, hit, lastModified = null) }

        val outcome = DependencyDownloadEngine.downloadFileEx(
            server.url("/latest.bin"), dest, "Latest", null
        )

        assertThat(outcome).isEqualTo(DependencyDownloadEngine.Outcome.SUCCESS)
        assertThat(server.hits.single().range).isNull()
        assertThat(dest.readBytes()).isEqualTo(artifactB)
    }

    private fun destFile(): File = File(tmp.newFolder(), "pkg.bin")

    private fun tempFile(dest: File) = File(dest.parentFile, "${dest.name}.tmp")

    private fun resumeMeta(dest: File) = File(dest.parentFile, "${dest.name}.tmp.src")

    /**
     * Leaves a genuine partial download behind: the server announces the full
     * length, writes [sendBytes] and drops the connection, which is how the
     * engine ends up holding resumable bytes plus their provenance.
     */
    private suspend fun seedPartial(
        path: String,
        dest: File,
        body: ByteArray,
        sendBytes: Int,
        lastModified: String?,
        pin: String? = null,
    ) {
        server.respond = { _, out -> serveFull(out, body, lastModified, sendBytes) }
        val outcome = DependencyDownloadEngine.downloadFileEx(
            server.url(path), dest, "Seed", null, expectedSha256 = pin
        )
        assertThat(outcome).isEqualTo(DependencyDownloadEngine.Outcome.FAILED)
        assertThat(tempFile(dest).length()).isEqualTo(sendBytes.toLong())
    }
}
