package io.github.raghavsatyadev.phonebackup.shared.model

val PhotoItem.needsUpload: Boolean
  get() = status == UploadStatus.Pending || status == UploadStatus.Failed

val PhotoItem.isFinished: Boolean
  get() = status == UploadStatus.Done || status == UploadStatus.Skipped
