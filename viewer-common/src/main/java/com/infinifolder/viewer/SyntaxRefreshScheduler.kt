package com.infinifolder.viewer

/** Layout/scroll callbacks must not indefinitely postpone highlighting while the view is active. */
internal class SyntaxRefreshScheduler(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val refresh: () -> Unit
) {
    private var pending = false
    private val callback = Runnable {
        pending = false
        refresh()
    }
    fun request(immediate: Boolean = false) {
        if (pending && !immediate) return
        if (pending) remove(callback)
        pending = true
        post(callback, if (immediate) 0L else 160L)
    }
    fun cancel() {
        remove(callback)
        pending = false
    }
}
