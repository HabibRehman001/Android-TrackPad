package com.example.phonetrackpad

/**
 * Wire format: a fixed 5-byte binary frame per packet.
 *   byte 0     -> type
 *   bytes 1-2  -> value1, signed 16-bit little-endian (dx for Move, amount for Scroll)
 *   bytes 3-4  -> value2, signed 16-bit little-endian (dy for Move, unused otherwise)
 *
 * Fixed-size framing means the desktop side never has to guess where one
 * packet ends and the next begins - no JSON, no delimiter, no string
 * parsing. That matters at up to 240 samples/sec: JSON's allocation and
 * text encoding is exactly the k5ind of per-event overhead you don't want
 * on this hot path.
 */
const val PACKET_SIZE = 5

sealed class Packet {
    data class Move(val dx: Int, val dy: Int) : Packet()
    object Click : Packet()
    object DoubleClick : Packet()
    object RightClick : Packet()
    object MiddleClick : Packet()
    data class Scroll(val amount: Int) : Packet()

    /** Fills a reusable 5-byte buffer - the caller owns the allocation so
     *  we don't allocate a new byte array for every touch sample. */
    fun writeTo(buf: ByteArray) {
        buf[0] = 0; buf[1] = 0; buf[2] = 0; buf[3] = 0; buf[4] = 0
        when (this) {
            is Move -> {
                buf[0] = TYPE_MOVE
                putShort(buf, 1, dx)
                putShort(buf, 3, dy)
            }
            Click -> buf[0] = TYPE_CLICK
            DoubleClick -> buf[0] = TYPE_DOUBLE_CLICK
            RightClick -> buf[0] = TYPE_RIGHT_CLICK
            MiddleClick -> buf[0] = TYPE_MIDDLE_CLICK
            is Scroll -> {
                buf[0] = TYPE_SCROLL
                putShort(buf, 1, amount)
            }
        }
    }

    private fun putShort(buf: ByteArray, offset: Int, value: Int) {
        val v = value.coerceIn(-32768, 32767)
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    companion object {
        const val TYPE_MOVE: Byte = 0
        const val TYPE_CLICK: Byte = 1
        const val TYPE_DOUBLE_CLICK: Byte = 2
        const val TYPE_RIGHT_CLICK: Byte = 3
        const val TYPE_MIDDLE_CLICK: Byte = 4
        const val TYPE_SCROLL: Byte = 5
    }
}
