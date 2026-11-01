package ani.dantotsu.others

import android.os.Handler
import android.os.Looper

/**
 * Ejecuta una acción después de un retraso, reiniciando la cuenta si se vuelve
 * a pedir antes de que termine.
 *
 * Usa el Handler del hilo principal en vez de crear un `Timer` (y su hilo)
 * por cada gesto. Con gestos rápidos eso generaba cientos de hilos.
 */
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
