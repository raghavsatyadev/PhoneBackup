package io.github.raghavsatyadev.phonebackup.shared.data

import io.github.raghavsatyadev.phonebackup.shared.model.DateSource
import io.github.raghavsatyadev.phonebackup.shared.model.IncomingPhoto
import io.github.raghavsatyadev.phonebackup.shared.model.UploadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime

class ShareQueueTest {
  private val config = ServerConfig("127.0.0.1:9877", "t")
  private val date = LocalDateTime(2025, 1, 1, 10, 0)

  private fun photo(id: String) = IncomingPhoto(id, "$id.jpg", "/nowhere/$id/$id.jpg", 10, date, DateSource.Exif)

  private fun TestScope.queue(sender: PhotoSender, onAllSent: () -> Unit = {}) =
    ShareQueue(backgroundScope, sender, onAllSent)

  @Test
  fun uploadsEveryWaitingPhotoAndReports() = runTest {
    var cleared = 0
    val q = queue({ _, p, _, _, progress -> progress(1f); p.id == "b" }) { cleared++ }
    q.add(listOf(photo("a"), photo("b")))
    val summary = async { q.summaries.first() }
    assertTrue(q.upload(config, "Trip", listOf("x")))
    assertEquals(UploadSummary(sent = 2, failed = 0, cancelled = false), summary.await())
    assertEquals(listOf(UploadStatus.Done, UploadStatus.Skipped), q.photos.value.map { it.status })
    assertNull(q.uploadProgress.value)
    assertEquals(1, cleared)
  }

  @Test
  fun failureKeepsGoing() = runTest {
    val q = queue({ _, p, _, _, _ -> if (p.id == "a") error("boom") else false })
    q.add(listOf(photo("a"), photo("b")))
    val summary = async { q.summaries.first() }
    q.upload(config, "", emptyList())
    assertEquals(UploadSummary(sent = 1, failed = 1, cancelled = false), summary.await())
    assertEquals("boom", q.photos.value.first().error)
  }

  @Test
  fun cancelPutsCurrentPhotoBackToWaiting() = runTest {
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    val q = queue({ _, _, _, _, _ -> started.complete(Unit); gate.await(); false })
    q.add(listOf(photo("a"), photo("b")))
    val summary = async { q.summaries.first() }
    q.upload(config, "", emptyList())
    started.await()
    assertEquals(UploadStatus.Uploading, q.photos.value.first().status)
    q.cancel()
    assertEquals(UploadSummary(sent = 0, failed = 0, cancelled = true), summary.await())
    assertEquals(listOf(UploadStatus.Pending, UploadStatus.Pending), q.photos.value.map { it.status })
  }

  @Test
  fun copyProgressAddsUp() = runTest {
    val q = queue({ _, _, _, _, _ -> false })
    val seen = mutableListOf<TransferProgress?>()
    q.copying(2) { report ->
      seen += q.progress.first { it != null }
      report(1, "a.jpg")
      seen += q.progress.first { it?.done == 1 }
    }
    assertEquals(TransferProgress(TransferPhase.Copying, 0, 2), seen[0])
    assertEquals(TransferProgress(TransferPhase.Copying, 1, 2, "a.jpg"), seen[1])
    assertNull(q.progress.first { it == null })
  }
}
