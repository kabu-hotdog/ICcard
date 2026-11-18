package com.kabu.iccard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 残高が入っている場所の候補 */
data class Loc(val system: Int, val service: Int, val block: Int, val offset: Int, val little: Boolean) {
    fun key() = "%04X/%04X/%d/%d/%s".format(system, service, block, offset, if (little) "LE" else "BE")
    fun label() = "SYS %04X  SVC %04X  BLK %d  +%d  %s".format(system, service, block, offset, if (little) "LE" else "BE")
    fun read(d: CardDump): Int? {
        val sys = d.systems.firstOrNull { it.systemCode == system } ?: return null
        val svc = sys.services.firstOrNull { it.code == service } ?: return null
        val b = svc.blocks.firstOrNull { it.index == block } ?: return null
        val lo = b.data[offset].toInt() and 0xFF
        val hi = b.data[offset + 1].toInt() and 0xFF
        return if (little) lo or (hi shl 8) else (lo shl 8) or hi
    }

    companion object {
        fun parse(s: String): Loc? = try {
            val p = s.split("/")
            Loc(p[0].toInt(16), p[1].toInt(16), p[2].toInt(), p[3].toInt(), p[4] == "LE")
        } catch (e: Exception) {
            null
        }

        fun search(d: CardDump, value: Int): List<Loc> {
            val out = mutableListOf<Loc>()
            for (sys in d.systems) for (svc in sys.services) for (b in svc.blocks) {
                for (o in 0..14) {
                    val lo = b.data[o].toInt() and 0xFF
                    val hi = b.data[o + 1].toInt() and 0xFF
                    if ((lo or (hi shl 8)) == value) out.add(Loc(sys.systemCode, svc.code, b.index, o, true))
                    if (((lo shl 8) or hi) == value) out.add(Loc(sys.systemCode, svc.code, b.index, o, false))
                }
            }
            return out.distinctBy { it.key() }
        }
    }
}

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    private var nfc: NfcAdapter? = null
    private lateinit var status: TextView
    private lateinit var balanceView: TextView
    private lateinit var dumpView: TextView
    private var lastDump: CardDump? = null

    private val prefs by lazy { getSharedPreferences("iccard", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        balanceView = TextView(this).apply {
            textSize = 40f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            text = "¥ ----"
        }
        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
            text = if (nfc == null) "この端末はNFC非対応" else "カードを背面にかざしてください"
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnBalance = Button(this).apply {
            text = "今の残高を教える"
            setOnClickListener { askBalance() }
        }
        val btnShare = Button(this).apply {
            text = "ダンプを共有"
            setOnClickListener { share() }
        }
        val btnReset = Button(this).apply {
            text = "リセット"
            setOnClickListener { confirmReset() }
        }
        val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        buttons.addView(btnBalance, lp)
        buttons.addView(btnShare, lp)
        buttons.addView(btnReset, lp)

        dumpView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, 24, 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(dumpView) }

        root.addView(balanceView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        loadDumps().lastOrNull()?.let {
            lastDump = it
            render(it, null)
        }
    }

    override fun onResume() {
        super.onResume()
        nfc?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val f = NfcF.get(tag) ?: run {
            runOnUiThread { status.text = "FeliCaカードではありません" }
            return
        }
        runOnUiThread { status.text = "読み取り中… 離さないで" }
        try {
            f.connect()
            f.timeout = 1000
            val dump = Felica(f).dumpAll(tag.id.hex(""))
            f.close()
            val prev = loadDumps().lastOrNull { it.cardId == dump.cardId }
            saveDump(dump)
            lastDump = dump
            runOnUiThread {
                status.text = "読み取り完了 " + fmtTime(dump.time)
                render(dump, prev)
            }
        } catch (e: Exception) {
            runOnUiThread { status.text = "読み取り失敗: ${e.message}（もう一度かざしてください）" }
        }
    }

    // ---------- 表示 ----------

    private fun render(d: CardDump, prev: CardDump?) {
        val locs = loadLocs()
        balanceView.text = when {
            locs.isEmpty() -> "¥ ----"
            locs.size == 1 -> locs[0].read(d)?.let { "¥ %,d".format(it) } ?: "¥ ????"
            else -> "候補" + locs.size + "個"
        }
        balanceView.setTextColor(if (locs.size == 1) Color.rgb(0, 100, 200) else Color.GRAY)

        val sb = StringBuilder()
        if (locs.size > 1) {
            sb.append("■ 残高の候補（チャージや乗車の後に再度「今の残高を教える」で絞り込めます）\n")
            locs.forEach { sb.append("  ${it.label()} = ${it.read(d)}\n") }
            sb.append("\n")
        }
        if (prev != null) {
            sb.append("■ 前回(${fmtTime(prev.time)})からの変化\n")
            val diffs = diff(prev, d)
            if (diffs.isEmpty()) sb.append("  変化なし\n") else diffs.forEach { sb.append(it).append("\n") }
            sb.append("\n")
        }
        sb.append(dumpText(d))
        dumpView.text = sb.toString()
    }

    private fun dumpText(d: CardDump): String {
        val sb = StringBuilder()
        sb.append("■ ダンプ ${fmtTime(d.time)}\nカードID ${d.cardId}\n")
        for (sys in d.systems) {
            sb.append("\n=== システム %04X  IDm %s ===\n".format(sys.systemCode, sys.idm.hex("")))
            sb.append("全サービス: " + sys.allServiceCodes.joinToString(" ") { "%04X".format(it) } + "\n")
            for (svc in sys.services) {
                sb.append("\n--- サービス %04X (%s) %dブロック ---\n".format(svc.code, svc.attrName, svc.blocks.size))
                for (b in svc.blocks) sb.append("%02d: %s\n".format(b.index, b.data.hex()))
            }
        }
        return sb.toString()
    }

    private fun diff(a: CardDump, b: CardDump): List<String> {
        val out = mutableListOf<String>()
        for (sys in b.systems) {
            val oldSys = a.systems.firstOrNull { it.systemCode == sys.systemCode }
            for (svc in sys.services) {
                val oldSvc = oldSys?.services?.firstOrNull { it.code == svc.code }
                for (blk in svc.blocks) {
                    val old = oldSvc?.blocks?.firstOrNull { it.index == blk.index }?.data
                    if (old == null || !old.contentEquals(blk.data)) {
                        out.add("  %04X/%04X #%02d\n    前 %s\n    後 %s".format(
                            sys.systemCode, svc.code, blk.index,
                            old?.hex() ?: "(なし)", blk.data.hex()))
                    }
                }
            }
        }
        return out
    }

    // ---------- 残高の位置探し ----------

    private fun askBalance() {
        val d = lastDump ?: run { status.text = "先にカードを読み取ってください"; return }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "例: 1530"
        }
        AlertDialog.Builder(this)
            .setTitle("最後に読んだ時点の残高（円）")
            .setView(input)
            .setPositiveButton("探す") { _, _ ->
                val v = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                val found = Loc.search(d, v)
                val old = loadLocs()
                val merged = if (old.isEmpty()) found
                else old.filter { o -> found.any { it.key() == o.key() } }.ifEmpty { found }
                saveLocs(merged)
                status.text = when (merged.size) {
                    0 -> "¥$v はカード内に見つかりませんでした"
                    1 -> "残高の場所を特定しました！ 次回からかざすだけで表示されます"
                    else -> "候補が${merged.size}個あります。乗車やチャージの後にもう一度教えてください"
                }
                render(d, null)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setMessage("保存したダンプと残高の位置をすべて消しますか？")
            .setPositiveButton("消す") { _, _ ->
                prefs.edit().clear().apply()
                lastDump = null
                balanceView.text = "¥ ----"
                dumpView.text = ""
                status.text = "リセットしました"
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun share() {
        val d = lastDump ?: run { status.text = "先にカードを読み取ってください"; return }
        val dumps = loadDumps().filter { it.cardId == d.cardId }.takeLast(3)
        val text = dumps.joinToString("\n\n") { dumpText(it) }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            "ダンプを共有"))
    }

    // ---------- 保存 ----------

    private fun fmtTime(t: Long) = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(Date(t))

    private fun loadLocs(): List<Loc> =
        prefs.getString("locs", "")!!.split(",").filter { it.isNotBlank() }.mapNotNull { Loc.parse(it) }

    private fun saveLocs(l: List<Loc>) = prefs.edit().putString("locs", l.joinToString(",") { it.key() }).apply()

    private fun saveDump(d: CardDump) {
        val list = (loadDumps() + d).takeLast(10)
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("dumps", arr.toString()).apply()
    }

    private fun loadDumps(): List<CardDump> = try {
        val arr = JSONArray(prefs.getString("dumps", "[]"))
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun toJson(d: CardDump) = JSONObject().apply {
        put("time", d.time)
        put("card", d.cardId)
        put("systems", JSONArray().apply {
            d.systems.forEach { s ->
                put(JSONObject().apply {
                    put("code", s.systemCode)
                    put("idm", s.idm.hex(""))
                    put("all", JSONArray(s.allServiceCodes))
                    put("services", JSONArray().apply {
                        s.services.forEach { v ->
                            put(JSONObject().apply {
                                put("code", v.code)
                                put("blocks", JSONArray().apply { v.blocks.forEach { put(it.data.hex("")) } })
                            })
                        }
                    })
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): CardDump {
        val systems = o.getJSONArray("systems")
        return CardDump(o.getLong("time"), o.getString("card"), (0 until systems.length()).map { i ->
            val s = systems.getJSONObject(i)
            val all = s.getJSONArray("all")
            val svcs = s.getJSONArray("services")
            SystemDump(
                s.getInt("code"), unhex(s.getString("idm")),
                (0 until svcs.length()).map { j ->
                    val v = svcs.getJSONObject(j)
                    val bl = v.getJSONArray("blocks")
                    Service(v.getInt("code"), (0 until bl.length()).map { k -> Block(k, unhex(bl.getString(k))) })
                },
                (0 until all.length()).map { all.getInt(it) }
            )
        })
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
