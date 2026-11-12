package com.voicekiosk.app

import android.os.Handler
import android.os.Looper

/**
 * Thread-safe event bus for dispatching wake word triggers and service state updates
 * between the foreground service and UI components on the main thread.
 */
object WakeWordEventBus {
    interface Listener {
        fun onWakeWordDetected(keyword: String)
        fun onServiceStatusChanged(running: Boolean, message: String)
        fun onAsrDownloadProgress(progressPercent: Int, statusText: String) {}
        fun onAsrPartialResult(text: String) {}
        fun onAsrFinalResult(text: String) {}
        fun onChatReply(replyText: String) {}
        fun onChatOfflineError(errorMessage: String) {}
        fun onStateChanged(state: String) {}
        fun onFollowUpListening(turnIndex: Int) {}
        fun onAudioEnergy(energy: Float) {}
        fun onFocusBoard(boardId: String) {}
        fun onBridgeNotice(text: String) {}
        fun onWaitingForMuse(statusText: String) {}
        /** Native PIN pad for a pending approval (`kind` is approve or reject). */
        fun onRequestPin(requestId: String, kind: String) {}
    }

    private val listeners = mutableListOf<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun register(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    @Synchronized
    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun emitWakeWord(keyword: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onWakeWordDetected(keyword) }
            }
        }
    }

    fun emitStatus(running: Boolean, message: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onServiceStatusChanged(running, message) }
            }
        }
    }

    fun emitAsrDownloadProgress(progressPercent: Int, statusText: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onAsrDownloadProgress(progressPercent, statusText) }
            }
        }
    }

    fun emitAsrPartialResult(text: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onAsrPartialResult(text) }
            }
        }
    }

    fun emitAsrFinalResult(text: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onAsrFinalResult(text) }
            }
        }
    }

    fun emitChatReply(replyText: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onChatReply(replyText) }
            }
        }
    }

    fun emitWaitingForMuse(statusText: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onWaitingForMuse(statusText) }
            }
        }
    }

    fun emitChatOfflineError(errorMessage: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onChatOfflineError(errorMessage) }
            }
        }
    }

    fun emitStateChanged(state: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onStateChanged(state) }
            }
        }
    }

    fun emitFollowUpListening(turnIndex: Int) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onFollowUpListening(turnIndex) }
            }
        }
    }

    fun emitAudioEnergy(energy: Float) {
        val clamped = energy.coerceIn(0f, 1f)
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onAudioEnergy(clamped) }
            }
        }
    }

    fun emitFocusBoard(boardId: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onFocusBoard(boardId) }
            }
        }
    }

    fun emitBridgeNotice(text: String) {
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onBridgeNotice(text) }
            }
        }
    }

    fun emitRequestPin(requestId: String, kind: String = "approve") {
        val id = requestId.trim()
        if (id.isEmpty()) return
        val k = if (kind == "reject") "reject" else "approve"
        mainHandler.post {
            synchronized(this) {
                listeners.forEach { it.onRequestPin(id, k) }
            }
        }
    }
}
