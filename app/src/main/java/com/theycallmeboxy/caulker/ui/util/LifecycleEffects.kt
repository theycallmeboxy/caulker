package com.theycallmeboxy.caulker.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

// Runs `onResume` every time this composable's screen becomes visible again
// -- including returning via popBackStack from a screen pushed on top of it
// (Navigation Compose keeps the destination's own Composition/ViewModel
// alive across that, so a plain LaunchedEffect(Unit) only fires once and
// never again on the way back). Used to refresh state a screen doesn't own
// but that another screen may have changed underneath it -- e.g. platform
// settings' setup warnings and a per-game screen's status after the
// Unassigned-files screen changes an assignment (save-sync design doc, Part
// 2 §12 phase 3B fixes, item 6).
@Composable
fun OnResumeEffect(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResume by rememberUpdatedState(onResume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentOnResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
