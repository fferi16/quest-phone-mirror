package com.feri.vrmirror.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A telefon és a Quest közötti hálózati protokoll.
 *
 * Minden üzenet: [típus: 1 bájt][hossz: 4 bájt, big-endian][payload].
 * A telefon a szerver (TCP_PORT), a Quest a kliens.
 */
object Protocol {
    const val TCP_PORT = 7788
    const val DISCOVERY_PORT = 7789
    const val DISCOVERY_REQUEST = "VRMIRROR_DISCOVER"
    const val DISCOVERY_REPLY = "VRMIRROR_HERE"

    // ---- Telefon -> Quest ----

    /** A videó mérete. Payload: width(int32), height(int32). */
    const val MSG_VIDEO_CONFIG: Byte = 1

    /** H.264 SPS/PPS (codec config). Payload: nyers NAL adat. */
    const val MSG_CODEC_CONFIG: Byte = 2

    /** Egy képkocka. Payload: flags(int32), pts(int64), NAL adat. */
    const val MSG_VIDEO_FRAME: Byte = 3

    /** A telefon állapota. Payload: flags(int32), lásd STATUS_FLAG_*. */
    const val MSG_STATUS: Byte = 4

    /** Hang formátuma. Payload: sampleRate(int32), channels(int32). */
    const val MSG_AUDIO_CONFIG: Byte = 6

    /** Hangadat: PCM 16 bit, little-endian, interleaved. */
    const val MSG_AUDIO: Byte = 7

    /** Fut a képernyőrögzítés. */
    const val STATUS_FLAG_CAPTURING = 1
    /** Az érintésvezérlés (Kisegítő lehetőségek szolgáltatás) engedélyezve van. */
    const val STATUS_FLAG_TOUCH_ENABLED = 2

    // ---- Quest -> Telefon ----

    /** Érintés. Payload: action(int8), x(float32), y(float32). x,y: 0..1 arányos. */
    const val MSG_TOUCH: Byte = 16

    /** Rendszergomb. Payload: key(int8). */
    const val MSG_KEY: Byte = 17

    /** Kulcskocka kérése (üres payload). */
    const val MSG_REQUEST_KEYFRAME: Byte = 18

    /**
     * Görgetés (hüvelykujj-kar / egérgörgő). Payload: x(float32), y(float32), dx(float32), dy(float32).
     * x,y: 0..1 arányos pozíció; dx,dy: görgetési "fokok" (egy fok kb. egy görgőkattintás).
     */
    const val MSG_SCROLL: Byte = 19

    /**
     * Kétujjas csippentés. Payload: action(int8: PINCH_START/UPDATE/END), cx(float32), cy(float32), spread(float32).
     * cx,cy: a két ujj közepe 0..1 arányosan; spread: a két ujj távolsága a videó magasságának arányában.
     */
    const val MSG_PINCH: Byte = 20

    const val PINCH_START = 0
    const val PINCH_UPDATE = 1
    const val PINCH_END = 2

    const val TOUCH_DOWN = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_UP = 2
    const val TOUCH_CANCEL = 3

    const val KEY_BACK = 0
    const val KEY_HOME = 1
    const val KEY_RECENTS = 2

    const val FRAME_FLAG_KEYFRAME = 1

    /** Egy üzenet legnagyobb megengedett mérete. */
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    val EMPTY = ByteArray(0)
}

class Message(val type: Byte, val payload: ByteArray)

/** Szálbiztos üzenetíró egy kimeneti folyamra. */
class MessageWriter(stream: OutputStream) {
    private val out = DataOutputStream(stream.buffered(64 * 1024))

    @Synchronized
    fun write(type: Byte, payload: ByteArray = Protocol.EMPTY) {
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    @Synchronized
    fun write(type: Byte, payload: ByteArray, offset: Int, length: Int) {
        out.writeByte(type.toInt())
        out.writeInt(length)
        out.write(payload, offset, length)
        out.flush()
    }

    /** Fejléc + törzs egy üzenetként, felesleges másolás nélkül. */
    @Synchronized
    fun write(type: Byte, header: ByteArray, body: ByteArray, bodyOffset: Int = 0, bodyLength: Int = body.size) {
        out.writeByte(type.toInt())
        out.writeInt(header.size + bodyLength)
        out.write(header)
        out.write(body, bodyOffset, bodyLength)
        out.flush()
    }
}

/** Üzenetolvasó egy bemeneti folyamról. Blokkol, amíg nem jön teljes üzenet. */
class MessageReader(stream: InputStream) {
    private val input = DataInputStream(stream.buffered(64 * 1024))

    fun read(): Message {
        val type = input.readByte()
        val length = input.readInt()
        require(length in 0..Protocol.MAX_PAYLOAD) { "Hibás üzenethossz: $length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        return Message(type, payload)
    }
}
