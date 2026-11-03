package io.github.raghavsatyadev.phonebackup.shared.data

import io.github.raghavsatyadev.phonebackup.shared.model.DateSource
import kotlinx.datetime.LocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/**
 * Saves a date the user picked next to an imported photo (incoming/<id>/.meta, the same
 * "date|source" line the importer writes), so a restart restores the edited date.
 */
fun saveImportedDate(path: String, takenAt: LocalDateTime) {
  val dir = Path(path).parent ?: return
  runCatching {
    SystemFileSystem.sink(Path(dir, ".meta")).buffered().use {
      it.writeString("$takenAt|${DateSource.Manual.name}")
    }
  }
}

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
