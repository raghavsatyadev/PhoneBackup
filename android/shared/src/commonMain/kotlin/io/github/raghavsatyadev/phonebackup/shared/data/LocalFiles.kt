package io.github.raghavsatyadev.phonebackup.shared.data

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Deletes an imported photo and its per-photo folder (incoming/<id>/). */
fun deleteImported(path: String) {
  val file = Path(path)
  runCatching { SystemFileSystem.delete(file, mustExist = false) }
  val dir = file.parent ?: return
  runCatching {
    SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
    SystemFileSystem.delete(dir, mustExist = false)
  }
}
