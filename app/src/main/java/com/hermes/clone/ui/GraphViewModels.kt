package com.hermes.clone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.clone.AppGraph

/**
 * Creates (or finds) a ViewModel that is built from the app's [AppGraph].
 * One ViewModel per screen; it is scoped to the navigation back stack entry that shows the screen.
 */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(
    key: String? = null,
    crossinline build: @DisallowComposableCalls (AppGraph) -> VM,
): VM {
    val appContext = LocalContext.current.applicationContext
    val factory = remember(key) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return build(AppGraph.get(appContext)) as T
            }
        }
    }
    return viewModel(key = key, factory = factory)
}
