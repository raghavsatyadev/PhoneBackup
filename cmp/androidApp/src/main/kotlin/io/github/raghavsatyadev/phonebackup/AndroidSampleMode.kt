package io.github.raghavsatyadev.phonebackup

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import io.github.raghavsatyadev.phonebackup.shared.sample.SampleMode

/**
 * Sample mode on Android: a saved switch read once at start. Turning it on or off restarts the
 * process, so the app is either all sample PC or all real PCs, never a mix. The real PC list is
 * never touched: sample mode keeps its settings in memory and its photos in their own folder.
 */
class AndroidSampleMode(private val context: Context) : SampleMode {
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  override val active: Boolean = prefs.getBoolean(KEY, false)

  override val available = true

  override fun start() = switchTo(true)

  override fun exit() = switchTo(false)

  private fun switchTo(on: Boolean) {
    // Written now, not later: the process ends below.
    prefs.edit(commit = true) { putBoolean(KEY, on) }
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
  }

  companion object {
    private const val PREFS = "sample_mode"
    private const val KEY = "active"

    /** Shared photos waiting in sample mode live here, apart from the real queue. */
    const val INCOMING_FOLDER = "incoming-sample"
  }
}
