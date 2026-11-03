package io.github.raghavsatyadev.phonebackup.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerListTest {
  private class MemoryStore(val values: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
    override fun get(key: String) = values[key]

    override fun put(values: Map<String, String>) {
      this.values.putAll(values)
    }
  }

  @Test
  fun firstServerBecomesActive() {
    val a = SavedServer(address = "10.0.0.2", token = "t")
    val list = ServerList().upsert(a).upsert(SavedServer(address = "10.0.0.3"))
    assertEquals(2, list.servers.size)
    assertEquals(a.localId, list.activeId)
  }

  @Test
  fun samePcIdMergesIntoOneEntry() {
    val a = SavedServer(serverId = "pc1", address = "10.0.0.2")
    val list = ServerList().upsert(a).upsert(SavedServer(serverId = "pc1", address = "10.0.0.9", token = "t"))
    assertEquals(1, list.servers.size)
    assertEquals(a.localId, list.servers.single().localId)
    assertEquals("10.0.0.9", list.servers.single().address)
  }

  @Test
  fun editingIntoAnotherEntryKeepsActiveValid() {
    val a = SavedServer(serverId = "pc1", address = "10.0.0.2", token = "t")
    val b = SavedServer(address = "10.0.0.3")
    var list = ServerList().upsert(a).upsert(b).activate(a.localId)
    // b turns out to be the same PC as a: a is dropped, b stays, and becomes active.
    list = list.upsert(b.copy(serverId = "pc1", token = "t2"))
    assertEquals(listOf(b.localId), list.servers.map { it.localId })
    assertEquals(b.localId, list.activeId)
  }

  @Test
  fun removeMovesActiveToNext() {
    val a = SavedServer(address = "a")
    val b = SavedServer(address = "b")
    val list = ServerList().upsert(a).upsert(b).remove(a.localId)
    assertEquals(b.localId, list.activeId)
    assertNull(list.remove(b.localId).activeId)
  }

  @Test
  fun seenAtMovesOnlyStaleAddresses() {
    val a = SavedServer(serverId = "pc1", address = "10.0.0.2")
    val list = ServerList().upsert(a)
    assertEquals(list, list.seenAt("pc1", listOf("10.0.0.5", "10.0.0.2"), 9877))
    assertEquals("10.0.0.5", list.seenAt("pc1", listOf("10.0.0.5"), 9877).servers.single().address)
    assertEquals("10.0.0.5:9999", list.seenAt("pc1", listOf("10.0.0.5"), 9999).servers.single().address)
  }

  @Test
  fun oldSingleAddressIsMigrated() {
    val kv = MemoryStore(mutableMapOf("address" to " 10.0.0.2 ", "token" to "t"))
    val store = SettingsStore(kv)
    assertEquals(ServerConfig("10.0.0.2", "t"), store.config.value)
    assertEquals(1, store.servers.value.servers.size)
    // Saved as a list, so the next start reads the same entry.
    assertEquals(store.servers.value, SettingsStore(kv).servers.value)
  }
}
