package com.kabu.iccard

/**
 * Asaca の利用履歴（サービス 010F, サイクリック, 新しい順）の解析。
 * 実カードのダンプから判明したフォーマット（1ブロック16バイト）:
 *  [0]月 [1]日 [2]時 [3]分 [4]種別(03=チャージ, 05=定期で乗車, 09=残高で支払, 他は不明)
 *  [5-6]乗車停留所? [7-8]降車停留所? [9-10]運賃(BE) [11]通番 [12]? [13]?
 *  [14-15]その時点の残高(BE)
 */
object Asaca {
    const val HISTORY_SERVICE = 0x010F

    data class Entry(
        val month: Int, val day: Int, val hour: Int, val minute: Int,
        val type: Int, val from: Int, val to: Int, val fare: Int, val seq: Int, val balance: Int,
        val delta: Int?
    ) {
        val time get() = "%d/%d %02d:%02d".format(month, day, hour, minute)
        val label: String get() = when {
            type == 0x03 || (delta != null && delta > 0) -> "チャージ"
            type == 0x05 -> "乗車（定期）"
            type == 0x09 -> "乗車（残高）"
            delta != null && delta < 0 -> "支払"
            else -> "記録(種別%02X)".format(type)
        }
        val amount: String get() = when {
            delta != null && delta != 0 -> "%+,d円".format(delta)
            type == 0x09 && fare > 0 -> "-%,d円".format(fare)
            else -> ""
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    fun history(d: CardDump): List<Entry>? {
        val blocks = d.systems.flatMap { it.services }
            .firstOrNull { it.code == HISTORY_SERVICE }?.blocks ?: return null
        val valid = blocks.map { it.data }.filter { b -> b.any { it != 0.toByte() } && u8(b, 0) in 1..12 }
        if (valid.isEmpty()) return null
        return valid.mapIndexed { i, b ->
            val older = valid.getOrNull(i + 1)
            Entry(
                u8(b, 0), u8(b, 1), u8(b, 2), u8(b, 3), u8(b, 4),
                be16(b, 5), be16(b, 7), be16(b, 9), u8(b, 11), be16(b, 14),
                older?.let { be16(b, 14) - be16(it, 14) }
            )
        }
    }

    fun balance(d: CardDump): Int? = history(d)?.firstOrNull()?.balance
}
