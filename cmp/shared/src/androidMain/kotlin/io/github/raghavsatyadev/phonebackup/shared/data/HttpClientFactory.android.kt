package io.github.raghavsatyadev.phonebackup.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

actual fun createHttpClient(): HttpClient =
  HttpClient(OkHttp) {
    expectSuccess = false
    engine {
      config {
        connectTimeout(5, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(60, TimeUnit.SECONDS)
      }
    }
  }
