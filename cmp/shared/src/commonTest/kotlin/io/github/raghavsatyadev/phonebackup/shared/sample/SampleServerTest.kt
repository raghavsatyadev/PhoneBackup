package io.github.raghavsatyadev.phonebackup.shared.sample

import io.github.raghavsatyadev.phonebackup.shared.data.GalleryClient
import io.github.raghavsatyadev.phonebackup.shared.data.PairClient
import io.github.raghavsatyadev.phonebackup.shared.data.ServerConfig
import io.github.raghavsatyadev.phonebackup.shared.data.UploadClient
import io.github.raghavsatyadev.phonebackup.shared.data.UploadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write

class SampleServerTest {
  private val http = SampleServer().client()
  private val pc = ServerConfig(SamplePc.HOST, SamplePc.TOKEN)

  @Test
  fun pairingIsAllowedOnTheSecondPoll() = runTest {
    val pair = PairClient(http)
    val ticket = pair.request(SamplePc.HOST, "Phone")
    assertEquals(SamplePc.PAIR_CODE, ticket.code)
    assertEquals("pending", pair.status(SamplePc.HOST, ticket.secret).status)
    val allowed = pair.status(SamplePc.HOST, ticket.secret)
    assertEquals("allowed", allowed.status)
    assertEquals(SamplePc.TOKEN, allowed.token)
  }

  @Test
  fun refusesAWrongToken() = runTest {
    assertFailsWith<UploadException> { UploadClient(http).ping(pc.copy(token = "nope")) }
  }

  @Test
  fun uploadShowsUpInTheGalleryAndRetriesAreSkipped() = runTest {
    val file = Path(SystemTemporaryDirectory, "sample-upload-test.jpg")
    val bytes = ByteArray(5000) { (it % 251).toByte() }
    SystemFileSystem.sink(file).buffered().use { it.write(bytes) }
    val uploads = UploadClient(http)
    val at = LocalDateTime(2026, 11, 27, 10, 0)
    var progress = 0L
    val first = uploads.upload(pc, file.toString(), "Me.jpg", bytes.size.toLong(), "Test", listOf("a", "b"), at) { sent, _ ->
      progress = sent
    }
    assertEquals("Me.jpg", first.fileName)
    assertTrue(progress > 0)
    val again = uploads.upload(pc, file.toString(), "Me.jpg", bytes.size.toLong(), "Test", emptyList(), at)
    assertTrue(again.skipped)
    val photo = GalleryClient(http).photos(pc).single { it.id == "Test/Me.jpg" }
    assertEquals(listOf("a", "b"), photo.tags)
    assertEquals(5000L, photo.size)
    assertEquals("Test", uploads.albums(pc).first())
    SystemFileSystem.delete(file)
  }
}
