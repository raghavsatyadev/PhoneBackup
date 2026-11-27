package io.github.raghavsatyadev.phonebackup.shared.sample

import io.github.raghavsatyadev.phonebackup.shared.appModule
import io.github.raghavsatyadev.phonebackup.shared.data.FoundServer
import io.github.raghavsatyadev.phonebackup.shared.data.GalleryPhoto
import io.github.raghavsatyadev.phonebackup.shared.data.KeyValueStore
import io.github.raghavsatyadev.phonebackup.shared.data.SavedServer
import io.github.raghavsatyadev.phonebackup.shared.data.ServerDiscovery
import io.github.raghavsatyadev.phonebackup.shared.data.ServerList
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import org.koin.core.module.Module

/** The made-up PC of sample mode. Its address never reaches the network: the fake engine answers. */
object SamplePc {
  const val ID = "sample-pc"
  const val NAME = "Sample PC"
  const val HOST = "192.168.1.20"
  const val TOKEN = "sample-token"
  const val PAIR_CODE = "428913"

  /** Shows the sample PC under "On this Wi-Fi", so pairing can be tried too. */
  val discovery = ServerDiscovery { flowOf(listOf(FoundServer(ID, NAME, listOf(HOST), 9877))) }

  /** Settings that live only in memory, starting paired with the sample PC. */
  fun settings(): SettingsStore {
    val store = SettingsStore(MemoryKeyValueStore())
    store.update { ServerList().upsert(SavedServer(serverId = ID, name = NAME, address = HOST, token = TOKEN)) }
    return store
  }
}

/** Nothing is written to disk, so leaving sample mode leaves the real PCs as they were. */
class MemoryKeyValueStore : KeyValueStore {
  private val values = MutableStateFlow<Map<String, String>>(emptyMap())

  override fun get(key: String): String? = values.value[key]

  override fun put(values: Map<String, String>) {
    this.values.update { it + values }
  }
}

/** One sample photo: the bundled picture in composeResources/files/sample plus what the PC knows. */
internal data class SamplePhoto(val file: String, val photo: GalleryPhoto)

private fun sample(
  file: String,
  album: String,
  takenAt: String,
  category: String,
  event: String,
  caption: String,
  aiTags: List<String>,
  tags: List<String> = emptyList(),
  groupId: String = "",
  groupSize: Int = 1,
  isBest: Boolean = true,
) =
  SamplePhoto(
    file,
    GalleryPhoto(
      id = "$album/$file",
      album = album,
      fileName = file,
      takenAt = takenAt,
      uploadedAt = takenAt,
      size = 2_400_000,
      tags = tags,
      aiTags = aiTags,
      caption = caption,
      category = category,
      event = event,
      groupId = groupId,
      groupSize = groupSize,
      isBest = isBest,
    ),
  )

/** Pictures drawn for sample mode (no real people or places); categories match server/categories.go. */
internal val samplePhotos: List<SamplePhoto> =
  listOf(
    sample("diwali_diyas_1.jpg", "Diwali 2026", "2026-11-08T19:42:00+05:30", "Festival", "Diwali",
      "A row of lit diyas on a dark evening.", listOf("diya", "lights", "night"), listOf("family"),
      groupId = "g-diya", groupSize = 2),
    sample("diwali_diyas_2.jpg", "Diwali 2026", "2026-11-08T19:42:30+05:30", "Festival", "Diwali",
      "Diyas glowing in a line.", listOf("diya", "lights"), groupId = "g-diya", groupSize = 2, isBest = false),
    sample("rangoli.jpg", "Diwali 2026", "2026-11-08T11:15:00+05:30", "Festival", "Diwali",
      "A colourful round rangoli at the door.", listOf("rangoli", "colours", "pattern")),
    sample("birthday_cake_1.jpg", "Birthdays", "2026-09-14T18:05:00+05:30", "Celebration", "Birthday",
      "A chocolate cake with five candles.", listOf("cake", "candles", "party"),
      groupId = "g-cake", groupSize = 2),
    sample("birthday_cake_2.jpg", "Birthdays", "2026-09-14T18:05:20+05:30", "Celebration", "Birthday",
      "Birthday cake on the table.", listOf("cake", "candles"), groupId = "g-cake", groupSize = 2, isBest = false),
    sample("goa_beach_1.jpg", "Goa Trip", "2026-05-02T17:30:00+05:30", "Trip", "Goa",
      "Sunset over the sea with a palm tree.", listOf("beach", "sunset", "palm"), listOf("vacation"),
      groupId = "g-beach", groupSize = 3),
    sample("goa_beach_2.jpg", "Goa Trip", "2026-05-02T17:30:40+05:30", "Trip", "Goa",
      "Beach at sunset.", listOf("beach", "sunset"), groupId = "g-beach", groupSize = 3, isBest = false),
    sample("goa_beach_3.jpg", "Goa Trip", "2026-05-02T17:31:10+05:30", "Trip", "Goa",
      "Palm tree on the sand at dusk.", listOf("beach", "palm"), groupId = "g-beach", groupSize = 3, isBest = false),
    sample("hills.jpg", "Goa Trip", "2026-05-04T09:10:00+05:30", "Trip", "Goa",
      "Layers of green hills under a blue sky.", listOf("hills", "landscape")),
    sample("city_dusk.jpg", "Goa Trip", "2026-05-05T20:00:00+05:30", "Trip", "Goa",
      "City buildings with lit windows at dusk.", listOf("city", "night", "buildings")),
    sample("garden.jpg", "Home", "2026-03-21T08:45:00+05:30", "Everyday", "",
      "Flowers in the garden in the morning.", listOf("flowers", "garden")),
    sample("receipt.jpg", "Home", "2026-03-22T13:00:00+05:30", "Documents", "",
      "A shop receipt.", listOf("receipt", "bill"), listOf("expenses")),
  )

/** The app wired to the sample PC: in-memory settings, a fake "found" PC and [SampleServer]. */
fun sampleAppModule(sample: SampleMode, deviceName: String): Module {
  val server = SampleServer()
  return appModule(
    settings = SamplePc.settings(),
    discovery = SamplePc.discovery,
    deviceName = deviceName,
    sample = sample,
    httpClient = server::client,
  )
}
