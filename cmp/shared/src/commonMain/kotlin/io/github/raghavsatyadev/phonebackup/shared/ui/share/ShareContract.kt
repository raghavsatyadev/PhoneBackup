package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.runtime.Immutable
import io.github.raghavsatyadev.phonebackup.shared.data.TransferProgress
import io.github.raghavsatyadev.phonebackup.shared.model.PhotoItem
import io.github.raghavsatyadev.phonebackup.shared.model.needsUpload
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.StringResource

/** Which photos a date edit applies to. */
sealed interface DateTarget {
  data object All : DateTarget

  data class One(val id: String) : DateTarget
}

@Immutable
data class ShareUiState(
  val photos: ImmutableList<PhotoItem> = persistentListOf(),
  val album: String = "",
  val tagInput: String = "",
  val tags: ImmutableList<String> = persistentListOf(),
  val isUploading: Boolean = false,
  /** 0..1 for the photo being sent right now. */
  val currentProgress: Float = 0f,
  val serverConfigured: Boolean = false,
  val dateTarget: DateTarget? = null,
  /** Shared photos still being copied into the app; null when none are. */
  val copying: TransferProgress? = null,
) {
  val canUpload: Boolean
    get() = !isUploading && serverConfigured && photos.any { it.needsUpload }
}

sealed interface ShareEvent {
  data class AlbumChanged(val value: String) : ShareEvent

  data class TagInputChanged(val value: String) : ShareEvent

  data object AddTag : ShareEvent

  data class RemoveTag(val tag: String) : ShareEvent

  data class RemovePhoto(val id: String) : ShareEvent

  data class EditDate(val target: DateTarget) : ShareEvent

  data object DismissDateEdit : ShareEvent

  data class DateChosen(val value: LocalDateTime) : ShareEvent

  data object Upload : ShareEvent

  data object CancelUpload : ShareEvent

  data object ClearFinished : ShareEvent
}

data class ShareMessage(val text: StringResource, val arg: String = "")
