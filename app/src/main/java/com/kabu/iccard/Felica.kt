package com.kabu.iccard

import android.nfc.tech.NfcF
import java.io.IOException

/** 1ブロック(16バイト)の読み取り結果 */
data class Block(val index: Int, val data: ByteArray)

/** 1サービスの読み取り結果 */
data class Service(val code: Int, val blocks: List<Block>) {
    val attrName: String get() = Felica.attrName(code)
}

/** 1システムの読み取り結果 */
data class SystemDump(val systemCode: Int, val idm: ByteArray, val services: List<Service>, val allServiceCodes: List<Int>)

/** カード全体のダンプ */
data class CardDump(val time: Long, val cardId: String, val systems: List<SystemDump>)

/**
 * FeliCa の生コマンドで、鍵なしで読める領域を全部読み出す。
 * 使うコマンド: Polling(0x00) / Request System Code(0x0C) /
 *              Search Service Code(0x0A) / Read Without Encryption(0x06)
 */
class Felica(private val nfc: NfcF) {

    companion object {
        const val MAX_BLOCKS = 40      // 1サービスあたり最大何ブロック読むか
        const val MAX_SERVICES = 200   // 探索するサービス数の上限

        /** 認証なしで読めるサービスか（属性の最下位ビットが1、かつエリア定義ではない） */
        fun isReadable(code: Int): Boolean {
            val attr = code and 0x3F
            return (attr and 0x01) == 1 && attr >= 0x08
        }

        fun attrName(code: Int): String = when (code and 0x3F) {
            0x09 -> "ランダム RW"
            0x0B -> "ランダム RO"
            0x0D -> "サイクリック RW"
            0x0F -> "サイクリック RO"
            0x11, 0x13, 0x15, 0x17 -> "パース"
            else -> "要認証"
        }
    }

    private fun tx(cmd: ByteArray): ByteArray {
        val packet = ByteArray(cmd.size + 1)
        packet[0] = (cmd.size + 1).toByte()
        System.arraycopy(cmd, 0, packet, 1, cmd.size)
        return nfc.transceive(packet)
    }

    /** 指定システムコードでポーリングして IDm を得る */
    fun polling(systemCode: Int): ByteArray? {
        return try {
            val res = tx(byteArrayOf(0x00, (systemCode shr 8).toByte(), systemCode.toByte(), 0x01, 0x00))
            if (res.size >= 18 && res[1] == 0x01.toByte()) res.copyOfRange(2, 10) else null
        } catch (e: IOException) {
            null
        }
    }

    fun requestSystemCodes(idm: ByteArray): List<Int> {
        return try {
            val res = tx(byteArrayOf(0x0C) + idm)
            if (res.size < 11 || res[1] != 0x0D.toByte()) return emptyList()
            val n = res[10].toInt() and 0xFF
            (0 until n).mapNotNull { i ->
                val p = 11 + i * 2
                if (p + 1 < res.size) ((res[p].toInt() and 0xFF) shl 8) or (res[p + 1].toInt() and 0xFF) else null
            }
        } catch (e: IOException) {
            emptyList()
        }
    }

    /** Search Service Code で全サービスコードを列挙（エリアは除外） */
    fun searchServices(idm: ByteArray): List<Int> {
        val list = mutableListOf<Int>()
        for (i in 0 until MAX_SERVICES) {
            val res = try {
                tx(byteArrayOf(0x0A) + idm + byteArrayOf(i.toByte(), (i shr 8).toByte()))
            } catch (e: IOException) {
                break
            }
            if (res.size < 12 || res[1] != 0x0B.toByte()) break
            val code = (res[10].toInt() and 0xFF) or ((res[11].toInt() and 0xFF) shl 8)
            if (code == 0xFFFF) break
            val isArea = res.size - 10 == 4
            if (!isArea) list.add(code)
        }
        return list
    }

    /** Read Without Encryption で1ブロック読む。読めなければ null */
    fun readBlock(idm: ByteArray, service: Int, block: Int): ByteArray? {
        return try {
            val blockElem = if (block < 256) byteArrayOf(0x80.toByte(), block.toByte())
            else byteArrayOf(0x00, block.toByte(), (block shr 8).toByte())
            val cmd = byteArrayOf(0x06) + idm +
                byteArrayOf(0x01, service.toByte(), (service shr 8).toByte(), 0x01) + blockElem
            val res = tx(cmd)
            if (res.size < 13 || res[1] != 0x07.toByte()) return null
            if (res[10] != 0x00.toByte()) return null   // ステータスフラグ1 がエラー
            if (res.size < 29) return null
            res.copyOfRange(13, 29)
        } catch (e: IOException) {
            null
        }
    }

    fun dumpAll(cardId: String): CardDump {
        val firstIdm = polling(0xFFFF) ?: nfc.tag.id
        var systemCodes = requestSystemCodes(firstIdm).ifEmpty { requestSystemCodes(firstIdm) }
        if (systemCodes.isEmpty()) {
            val sc = nfc.systemCode
            systemCodes = listOf(((sc[0].toInt() and 0xFF) shl 8) or (sc[1].toInt() and 0xFF))
        }
        val systems = systemCodes.map { sys ->
            val idm = polling(sys) ?: firstIdm
            val codes = searchServices(idm)
            val services = codes.filter { isReadable(it) }.map { svc ->
                val blocks = mutableListOf<Block>()
                for (b in 0 until MAX_BLOCKS) {
                    val d = readBlock(idm, svc, b) ?: break
                    blocks.add(Block(b, d))
                }
                Service(svc, blocks)
            }
            SystemDump(sys, idm, services, codes)
        }
        return CardDump(System.currentTimeMillis(), cardId, systems)
    }
}

fun ByteArray.hex(sep: String = " "): String = joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }
