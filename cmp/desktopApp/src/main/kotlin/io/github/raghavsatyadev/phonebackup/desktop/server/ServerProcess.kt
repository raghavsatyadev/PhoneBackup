package io.github.raghavsatyadev.phonebackup.desktop.server

import io.github.raghavsatyadev.phonebackup.desktop.configDir
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Runs the PhoneBackup server (`phonebackup.exe server`) as a hidden child of this app, so the
 * app is the one thing the user starts. A server that is already running (started by hand) is
 * used as it is and left running on quit. The child gets `-exit-with-parent` and keeps our end of
 * its stdin, so it also ends if this app crashes.
 */
class ServerProcess {
  private var process: Process? = null

  /** Where the server's console output goes (its banner, errors). */
  val logFile: File = File(dataDir(), "server.log")

  /** Starts the server unless one already answers; true once it answers. */
  fun start(): Boolean {
    if (isUp()) return true
    val exe = findServer() ?: return false.also { System.err.println("phonebackup.exe not found") }
    logFile.parentFile.mkdirs()
    val command = listOf(exe.path, "server", "-exit-with-parent") + extraArgs()
    process =
      ProcessBuilder(command)
        .directory(exe.parentFile)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        .start()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_S)
    while (System.nanoTime() < deadline) {
      if (process?.isAlive != true) return false
      if (isUp()) return true
      Thread.sleep(POLL_MS)
    }
    return isUp()
  }

  /** Stops the server this app started; one started by hand keeps running. */
  fun stop() {
    val p = process ?: return
    process = null
    runCatching { p.outputStream.close() }
    if (!p.waitFor(STOP_TIMEOUT_S, TimeUnit.SECONDS)) p.destroyForcibly()
  }

  private fun isUp(): Boolean =
    try {
      val c = URI("http://127.0.0.1:$DASHBOARD_PORT/api/pair/pending").toURL().openConnection() as HttpURLConnection
      c.connectTimeout = 500
      c.readTimeout = 1000
      c.responseCode == 200
    } catch (_: Exception) {
      false
    }

  private companion object {
    const val DASHBOARD_PORT = 9876
    const val START_TIMEOUT_S = 20L
    const val STOP_TIMEOUT_S = 5L
    const val POLL_MS = 250L
  }
}

/** This app's folder in the user config folder, shared with the server. */
fun dataDir(): File = File(configDir() ?: File(System.getProperty("user.home")), "phonebackup")

/**
 * The server: next to the app's resources once installed, else `server/phonebackup.exe` in the
 * repository (running from Gradle).
 */
private fun findServer(): File? {
  val name = if (System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")) "phonebackup.exe" else "phonebackup"
  System.getProperty("compose.application.resources.dir")?.let { dir ->
    File(dir, "server/$name").takeIf { it.isFile }?.let { return it }
  }
  var dir: File? = File(System.getProperty("user.dir")).absoluteFile
  while (dir != null) {
    File(dir, "server/$name").takeIf { it.isFile }?.let { return it }
    dir = dir.parentFile
  }
  return null
}

/** Extra server flags (for example `-vision-model qwen3-vl:8b`), one file in the data folder. */
private fun extraArgs(): List<String> =
  runCatching { File(dataDir(), "server_args").readText() }.getOrNull()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
