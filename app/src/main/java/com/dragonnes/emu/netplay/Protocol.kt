package com.dragonnes.emu.netplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Netplay messages. Every message starts with a type byte. */
sealed class Msg {
    /** Guest -> host on connect, and host -> guest as the answer. */
    data class Hello(val version: Int, val romCrc: String, val name: String, val game: String) : Msg()
    /** Host -> guest: start (or restart) from this exact emulator state. */
    class Start(val epoch: Int, val delay: Int, val batch: Int, val state: ByteArray) : Msg()
    /** Guest -> host: state loaded, ready to run frame 0. */
    data class Ready(val epoch: Int) : Msg()
    /** Inputs of one player for frames [first] until first + masks.size - 1. */
    class Input(val epoch: Int, val first: Int, val masks: ByteArray) : Msg()
    /** Emulator state checksum at the start of [frame], to detect desyncs. */
    data class Check(val epoch: Int, val frame: Int, val crc: Int) : Msg()
    data class Ping(val time: Long) : Msg()
    data class Pong(val time: Long) : Msg()
    /** Player opened the menu / came back. */
    data class Paused(val paused: Boolean) : Msg()
    data class Error(val reason: String) : Msg()
    object Bye : Msg()
}

object Protocol {
    const val VERSION = 1
    const val LAN_PORT = 55435
    const val DISCOVERY_PORT = 55436

    fun encode(m: Msg): ByteArray {
        val bo = ByteArrayOutputStream(32)
        DataOutputStream(bo).use { o ->
            when (m) {
                is Msg.Hello -> { o.writeByte(1); o.writeInt(m.version); o.writeUTF(m.romCrc); o.writeUTF(m.name); o.writeUTF(m.game) }
                is Msg.Start -> { o.writeByte(2); o.writeInt(m.epoch); o.writeByte(m.delay); o.writeByte(m.batch); o.writeInt(m.state.size); o.write(m.state) }
                is Msg.Ready -> { o.writeByte(3); o.writeInt(m.epoch) }
                is Msg.Input -> { o.writeByte(4); o.writeInt(m.epoch); o.writeInt(m.first); o.writeByte(m.masks.size); o.write(m.masks) }
                is Msg.Check -> { o.writeByte(5); o.writeInt(m.epoch); o.writeInt(m.frame); o.writeInt(m.crc) }
                is Msg.Ping -> { o.writeByte(6); o.writeLong(m.time) }
                is Msg.Pong -> { o.writeByte(7); o.writeLong(m.time) }
                is Msg.Paused -> { o.writeByte(8); o.writeBoolean(m.paused) }
                is Msg.Error -> { o.writeByte(9); o.writeUTF(m.reason) }
                Msg.Bye -> o.writeByte(10)
            }
        }
        return bo.toByteArray()
    }

    fun decode(b: ByteArray): Msg? = runCatching {
        DataInputStream(ByteArrayInputStream(b)).use { i ->
            when (i.readUnsignedByte()) {
                1 -> Msg.Hello(i.readInt(), i.readUTF(), i.readUTF(), i.readUTF())
                2 -> {
                    val epoch = i.readInt(); val delay = i.readUnsignedByte(); val batch = i.readUnsignedByte()
                    val st = ByteArray(i.readInt()); i.readFully(st)
                    Msg.Start(epoch, delay, batch, st)
                }
                3 -> Msg.Ready(i.readInt())
                4 -> {
                    val epoch = i.readInt(); val first = i.readInt()
                    val masks = ByteArray(i.readUnsignedByte()); i.readFully(masks)
                    Msg.Input(epoch, first, masks)
                }
                5 -> Msg.Check(i.readInt(), i.readInt(), i.readInt())
                6 -> Msg.Ping(i.readLong())
                7 -> Msg.Pong(i.readLong())
                8 -> Msg.Paused(i.readBoolean())
                9 -> Msg.Error(i.readUTF())
                10 -> Msg.Bye
                else -> null
            }
        }
    }.getOrNull()
}

/** NES pad state packed in one byte. */
object Pad {
    const val UP = 1; const val DOWN = 2; const val LEFT = 4; const val RIGHT = 8
    const val A = 16; const val B = 32; const val SELECT = 64; const val START = 128
}
