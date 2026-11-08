package com.humbleplanning.app.data

import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

enum class RealtimeEvent { Connected, TasksChanged, SessionRevoked, PermissionsChanged, Alert, AssistantChanged, InvalidSession }

/**
 * Socket.IO connection of the app in the foreground: the server only says that something
 * changed, the screens then reload. Disconnected in the background (Android would cut it anyway,
 * and an idle socket would show the user as present); push takes over there.
 */
class RealtimeClient(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<RealtimeEvent> = _events

    private var socket: Socket? = null
    private var heartbeat: Job? = null

    /** The cookie goes in the handshake: it is the only credential the Socket.IO server reads. */
    fun connect(session: SessionStore.Session) {
        disconnect()
        val options = IO.Options.builder()
            .setTransports(arrayOf("websocket", "polling"))
            .setReconnection(true)
            .setReconnectionDelay(1_000)
            .setReconnectionDelayMax(10_000)
            .setTimeout(20_000)
            .setExtraHeaders(mapOf("Cookie" to listOf(session.cookie)))
            .build()
        options.callFactory = http
        options.webSocketFactory = http
        val socket = IO.socket(URI.create(session.serverUrl), options)
        socket.on(Socket.EVENT_CONNECT) {
            _events.tryEmit(RealtimeEvent.Connected)
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val message = args.firstOrNull()?.let { (it as? Exception)?.message ?: it.toString() }.orEmpty()
            if (message in REFUSED_SESSION) _events.tryEmit(RealtimeEvent.InvalidSession)
        }
        socket.on("task:changed") { _events.tryEmit(RealtimeEvent.TasksChanged) }
        socket.on("session:revoked") { _events.tryEmit(RealtimeEvent.SessionRevoked) }
        socket.on("permissions:changed") { _events.tryEmit(RealtimeEvent.PermissionsChanged) }
        socket.on("mobile:alert") { _events.tryEmit(RealtimeEvent.Alert) }
        socket.on("ai-chat:availability") { _events.tryEmit(RealtimeEvent.AssistantChanged) }
        socket.connect()
        this.socket = socket
        heartbeat = scope.launch {
            while (isActive) {
                delay(30_000)
                if (socket.connected()) socket.emit("presence:heartbeat")
            }
        }
    }

    /** Connected on a network that changed: the old connection could take a minute to notice. In the background: nothing to do. */
    fun reconnect(session: SessionStore.Session) {
        if (socket != null) connect(session)
    }

    fun disconnect() {
        heartbeat?.cancel()
        heartbeat = null
        socket?.off()
        socket?.disconnect()
        socket = null
    }

    private companion object {
        val REFUSED_SESSION = setOf("Not authenticated", "Invalid session", "Session expired", "User not found")
    }
}
