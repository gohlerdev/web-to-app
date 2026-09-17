package com.webtoapp.core.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pause / cancel are addressed by task id, so they must only ever reach the
 * download that owns that id. The transfer is held open mid-body while the
 * control action is issued, which is the only way to observe which run a flag
 * actually lands on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadTaskControlTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpFixture

    private val artifact = ByteArray(40_000) { (it % 251).toByte() }

    @Before
    fun startServer() {
        server = HttpFixture()
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `cancelling another task leaves the running download intact`() = runBlocking {
        val dest = destFile()
        val midBody = CountDownLatch(1)
        val release = CountDownLatch(1)
        serveHalfThenWait(midBody, release)

        val run = CoroutineScope(Dispatchers.IO).async {
            DependencyDownloadEngine.downloadFileEx(
                server.url("/a.bin"), dest, "Artifact", null, taskId = "running-task"
            )
        }

        assertThat(midBody.await(20, TimeUnit.SECONDS)).isTrue()
        DependencyDownloadEngine.cancel("some-other-task")
        release.countDown()

        assertThat(run.await()).isEqualTo(DependencyDownloadEngine.Outcome.SUCCESS)
        assertThat(dest.readBytes()).isEqualTo(artifact)
    }

    @Test
    fun `cancelling the running task aborts it and keeps the partial for resume`() = runBlocking {
        val dest = destFile()
        val midBody = CountDownLatch(1)
        val release = CountDownLatch(1)
        serveHalfThenWait(midBody, release)

        val run = CoroutineScope(Dispatchers.IO).async {
            DependencyDownloadEngine.downloadFileEx(
                server.url("/a.bin"), dest, "Artifact", null, taskId = "running-task"
            )
        }

        assertThat(midBody.await(20, TimeUnit.SECONDS)).isTrue()
        DependencyDownloadEngine.cancel("running-task")
        release.countDown()

        val thrown = runCatching { run.await() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(dest.exists()).isFalse()
        // A cancelled transfer stays resumable: however far it got, the partial
        // file and its provenance survive rather than being dropped.
        assertThat(tempFile(dest).exists()).isTrue()
        assertThat(resumeMeta(dest).exists()).isTrue()
    }

    /**
     * Announces the full artifact, delivers the first half, then blocks until
     * [release] so a control action can be issued while the engine is still
     * reading this response.
     */
    private fun serveHalfThenWait(midBody: CountDownLatch, release: CountDownLatch) {
        val half = artifact.size / 2
        server.respond = { _, out ->
            writeHead(
                out,
                "200 OK",
                listOf("Content-Length: ${artifact.size}", "Accept-Ranges: bytes"),
            )
            out.write(artifact, 0, half)
            out.flush()
            midBody.countDown()
            release.await(20, TimeUnit.SECONDS)
            out.write(artifact, half, artifact.size - half)
            out.flush()
        }
    }

    private fun destFile(): File = File(tmp.newFolder(), "pkg.bin")

    private fun tempFile(dest: File) = File(dest.parentFile, "${dest.name}.tmp")

    private fun resumeMeta(dest: File) = File(dest.parentFile, "${dest.name}.tmp.src")
}
