package io.github.raghavsatyadev.phonebackup.shared.ui

import androidx.compose.runtime.Composable

/** System back (gesture or button) while this is in composition. */
@Composable expect fun BackHandler(onBack: () -> Unit)
