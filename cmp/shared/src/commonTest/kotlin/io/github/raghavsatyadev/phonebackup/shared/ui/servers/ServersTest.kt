package io.github.raghavsatyadev.phonebackup.shared.ui.servers

import io.github.raghavsatyadev.phonebackup.shared.data.FoundServer
import io.github.raghavsatyadev.phonebackup.shared.data.SavedServer
import io.github.raghavsatyadev.phonebackup.shared.data.ServerList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServersTest {
  @Test
  fun pairingAddsAndActivatesPc() {
    val other = SavedServer(address = "10.0.0.3", token = "x")
    val list = savePaired(ServerList().upsert(other), "10.0.0.2", "Rocky-PC", "pc1", "tok")
    val pc = list.servers.single { it.serverId == "pc1" }
    assertEquals("tok", pc.token)
    assertEquals("Rocky-PC", pc.name)
    assertEquals(pc.localId, list.activeId)
  }

  @Test
  fun pairingUpdatesEntryWithSameAddressAndKeepsItsName() {
    val typed = SavedServer(name = "Office", address = "10.0.0.2")
    val list = savePaired(ServerList().upsert(typed), "10.0.0.2", "Rocky-PC", "pc1", "tok")
    val pc = list.servers.single()
    assertEquals(typed.localId, pc.localId)
    assertEquals("Office", pc.name)
    assertEquals("pc1", pc.serverId)
  }

  @Test
  fun foundPcIsMatchedToSavedEntry() {
    val saved = SavedServer(serverId = "pc1", address = "10.0.0.2", token = "t")
    val net =
      listOf(FoundServer("pc1", "Rocky-PC", listOf("10.0.0.2"), 9877), FoundServer("pc2", "Other", listOf("10.0.0.4"), 9877))
    val state = toState(ServerList().upsert(saved), net, canSearch = true)
    assertEquals(saved.localId, state.found[0].savedId)
    assertTrue(state.found[0].paired)
    assertNull(state.found[1].savedId)
    assertTrue(state.saved.single().active)
  }
}
