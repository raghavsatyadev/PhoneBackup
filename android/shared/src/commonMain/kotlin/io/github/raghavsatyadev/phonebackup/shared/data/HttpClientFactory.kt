package io.github.raghavsatyadev.phonebackup.shared.data

import io.ktor.client.HttpClient

/** Platform HTTP engine with timeouts suited to large LAN uploads. */
expect fun createHttpClient(): HttpClient
