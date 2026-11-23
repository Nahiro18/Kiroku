package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.widget.Toast
import com.google.gson.Gson

/**
 * Compatibility shims for the small part of the CloudStream *app* module that extensions
 * reference (it is not part of the published CloudStream library).
 *
 * Extensions compiled against CloudStream expect `CloudStreamApp.context` and the
 * `getKey`/`setKey` preference helpers, plus `CommonActivity.showToast`. Kiroku provides
 * them here so those extensions keep working instead of crashing with NoClassDefFoundError.
 */
class CloudStreamApp {
    companion object {
        @Volatile
        var context: Context? = null

        private val gson by lazy { Gson() }

        private fun prefs(): SharedPreferences? =
            context?.getSharedPreferences("cloudstream_extension_settings", Context.MODE_PRIVATE)

        @JvmStatic
        fun <T> getKey(key: String, defaultValue: T): T {
            val stored = prefs()?.getString(key, null) ?: return defaultValue
            return decode(stored, defaultValue) ?: defaultValue
        }

        @JvmStatic
        fun <T> getKey(key: String): T? {
            val stored = prefs()?.getString(key, null) ?: return null
            @Suppress("UNCHECKED_CAST")
            return decode(stored, null as T?) as T?
        }

        @JvmStatic
        fun <T> setKey(key: String, value: T) {
            prefs()?.edit()?.putString(key, encode(value))?.apply()
        }

        @JvmStatic
        fun <T> removeKey(key: String) {
            prefs()?.edit()?.remove(key)?.apply()
        }

        @JvmStatic
        fun removeKeys(prefix: String) {
            val editor = prefs()?.edit() ?: return
            prefs()?.all?.keys?.filter { it.startsWith(prefix) }?.forEach { editor.remove(it) }
            editor.apply()
        }

        @JvmStatic
        fun getKeys(prefix: String): Set<String> =
            prefs()?.all?.keys?.filter { it.startsWith(prefix) }?.toSet() ?: emptySet()

        @JvmStatic
        fun getActivity(): Activity? = CommonActivity.activity

        @JvmStatic
        fun isOnline(): Boolean = context?.let { ctx ->
            runCatching {
                val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
                manager.activeNetworkInfo?.isConnected == true
            }.getOrDefault(true)
        } ?: true

        @Suppress("UNCHECKED_CAST")
        private fun <T> decode(stored: String, defaultValue: T?): T? {
            return try {
                when (defaultValue) {
                    is Boolean -> gson.fromJson(stored, Boolean::class.java) as T
                    is Int -> gson.fromJson(stored, Int::class.java) as T
                    is Long -> gson.fromJson(stored, Long::class.java) as T
                    is Float -> gson.fromJson(stored, Float::class.java) as T
                    is Double -> gson.fromJson(stored, Double::class.java) as T
                    is String -> gson.fromJson(stored, String::class.java) as T
                    is Set<*> -> {
                        val list = gson.fromJson(stored, Array<String>::class.java).toSet()
                        list as T
                    }
                    is List<*> -> gson.fromJson(stored, Array<String>::class.java).toList() as T
                    is Map<*, *> -> gson.fromJson(stored, Map::class.java) as T
                    null -> gson.fromJson(stored, Any::class.java) as T
                    else -> gson.fromJson(stored, defaultValue!!::class.java) as T
                }
            } catch (_: Exception) {
                defaultValue
            }
        }

        private fun encode(value: Any?): String = gson.toJson(value)
    }
}

/**
 * Minimal replacement for CloudStream's UI helper object. Only the helpers extensions
 * commonly use are provided; anything UI-specific simply does nothing.
 */
object CommonActivity {
    @Volatile
    var activity: Activity? = null
        private set

    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }

    val displayMetrics: DisplayMetrics
        get() = android.content.res.Resources.getSystem().displayMetrics

    val screenWidth: Int
        get() = maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels)

    val screenHeight: Int
        get() = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels)

    fun showToast(message: String?) {
        val text = message ?: return
        val context = CloudStreamApp.context ?: activity ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    fun showToast(resourceId: Int) {
        val context = CloudStreamApp.context ?: activity ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, resourceId, Toast.LENGTH_SHORT).show()
        }
    }
}
