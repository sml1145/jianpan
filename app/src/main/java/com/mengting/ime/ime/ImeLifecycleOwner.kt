package com.mengting.ime.ime

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * InputMethodService 不是 LifecycleOwner，
 * ComposeView 需要 ViewTreeLifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner，
 * 缺失会导致键盘弹出时崩溃（其他应用无法拉起键盘的根因）。
 */
class ImeLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    val currentState: Lifecycle.State get() = registry.currentState

    /** 按事件链推进到目标状态，避免非法状态跳变 */
    fun moveTo(target: Lifecycle.State) {
        while (true) {
            val cur = registry.currentState
            if (cur == target || cur == Lifecycle.State.DESTROYED) return
            if (cur.ordinal < target.ordinal) {
                registry.handleLifecycleEvent(
                    when (cur) {
                        Lifecycle.State.INITIALIZED -> Lifecycle.Event.ON_CREATE
                        Lifecycle.State.CREATED -> Lifecycle.Event.ON_START
                        Lifecycle.State.STARTED -> Lifecycle.Event.ON_RESUME
                        else -> return
                    }
                )
            } else {
                registry.handleLifecycleEvent(
                    when (cur) {
                        Lifecycle.State.RESUMED -> Lifecycle.Event.ON_PAUSE
                        Lifecycle.State.STARTED -> Lifecycle.Event.ON_STOP
                        else -> return
                    }
                )
            }
        }
    }

    fun destroy() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            moveTo(Lifecycle.State.CREATED)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
