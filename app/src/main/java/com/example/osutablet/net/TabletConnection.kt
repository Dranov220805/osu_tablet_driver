package com.example.osutablet.net

import android.util.Log
import com.example.osutablet.input.PointerPhase
import com.example.osutablet.input.PointerSample
import com.example.osutablet.input.PointerSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Observable state of the link to the PC server. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val hostname: String, val protocolVersion: Int) : ConnectionState
    data class Disconnected(val reason: String) : ConnectionState
}

/**
 * Owns the socket to the PC server and the single writer that serializes
 * pointer samples onto it.
 *
 * Ordering is the whole point of this class. Samples are queued on an unbounded
 * channel and drained by exactly one coroutine, so a MOVE can never overtake
 * the UP that follows it. Under backlog the writer coalesces runs of MOVE
 * samples instead of dropping arbitrary events, which degrades precision
 * slightly rather than desynchronizing the button state on the PC.
 */
class TabletConnection(
    private val host: String = Protocol.HOST,
    private val port: Int = Protocol.PORT,
) : PointerSink {

    // The connection owns its scope rather than borrowing the Activity's.
    // lifecycleScope is already cancelled by the time onDestroy runs, so a
    // teardown posted to it would silently never execute.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val outbox = Channel<Message>(Channel.UNLIMITED)
    private val sessionLock = Mutex()
    private var sessionJob: Job? = null

    /** Read from other threads to break a blocked read; must be volatile. */
    @Volatile
    private var activeSocket: Socket? = null

    /** Queued unit of work for the writer. */
    private sealed interface Message {
        data class Sample(val sample: PointerSample) : Message
        data object Cancel : Message
    }

    // --- PointerSink ------------------------------------------------------

    override fun onSample(sample: PointerSample) {
        // trySend on an unbounded channel never fails and never blocks, so this
        // is safe to call from the UI thread on every touch sample.
        outbox.trySend(Message.Sample(sample))
    }

    override fun onCancel() {
        outbox.trySend(Message.Cancel)
    }

    // --- Lifecycle --------------------------------------------------------

    /**
     * Connects, replacing any existing session. Safe to call repeatedly; the
     * lock keeps overlapping calls from leaving two sessions on the socket.
     */
    /**
     * Connects if not already connected.
     *
     * Idempotent by default: lifecycle callbacks legitimately fire more than
     * once (onCreate then onResume), and tearing a healthy session down to
     * rebuild it drops the socket the server just accepted. Pass [force] for
     * an explicit user-initiated retry.
     */
    fun connect(force: Boolean = false) {
        if (!scope.isActive) return
        if (!force && sessionJob?.isActive == true) return
        scope.launch {
            sessionLock.withLock {
                // Re-check under the lock: two callers can pass the check above
                // concurrently, and the loser would kill the winner's session.
                if (!force && sessionJob?.isActive == true) return@withLock
                // Closing the socket first is what actually unblocks the old
                // session's readLine. Blocking stream reads do not observe
                // coroutine cancellation, so cancelAndJoin alone would hang.
                closeActiveSocket()
                sessionJob?.cancelAndJoin()
                sessionJob = scope.launch { reconnectLoop() }
            }
        }
    }

    /**
     * Stops the session and any pending reconnect, without tearing the
     * connection down permanently. Used when the app leaves the foreground so
     * a backgrounded app is not retrying a USB link nobody is watching.
     */
    fun pause() {
        if (!scope.isActive) return
        scope.launch {
            sessionLock.withLock {
                closeActiveSocket()
                sessionJob?.cancelAndJoin()
                sessionJob = null
                _state.value = ConnectionState.Idle
            }
        }
    }

    /**
     * Retries until cancelled, backing off on consecutive failures.
     *
     * Unplugging the cable is the common case, and it should heal on its own
     * once the cable is back rather than requiring the user to find a button.
     */
    private suspend fun reconnectLoop() {
        var delayMs = INITIAL_RECONNECT_DELAY_MS
        while (coroutineContext.isActive) {
            val established = runSession()
            if (!coroutineContext.isActive) return
            // A session that got as far as connecting means the server is
            // reachable, so start the next backoff from the bottom again.
            delayMs = if (established) {
                INITIAL_RECONNECT_DELAY_MS
            } else {
                (delayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
            delay(delayMs)
        }
    }

    /** Permanently tears the connection down. The instance is not reusable. */
    fun close() {
        closeActiveSocket()
        scope.cancel()
        _state.value = ConnectionState.Idle
    }

    private fun closeActiveSocket() {
        activeSocket?.let { runCatching { it.close() } }
        activeSocket = null
    }

    /**
     * Runs one session. Returns true only once the handshake completed.
     *
     * "The socket opened" is not good enough: while `adb reverse` is mapped but
     * the PC server is dead, adb accepts the connection and closes it
     * immediately. Treating that as success reset the backoff on every attempt,
     * so the app hammered away and sat in "Connecting" instead of settling into
     * the disconnected state that offers the retry button.
     */
    private suspend fun runSession(): Boolean {
        var handshakeCompleted = false
        _state.value = ConnectionState.Connecting

        val socket = try {
            withContext(Dispatchers.IO) { openSocket() }
        } catch (e: IOException) {
            Log.w(TAG, "Connect failed: ${e.message}")
            _state.value = ConnectionState.Disconnected(describe(e))
            return false
        }
        activeSocket = socket

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII))

            val greeting = withContext(Dispatchers.IO) { reader.readLine() }
                ?: throw IOException("Server closed before greeting")
            val session = parseGreeting(greeting)

            if (session.protocolVersion >= 2) {
                withContext(Dispatchers.IO) {
                    writer.write(Protocol.CLIENT_HELLO)
                    writer.write("\n")
                    writer.flush()
                }
            }

            // The handshake had a read deadline; the session must not, because
            // a healthy idle link legitimately goes quiet between keepalives.
            socket.soTimeout = 0
            drainOutbox()
            _state.value = ConnectionState.Connected(session.hostname, session.protocolVersion)
            handshakeCompleted = true

            coroutineScope {
                launch { writeLoop(writer, session.protocolVersion) }
                // readLine returning null is the reliable disconnect signal.
                // Socket.isConnected stays true after the peer closes, so
                // polling it the way the old code did never detected anything.
                withContext(Dispatchers.IO) { while (reader.readLine() != null) Unit }
                throw IOException("Server closed the connection")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Session ended: ${e.message}")
            _state.value = ConnectionState.Disconnected(describe(e))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bad handshake", e)
            _state.value = ConnectionState.Disconnected("Unrecognized server")
        } finally {
            // Closed directly rather than via withContext: this also runs on
            // the cancellation path, where withContext would immediately throw
            // and leak the socket.
            runCatching { socket.close() }
            if (activeSocket === socket) activeSocket = null
        }
        return handshakeCompleted
    }

    private fun openSocket(): Socket {
        // Deliberately not written as Socket().apply { ... }: inside such a
        // block the receiver is the Socket, whose own `port` property (0 until
        // connected) silently shadows this class's `port` field, and every
        // connect goes to port 0. Resolve the address before touching a Socket.
        val address = InetSocketAddress(host, port)
        val socket = Socket()
        // Nagle would coalesce pointer samples into fewer, later packets. On a
        // latency-critical path that trade is exactly backwards.
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        socket.connect(address, CONNECT_TIMEOUT_MS)
        return socket
    }

    private data class Session(val hostname: String, val protocolVersion: Int)

    private fun parseGreeting(line: String): Session = when {
        line.startsWith(Protocol.GREETING_PREFIX) -> {
            val body = line.removePrefix(Protocol.GREETING_PREFIX)
            val version = body.substringBefore(' ').toIntOrNull()
                ?: throw IllegalArgumentException("Malformed greeting: $line")
            Session(body.substringAfter(' ', UNKNOWN_HOST), version)
        }
        // Older servers only speak v1. Stay compatible rather than refuse.
        line.startsWith(Protocol.LEGACY_GREETING_PREFIX) ->
            Session(line.removePrefix(Protocol.LEGACY_GREETING_PREFIX), 1)

        else -> throw IllegalArgumentException("Unexpected greeting: $line")
    }

    /** Discards samples queued while disconnected; they describe a stale pose. */
    private fun drainOutbox() {
        while (outbox.tryReceive().isSuccess) Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun writeLoop(writer: BufferedWriter, protocolVersion: Int) {
        val batch = ArrayList<Message>(INITIAL_BATCH_CAPACITY)
        val line = StringBuilder(64)

        while (coroutineContext.isActive) {
            batch.clear()

            // Block for the first message, but wake up on the keepalive
            // interval so a silent half-open link still surfaces as an error.
            val first = select<Message?> {
                outbox.onReceive { it }
                onTimeout(KEEPALIVE_INTERVAL_MS) { null }
            }
            if (first == null) {
                withContext(Dispatchers.IO) {
                    writer.write(Protocol.KEEPALIVE)
                    writer.write("\n")
                    writer.flush()
                }
                continue
            }
            batch.add(first)

            // Drain whatever else has piled up so a frame's worth of samples
            // leaves in one write and one flush rather than one syscall each.
            while (true) {
                val next = outbox.tryReceive().getOrNull() ?: break
                batch.add(next)
                if (batch.size >= MAX_BATCH) break
            }

            if (batch.size > COALESCE_THRESHOLD) coalesceMoves(batch)

            val payload = buildPayload(batch, line, protocolVersion)
            withContext(Dispatchers.IO) {
                writer.write(payload)
                writer.flush()
            }
        }
    }

    private fun buildPayload(
        batch: List<Message>,
        line: StringBuilder,
        protocolVersion: Int,
    ): String {
        line.setLength(0)
        for (message in batch) {
            when (message) {
                is Message.Cancel -> appendCancel(line, protocolVersion)
                is Message.Sample -> appendSample(line, message.sample, protocolVersion)
            }
        }
        return line.toString()
    }

    private fun appendCancel(line: StringBuilder, protocolVersion: Int) {
        if (protocolVersion >= 2) {
            line.append(Protocol.CANCEL).append('\n')
        } else {
            // v1 has no cancel. Synthesize a release so the button cannot stick.
            line.append("UP:0.0000,0.0000").append('\n')
        }
    }

    private fun appendSample(line: StringBuilder, sample: PointerSample, protocolVersion: Int) {
        if (protocolVersion >= 2) {
            sample.encodeTo(line)
            line.append('\n')
            return
        }
        // v1 understood only DOWN/MOVE/UP with float coordinates.
        val verb = when (sample.phase) {
            PointerPhase.DOWN -> "DOWN"
            PointerPhase.MOVE -> "MOVE"
            PointerPhase.UP -> "UP"
            else -> return
        }
        line.append(verb).append(':')
            .append(String.format(Locale.US, "%.4f,%.4f", sample.x, sample.y))
            .append('\n')
    }

    /**
     * Collapses runs of consecutive MOVE samples to their last element while
     * preserving every DOWN, UP, OUT_OF_RANGE and CANCEL. Only reached when the
     * link is falling behind; the alternative — dropping events blindly — can
     * strand a pressed button on the PC.
     */
    private fun coalesceMoves(batch: MutableList<Message>) {
        var write = 0
        for (read in batch.indices) {
            val current = batch[read]
            val next = batch.getOrNull(read + 1)
            if (isPlainMove(current) && isPlainMove(next)) continue
            batch[write++] = current
        }
        while (batch.size > write) batch.removeAt(batch.size - 1)
    }

    private fun isPlainMove(message: Message?): Boolean =
        message is Message.Sample && message.sample.phase == PointerPhase.MOVE

    private fun describe(e: IOException): String = when {
        e.message?.contains("ECONNREFUSED", ignoreCase = true) == true ||
            e.message?.contains("refused", ignoreCase = true) == true -> "Server not running"

        else -> "Disconnected"
    }

    private companion object {
        const val TAG = "TabletConnection"
        const val UNKNOWN_HOST = "Unknown PC"
        const val CONNECT_TIMEOUT_MS = 3_000
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        const val KEEPALIVE_INTERVAL_MS = 2_000L
        const val INITIAL_RECONNECT_DELAY_MS = 500L
        const val MAX_RECONNECT_DELAY_MS = 5_000L
        const val INITIAL_BATCH_CAPACITY = 64
        const val MAX_BATCH = 512

        /** Batches larger than this mean the writer is behind a frame. */
        const val COALESCE_THRESHOLD = 32
    }
}
