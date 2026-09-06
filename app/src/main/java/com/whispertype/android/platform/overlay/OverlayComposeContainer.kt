package com.whispertype.android.platform.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A [FrameLayout] that hosts the overlay's
 * [androidx.compose.ui.platform.ComposeView] as its child and installs the
 * stable Compose owners ([LifecycleOwner], [SavedStateRegistryOwner],
 * [ViewModelStoreOwner]) on itself via the `setViewTree*Owner()` APIs.
 *
 * Compose's `WindowRecomposer` discovers the owners by the tag-based
 * `findViewTree*Owner()` lookup, traversing the view tree upward from the
 * `ComposeView` — it does not discover them by the view implementing the owner
 * interfaces. The tags are set in the constructor (before the window is added,
 * so before the composition starts), fixing the `ViewTreeLifecycleOwner not
 * found` crash (§2.2).
 *
 * The owners themselves are delegated to [owners] (the runtime service), so the
 * overlay observes a real lifecycle rather than a fabricated one.
 */
@SuppressLint("ViewConstructor")
class OverlayComposeContainer @JvmOverloads constructor(
    context: Context,
    private val owners: OverlayOwners,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    init {
        setViewTreeLifecycleOwner(owners)
        setViewTreeSavedStateRegistryOwner(owners)
        setViewTreeViewModelStoreOwner(owners)
    }

    override val lifecycle: Lifecycle get() = owners.lifecycle
    override val savedStateRegistry: SavedStateRegistry get() = owners.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = owners.viewModelStore
}

