package dev.agenthub.app.demo

import okio.Buffer
import okio.ByteString
import okio.Source
import okio.Timeout
import java.io.IOException

/** One piece of a streamed response. [onStart] runs just before the bytes are released. */
internal class Chunk(val delayMs: Long, val bytes: ByteString, val onStart: (() -> Unit)? = null)

/**
 * Releases [chunks] one at a time with a pause before each, like a server streaming events. Ends early,
 * without a final chunk, when [cancelled] turns true or the reader closes it.
 */
internal class ScriptedSource(
    private val chunks: List<Chunk>,
    private val cancelled: () -> Boolean,
) : Source {
    private var index = 0
    private val current = Buffer()

    @Volatile private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (closed) throw IOException("closed")
        while (current.exhausted()) {
            if (index >= chunks.size || cancelled()) return -1
            val chunk = chunks[index++]
            if (!pause(chunk.delayMs)) return -1
            chunk.onStart?.invoke()
            current.write(chunk.bytes)
        }
        return current.read(sink, minOf(byteCount, current.size))
    }

    // Sleeps in short slices so Stop and a closed connection are noticed quickly.
    private fun pause(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (closed || cancelled()) return false
            val step = minOf(left, SLICE_MS)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            left -= step
        }
        return !(closed || cancelled())
    }

    override fun timeout(): Timeout = Timeout.NONE
    override fun close() { closed = true }

    private companion object { const val SLICE_MS = 20L }
}
