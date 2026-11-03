package io.github.raghavsatyadev.phonebackup.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.raghavsatyadev.phonebackup.shared.data.FoundServer
import io.github.raghavsatyadev.phonebackup.shared.data.ServerDiscovery
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds PCs announcing `_phonebackup._tcp` (server/announce.go) with Android's [NsdManager],
 * which handles multicast itself. Searches only once the local network question was answered,
 * and not at all when it was refused.
 */
class NsdDiscovery(private val nsd: NsdManager, private val access: LocalNetworkAccess) : ServerDiscovery {
  // Before Android 14 the manager resolves one service at a time.
  private val resolving = Mutex()

  override fun found(): Flow<List<FoundServer>> = flow {
    access.awaitAnswer()
    if (access.granted()) emitAll(search())
  }

  private fun search(): Flow<List<FoundServer>> = callbackFlow {
    val found = ConcurrentHashMap<String, FoundServer>()
    fun send() = trySend(found.values.sortedBy { it.name })
    val listener =
      object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
          launch {
            found[info.serviceName] = resolve(info) ?: return@launch
            send()
          }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
          if (found.remove(info.serviceName) != null) send()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit
      }
    send()
    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
  }

  private suspend fun resolve(info: NsdServiceInfo): FoundServer? =
    withTimeoutOrNull(RESOLVE_TIMEOUT) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolveWithCallback(info)
      else resolving.withLock { resolveLegacy(info) }
    }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private suspend fun resolveWithCallback(info: NsdServiceInfo): FoundServer? {
    val result = CompletableDeferred<FoundServer?>()
    val callback =
      object : NsdManager.ServiceInfoCallback {
        override fun onServiceUpdated(resolved: NsdServiceInfo) {
          toServer(resolved, resolved.hostAddresses)?.let(result::complete)
        }

        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
          result.complete(null)
        }

        override fun onServiceLost() = Unit

        override fun onServiceInfoCallbackUnregistered() = Unit
      }
    nsd.registerServiceInfoCallback(info, EXECUTOR, callback)
    return try {
      result.await()
    } finally {
      runCatching { nsd.unregisterServiceInfoCallback(callback) }
    }
  }

  @Suppress("DEPRECATION")
  private suspend fun resolveLegacy(info: NsdServiceInfo): FoundServer? =
    suspendCancellableCoroutine { continuation ->
      nsd.resolveService(
        info,
        object : NsdManager.ResolveListener {
          override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            if (continuation.isActive) continuation.resume(null)
          }

          override fun onServiceResolved(resolved: NsdServiceInfo) {
            val server = toServer(resolved, listOfNotNull(resolved.host))
            if (continuation.isActive) continuation.resume(server)
          }
        },
      )
    }

  private companion object {
    const val SERVICE_TYPE = "_phonebackup._tcp"
    val RESOLVE_TIMEOUT = 5.seconds
    val EXECUTOR = Executors.newSingleThreadExecutor()

    /** Needs the PC's `id` TXT entry and an IPv4 address (the upload API listens on those). */
    fun toServer(resolved: NsdServiceInfo, addresses: List<InetAddress>): FoundServer? {
      val txt = resolved.attributes
      val id = txt["id"]?.decodeToString()?.takeIf { it.isNotBlank() } ?: return null
      val hosts = addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct()
      if (hosts.isEmpty() || resolved.port <= 0) return null
      val name = txt["name"]?.decodeToString()?.takeIf { it.isNotBlank() } ?: resolved.serviceName
      return FoundServer(serverId = id, name = name, hosts = hosts, port = resolved.port)
    }
  }
}
