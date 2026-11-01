package ani.dantotsu.others

import android.os.Handler
import android.os.Looper

class ResettableTimer {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: (() -> Unit)? = null

    private val runnable = Runnable {
        val action = pending
        pending = null
        action?.invoke()
    }

    fun reset(delay: Long, action: () -> Unit) {
        cancel()
        pending = action
        handler.postDelayed(runnable, delay)
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
        pending = null
    }
}
