package io.github.raghavsatyadev.phonebackup.shared.ui

import androidx.compose.runtime.Composable

/** Desktop has no system back; screens show their own back button. */
@Composable actual fun BackHandler(onBack: () -> Unit) {}
