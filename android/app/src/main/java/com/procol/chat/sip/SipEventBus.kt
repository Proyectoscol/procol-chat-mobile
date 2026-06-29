package com.procol.chat.sip

import com.facebook.react.bridge.WritableMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process event bus that decouples SipService (runs in the Android main thread)
 * from SipModule (runs in the React Native thread).
 *
 * Using CopyOnWriteArrayList so that iterating over listeners while adding/removing
 * (e.g. during React Native re-initialization) is safe without locking.
 */
object SipEventBus {

    interface Listener {
        fun onSipEvent(eventName: String, params: WritableMap?)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun emit(eventName: String, params: WritableMap? = null) {
        for (listener in listeners) {
            try {
                listener.onSipEvent(eventName, params)
            } catch (_: Exception) {
                // Individual listener failures must not break event delivery to other listeners
            }
        }
    }
}
