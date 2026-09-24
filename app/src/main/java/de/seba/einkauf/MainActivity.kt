package de.seba.einkauf

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.Executors
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Einkauf: Monatsliste (Menge | Artikel | schon da | Woche) -> automatisch auf
 * Wochenlisten verteilt -> im Laden abhaken. Alles bleibt lokal auf dem Handy.
 */
class MainActivity : ComponentActivity() {

    // Farben (wie bei "Schritte")
    private val bg = 0xFF0F1115.toInt()
    private val card = 0xFF1A1D24.toInt()
    private val line = 0xFF2A2E38.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFF8A90A0.toInt()
    private val dim = 0xFF555B68.toInt()
    private val blue = 0xFF4F8CFF.toInt()
    private val green = 0xFF3DDC84.toInt()
    private val orange = 0xFFFFB454.toInt()

    /** Eine Zeile der Monatsliste (bearbeitbar). */
    private class Row(
        var id: String = UUID.randomUUID().toString(),
        var qty: Int = 1,
        var name: String = "",
        var have: Int = 0,
        var weeksSel: Set<Int> = emptySet(),
        // Packungsrechner (optional): Inhalt einer Packung, Verbrauch pro Tag, Tage (null = Wochen × 7)
        var perPack: Double? = null,
        var perDay: Double? = null,
        var days: Int? = null,
    )

    private val prefs by lazy { getSharedPreferences("einkauf", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    // Zustand
    private val rows = mutableListOf<Row>()
    private var weeks = 4
    private var checked = mutableSetOf<String>()
    private var bought = mutableMapOf<String, Int>() // teilweise gekauft: key -> Anzahl
    private var tab = 0 // 0 = Monat, 1..weeks = Woche

    // Abgleich
    private var doc: JSONObject = SyncDoc.empty()
    private var cfg: SyncDoc.Config? = null
    private val io = Executors.newSingleThreadExecutor()
    private var syncing = false
    private var syncAgain = false
    private var syncState = "off"
    private var lastSync = 0L
    private var lastType = 0L
    private var monthSyncLine: TextView? = null
    private var weekSyncLine: TextView? = null
    private val syncTask = Runnable { sync() }
    private val pollTask = object : Runnable {
        override fun run() { sync(); handler.postDelayed(this, 5000) }
    }

    // Views
    private lateinit var tabBar: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var monthView: View
    private lateinit var rowsBox: LinearLayout
    private lateinit var monthStats: TextView
    private lateinit var weekToggle: LinearLayout

    private val saveRowsTask = Runnable { saveRows() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        loadRows()
        weeks = prefs.getInt("weeks", 4).coerceIn(4, 5)
        checked = (prefs.getStringSet("checked", emptySet()) ?: emptySet()).toMutableSet()
        for (s in prefs.getStringSet("bought", emptySet()) ?: emptySet()) {
            val i = s.indexOf('|')
            val n = if (i > 0) s.substring(0, i).toIntOrNull() else null
            if (n != null) bought[s.substring(i + 1)] = n
        }
        // gemeinsames Datenmodell (ab Version 3.0); ältere Daten werden einmalig übernommen
        val savedDoc = prefs.getString("doc", null)
        if (savedDoc != null) {
            doc = try { SyncDoc.merge(JSONObject(savedDoc), null) } catch (e: Exception) { SyncDoc.empty() }
            stateFromDoc()
        } else {
            doc = buildDoc(SyncDoc.empty())
            prefs.edit().putString("doc", doc.toString()).apply()
        }
        cfg = prefs.getString("sync", null)?.let { SyncDoc.decodeConnect(it) }
        if (cfg != null) syncState = "busy"
        tab = prefs.getInt("tab", 0).coerceIn(0, weeks)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(10))
        }
        content = FrameLayout(this)
        root.addView(tabBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        monthView = buildMonthView()
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollTask)
        handler.post(pollTask)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(saveRowsTask)
        saveRows()
        sync()
    }

    // ---------- Daten ----------

    private fun loadRows() {
        rows.clear()
        val json = prefs.getString("items", null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    rows += Row(
                        qty = o.optInt("qty", 1).coerceAtLeast(1),
                        name = o.optString("name", ""),
                        have = o.optInt("have", 0).coerceAtLeast(0),
                        weeksSel = when {
                            o.has("weeks") -> o.getJSONArray("weeks").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
                            o.has("week") -> setOf(o.getInt("week")) // Version 2.0
                            else -> emptySet()
                        },
                        perPack = if (o.has("perPack")) o.getDouble("perPack") else null,
                        perDay = if (o.has("perDay")) o.getDouble("perDay") else null,
                        days = if (o.has("days")) o.getInt("days") else null,
                    )
                }
            } catch (e: Exception) { rows.clear() }
        } else {
            // Übernahme der alten Textliste aus Version 1.x
            for (l in (prefs.getString("text", "") ?: "").lines()) {
                val it = Planner.parseLine(l) ?: continue
                rows += Row(qty = it.qty, name = it.name, have = it.have, weeksSel = it.onlyWeeks)
            }
        }
        rows.removeAll { it.name.isBlank() }
    }

    /** Speichert den aktuellen Stand (mit Zeitstempeln für den Abgleich). */
    private fun saveRows() = commit()

    private fun commit() {
        val next = buildDoc(doc)
        if (SyncDoc.canon(next) != SyncDoc.canon(doc)) {
            doc = next
            prefs.edit().putString("doc", doc.toString()).apply()
            scheduleSync(700)
        }
    }

    private fun rowJson(r: Row): JSONObject {
        val o = JSONObject()
            .put("name", r.name)
            .put("qty", r.qty)
            .put("have", r.have)
            .put("weeks", JSONArray(r.weeksSel.sorted()))
        r.perPack?.let { o.put("perPack", it) }
        r.perDay?.let { o.put("perDay", it) }
        r.days?.let { o.put("days", it) }
        return o
    }

    /** Neuer Stand aus der Oberfläche; nur Geändertes bekommt einen neuen Zeitstempel. */
    private fun buildDoc(prev: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        val d = SyncDoc.merge(prev, null, now)
        val items = d.getJSONObject("items")
        val live = rows.filter { it.name.isNotBlank() }
        val liveIds = live.map { it.id }.toSet()
        for (r in live) {
            val o = rowJson(r)
            val p = items.optJSONObject(r.id)
            val same = p != null && !p.optBoolean("del") &&
                SyncDoc.canon(JSONObject(p.toString()).apply { remove("t") }) == SyncDoc.canon(o)
            if (!same) items.put(r.id, o.put("t", now))
        }
        for (id in items.keys().asSequence().toList()) {
            val p = items.getJSONObject(id)
            // nur Artikel löschen, die hier sichtbar waren (nicht die, die jemand gerade eintippt)
            if (!p.optBoolean("del") && p.optString("name").isNotBlank() && id !in liveIds) {
                items.put(id, JSONObject().put("del", true).put("t", now))
            }
        }
        val ids = live.map { it.id }
        val prevIds = SyncDoc.normalizeOrder(d.getJSONObject("order").optJSONArray("ids"), items)
        val shown = prevIds.filter { it in liveIds }
        if (ids != shown) d.put("order", JSONObject().put("ids", JSONArray(ids)).put("t", now))

        val ch = d.getJSONObject("checked")
        for (k in checked) if (ch.optJSONObject(k)?.optBoolean("v") != true) ch.put(k, JSONObject().put("v", true).put("t", now))
        for (k in ch.keys().asSequence().toList()) {
            if (ch.getJSONObject(k).optBoolean("v") && k !in checked) ch.put(k, JSONObject().put("v", false).put("t", now))
        }
        val bo = d.getJSONObject("bought")
        for ((k, n) in bought) if (bo.optJSONObject(k)?.optInt("n") != n) bo.put(k, JSONObject().put("n", n).put("t", now))
        for (k in bo.keys().asSequence().toList()) {
            if (bo.getJSONObject(k).optInt("n") > 0 && (bought[k] ?: 0) == 0) bo.put(k, JSONObject().put("n", 0).put("t", now))
        }
        if (d.getJSONObject("weeks").optInt("v", 4) != weeks) d.put("weeks", JSONObject().put("v", weeks).put("t", now))
        return d
    }

    /** Oberfläche aus dem gemeinsamen Stand aufbauen. */
    private fun stateFromDoc() {
        val items = doc.getJSONObject("items")
        val ids = SyncDoc.normalizeOrder(doc.getJSONObject("order").optJSONArray("ids"), items)
        rows.clear()
        for (id in ids) {
            val o = items.getJSONObject(id)
            val w = o.optJSONArray("weeks")
            rows += Row(
                id = id,
                qty = o.optInt("qty", 1).coerceAtLeast(1),
                name = o.optString("name", ""),
                have = o.optInt("have", 0).coerceAtLeast(0),
                weeksSel = if (w == null) emptySet() else (0 until w.length()).map { w.optInt(it) }.filter { it > 0 }.toSet(),
                perPack = if (o.has("perPack") && !o.isNull("perPack")) o.optDouble("perPack") else null,
                perDay = if (o.has("perDay") && !o.isNull("perDay")) o.optDouble("perDay") else null,
                days = if (o.has("days") && !o.isNull("days")) o.optInt("days") else null,
            )
        }
        rows.removeAll { it.name.isBlank() }
        val ch = doc.getJSONObject("checked")
        checked = ch.keys().asSequence().filter { ch.getJSONObject(it).optBoolean("v") }.toMutableSet()
        val bo = doc.getJSONObject("bought")
        bought = bo.keys().asSequence().filter { bo.getJSONObject(it).optInt("n") > 0 }
            .associateWith { bo.getJSONObject(it).optInt("n") }.toMutableMap()
        weeks = doc.getJSONObject("weeks").optInt("v", 4).coerceIn(4, 5)
    }

    // ---------- Abgleich ----------

    private fun scheduleSync(ms: Long) {
        if (cfg == null) return
        handler.removeCallbacks(syncTask)
        handler.postDelayed(syncTask, ms)
    }

    private fun sync() {
        val c = cfg ?: return
        if (syncing) { syncAgain = true; return }
        if (tab == 0 && currentFocus is EditText && System.currentTimeMillis() - lastType < 2500) {
            scheduleSync(2500); return
        }
        handler.removeCallbacks(saveRowsTask)
        commit()
        syncing = true
        if (lastSync == 0L) setSyncState("busy")
        val local = SyncDoc.copy(doc)
        io.execute {
            var result: JSONObject? = null
            try {
                val remote = SyncDoc.Remote(c)
                var (server, rev) = remote.get()
                var merged = SyncDoc.merge(local, server)
                for (i in 0 until 4) {
                    val srv = server
                    if (srv != null && SyncDoc.canon(merged) == SyncDoc.canon(SyncDoc.merge(srv, srv))) break
                    val (ok, data, r) = remote.put(merged, rev)
                    if (ok) break
                    server = data; rev = r
                    merged = SyncDoc.merge(merged, data)
                }
                result = merged
            } catch (e: Exception) {
                android.util.Log.w("Einkauf", "Abgleich fehlgeschlagen", e)
            }
            val res = result
            handler.post {
                syncing = false
                if (cfg != null) {
                    if (res != null) { applyRemote(res); lastSync = System.currentTimeMillis(); setSyncState("ok") }
                    else setSyncState("err")
                }
                if (syncAgain) { syncAgain = false; scheduleSync(300) }
            }
        }
    }

    /** Stand vom Server einarbeiten; eigene Änderungen von eben bleiben erhalten. */
    private fun applyRemote(m: JSONObject) {
        handler.removeCallbacks(saveRowsTask)
        commit()
        val next = SyncDoc.merge(doc, m)
        if (SyncDoc.canon(next) == SyncDoc.canon(doc)) return
        doc = next
        prefs.edit().putString("doc", doc.toString()).apply()
        stateFromDoc()
        if (tab > weeks) tab = weeks
        render()
    }

    private fun setSyncState(s: String) {
        syncState = s
        monthSyncLine?.let { paintSync(it) }
        weekSyncLine?.let { paintSync(it) }
    }

    private fun syncLineView(): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(4), 0, dp(6))
        setOnClickListener { settingsDialog() }
        paintSync(this)
    }

    private fun paintSync(t: TextView) {
        val (dot, label, link) = when {
            cfg == null -> Triple(dim, "Nur auf diesem Gerät", "Abgleich einrichten")
            syncState == "err" -> Triple(orange, "Offline – wird später abgeglichen", "Abgleich")
            syncState == "busy" && lastSync == 0L -> Triple(blue, "Verbinde …", "Abgleich")
            else -> Triple(green, "Abgeglichen", "Abgleich")
        }
        val sb = SpannableStringBuilder()
        fun add(text: String, color: Int) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        add("● ", dot); add("$label   ", grey); add(link, blue)
        t.text = sb
    }

    private fun settingsDialog() {
        val c = cfg
        val b = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Abgleich zwischen euren Handys")
        if (c == null) {
            val input = EditText(this).apply {
                hint = "EK1-…"
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            val wrap = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
            val d = b.setMessage("Füge den Verbindungs-Code ein (beginnt mit EK1-). Danach seht ihr auf beiden Handys dieselbe Liste.")
                .setView(wrap)
                .setPositiveButton("Verbinden", null)
                .setNegativeButton("Abbrechen", null)
                .create()
            d.setOnShowListener {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val cc = SyncDoc.decodeConnect(input.text.toString())
                    if (cc == null) input.error = "Kein gültiger Code (beginnt mit EK1-)"
                    else {
                        cfg = cc
                        prefs.edit().putString("sync", SyncDoc.encodeConnect(cc)).apply()
                        lastSync = 0L
                        setSyncState("busy")
                        d.dismiss()
                        sync()
                    }
                }
            }
            d.show()
        } else {
            val code = SyncDoc.encodeConnect(c)
            val tv = TextView(this).apply {
                text = code
                setTextIsSelectable(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setPadding(dp(24), dp(8), dp(24), 0)
            }
            b.setMessage("Verbunden. Änderungen werden alle paar Sekunden abgeglichen. Mit diesem Code verbindest du ein weiteres Gerät, z. B. das iPhone:")
                .setView(tv)
                .setPositiveButton("Code kopieren") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Einkauf", code))
                    Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Trennen") { _, _ ->
                    cfg = null
                    prefs.edit().remove("sync").apply()
                    setSyncState("off")
                }
                .setNegativeButton("Fertig", null)
                .show()
        }
    }

    private fun scheduleSave() {
        handler.removeCallbacks(saveRowsTask)
        handler.postDelayed(saveRowsTask, 500)
    }

    private fun items(): List<Planner.Item> =
        rows.filter { it.name.isNotBlank() }.map { Planner.Item(it.name, qtyOf(it), it.weeksSel, it.have) }

    private fun hasCalc(r: Row) = (r.perPack ?: 0.0) > 0 && (r.perDay ?: 0.0) > 0

    /** Menge der Zeile; mit Packungsrechner: aufgerundete Packungen für den Zeitraum. */
    private fun qtyOf(r: Row): Int {
        if (!hasCalc(r)) return r.qty
        val days = r.days ?: (weeks * 7)
        return Math.ceil(r.perDay!! * days / r.perPack!! - 1e-9).toInt().coerceAtLeast(1)
    }

    private fun fmt(d: Double): String =
        if (d == Math.floor(d)) d.toLong().toString() else String.format(java.util.Locale.GERMANY, "%.1f", d)

    private fun entries(): List<Planner.Entry> = Planner.split(items(), weeks)

    private fun saveChecked() = commit()

    private fun isDone(e: Planner.Entry) =
        e.covered || e.key in checked || (bought[e.key] ?: 0) >= e.qty

    private fun rowsNamed(name: String) = rows.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }

    // ---------- Aufbau ----------

    private fun render() {
        // Fokus merken, damit ein Neuaufbau (z. B. nach dem Abgleich) beim Tippen nicht stört
        val f = currentFocus as? EditText
        val fField = f?.tag as? String
        var fRow: String? = null
        var p = f?.parent
        while (p is View) { val tg = (p as View).tag; if (tg is String && tg.startsWith("row:")) { fRow = tg; break }; p = p.parent }
        val sel = f?.selectionStart ?: 0
        renderTabs()
        content.removeAllViews()
        if (tab == 0) {
            renderRows()
            updateMonthStats()
            content.addView(monthView, FrameLayout.LayoutParams(MATCH, MATCH))
            if (fRow != null && fField != null) {
                val target = rowsBox.findViewWithTag<View>(fRow)?.findViewWithTag<View>(fField) as? EditText
                target?.let { it.requestFocus(); it.setSelection(sel.coerceAtMost(it.text.length)) }
            }
        } else {
            hideKeyboard()
            content.addView(buildWeekView(tab), FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        val all = entries()
        for (i in 0..weeks) {
            val label: String
            val sub: String
            if (i == 0) {
                label = "Monat"
                sub = "${Planner.merge(items()).size}"
            } else {
                val wk = all.filter { it.week == i }
                val open = wk.count { !isDone(it) }
                label = "W$i"
                sub = if (wk.isNotEmpty() && open == 0) "✓" else "$open"
            }
            val selected = i == tab
            val t = TextView(this).apply {
                text = "$label\n$sub"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setLineSpacing(0f, 1.1f)
                setTextColor(if (selected) white else grey)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(0, dp(8), 0, dp(8))
                background = rounded(if (selected) blue else card, 12)
                setOnClickListener {
                    if (tab == 0) saveRows()
                    tab = i
                    prefs.edit().putInt("tab", tab).apply()
                    render()
                }
            }
            val lp = LinearLayout.LayoutParams(0, WRAP, if (i == 0) 1.4f else 1f)
            if (i > 0) lp.leftMargin = dp(6)
            tabBar.addView(t, lp)
        }
    }

    // ----- Monat -----

    private fun buildMonthView(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(24))
        }
        scroll.addView(col)

        col.addView(title("Was brauchen wir diesen Monat?"))
        monthSyncLine = syncLineView()
        col.addView(monthSyncLine)
        col.addView(small(
            "Menge und Artikel eintragen, die Menge wird auf die Wochen verteilt.\n" +
            "„da“ = schon zuhause (wird von Woche 1 abgezogen).\n" +
            "„Woche“ antippen = nur bestimmte Wochen, z. B. W3+4. Artikel leeren = löschen.\n" +
            "Menge lange drücken = Packungsrechner. ≡ gedrückt halten und ziehen = sortieren."
        ).apply { setPadding(0, dp(4), 0, dp(14)) })

        // Spaltenköpfe
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        head.addView(View(this), LinearLayout.LayoutParams(dp(HANDLE_W), 1))
        head.addView(colHead("Menge"), LinearLayout.LayoutParams(dp(QTY_W), WRAP))
        head.addView(colHead("Artikel"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(GAP) })
        head.addView(colHead("da"), LinearLayout.LayoutParams(dp(HAVE_W), WRAP).apply { leftMargin = dp(GAP) })
        head.addView(colHead("Woche"), LinearLayout.LayoutParams(dp(WEEK_W), WRAP).apply { leftMargin = dp(GAP) })
        col.addView(head)

        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(rowsBox)

        monthStats = small("").apply { setPadding(0, dp(12), 0, dp(4)) }
        col.addView(monthStats)

        // Wochenanzahl
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = "Aufteilen auf"
            setTextColor(grey)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        weekToggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(weekToggle)
        col.addView(row)
        renderWeekToggle()

        col.addView(button("Neuer Monat – alles leeren") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Neuen Monat anfangen?")
                .setMessage("Die Monatsliste und alle Haken werden gelöscht.")
                .setPositiveButton("Leeren") { _, _ ->
                    checked.clear(); bought.clear(); saveChecked()
                    rows.clear(); saveRows()
                    render()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(28)
        })

        return scroll
    }

    /** Baut die Zeilen neu auf; am Ende steht immer eine leere Zeile zum Weiterschreiben. */
    private fun renderRows() {
        rows.removeAll { it.name.isBlank() }
        rows += Row()
        rowsBox.removeAllViews()
        for (r in rows) rowsBox.addView(rowView(r))
    }

    private fun rowView(r: Row): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            tag = "row:" + r.id
        }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val calc = hasCalc(r)
        val qty = field(if (r.name.isBlank() && !calc) "" else qtyOf(r).toString(), "1", number = true).apply {
            tag = "qty"
            gravity = Gravity.CENTER
            imeOptions = EditorInfo.IME_ACTION_NEXT
            if (calc) setTextColor(blue)
            onChange { s ->
                val n = s.toIntOrNull()?.coerceAtLeast(1) ?: 1
                // Menge von Hand geändert -> Rechner für diese Zeile aus
                if (hasCalc(r) && n != qtyOf(r)) { r.perPack = null; r.perDay = null; r.days = null; setTextColor(white) }
                r.qty = n
                rowsChanged()
            }
            setOnLongClickListener { calcDialog(r); true }
        }
        val name = field(r.name, if (r.name.isBlank()) "Artikel…" else "", number = false).apply {
            tag = "name"
            imeOptions = EditorInfo.IME_ACTION_NEXT
            onChange { s ->
                r.name = s
                // In die letzte Zeile geschrieben -> neue leere Zeile anhängen
                if (s.isNotBlank() && rows.lastOrNull() === r) {
                    val n = Row(); rows += n; rowsBox.addView(rowView(n))
                }
                rowsChanged()
            }
        }
        val have = field(if (r.have > 0) r.have.toString() else "", "–", number = true).apply {
            tag = "have"
            gravity = Gravity.CENTER
            imeOptions = EditorInfo.IME_ACTION_NEXT
            onChange { s -> r.have = s.toIntOrNull()?.coerceAtLeast(0) ?: 0; rowsChanged() }
        }
        val week = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(card, 10)
        }
        fun paintWeek() {
            val sel = r.weeksSel.filter { it in 1..weeks }
            val auto = sel.isEmpty() || sel.size == weeks
            week.text = weeksLabel(r.weeksSel)
            week.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (week.text.length > 5) 12f else 14f)
            week.setTextColor(if (auto) dim else blue)
            week.typeface = if (auto) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        }
        paintWeek()
        week.setOnClickListener {
            weeksDialog(r.name.ifBlank { "Wochen" }, r.weeksSel) { sel ->
                r.weeksSel = sel
                paintWeek(); rowsChanged()
            }
        }
        // Nach dem Artikel direkt in die Menge der nächsten Zeile springen
        name.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_NEXT || id == EditorInfo.IME_ACTION_DONE) { focusNextRow(r); true } else false
        }
        have.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_NEXT || id == EditorInfo.IME_ACTION_DONE) { focusNextRow(r); true } else false
        }

        val handle = dragHandle(wrap, { children(rowsBox) }) { from, to ->
            if (from in rows.indices && to in rows.indices) {
                rows.add(to, rows.removeAt(from))
                saveRows()
                renderRows()
                rowsChanged()
            }
        }
        v.addView(handle, LinearLayout.LayoutParams(dp(HANDLE_W), dp(48)))
        v.addView(qty, LinearLayout.LayoutParams(dp(QTY_W), dp(48)))
        v.addView(name, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(GAP) })
        v.addView(have, LinearLayout.LayoutParams(dp(HAVE_W), dp(48)).apply { leftMargin = dp(GAP) })
        v.addView(week, LinearLayout.LayoutParams(dp(WEEK_W), dp(48)).apply { leftMargin = dp(GAP) })
        wrap.addView(v)

        if (calc) {
            val days = r.days ?: (weeks * 7)
            wrap.addView(TextView(this).apply {
                text = "${fmt(r.perDay!!)} pro Tag × $days Tage ÷ ${fmt(r.perPack!!)} pro Packung = ${qtyOf(r)} Packungen"
                setTextColor(grey)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(HANDLE_W + 4), 0, 0, dp(6))
                setOnClickListener { calcDialog(r) }
            })
        }
        return wrap
    }

    private fun children(box: LinearLayout): List<View> = (0 until box.childCount).map { box.getChildAt(it) }

    /**
     * Griff "≡" zum Umsortieren: gedrückt halten und ziehen. Die Nachbarn rutschen
     * beim Ziehen zur Seite; beim Loslassen wird [onDrop] (von, nach) aufgerufen.
     */
    private fun dragHandle(row: View, siblings: () -> List<View>, onDrop: (Int, Int) -> Unit): TextView {
        val h = TextView(this).apply {
            text = "≡"
            gravity = Gravity.CENTER
            setTextColor(dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
        var startY = 0f
        var from = -1
        var target = -1
        h.setOnTouchListener { v, ev ->
            val list = siblings()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    from = list.indexOf(row); target = from; startY = ev.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    hideKeyboard()
                    row.elevation = dp(8).toFloat(); row.alpha = 0.92f
                    h.setTextColor(blue)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (from >= 0) {
                        row.translationY = ev.rawY - startY
                        val center = row.top + row.translationY + row.height / 2f
                        var t = from
                        for (j in from + 1 until list.size) if (center > list[j].top + list[j].height / 2f) t = j
                        for (j in from - 1 downTo 0) if (center < list[j].top + list[j].height / 2f) t = j
                        target = t
                        for ((j, o) in list.withIndex()) {
                            if (o === row) continue
                            val shift = when {
                                j in (from + 1)..t -> -row.height.toFloat()
                                j in t until from -> row.height.toFloat()
                                else -> 0f
                            }
                            if (o.translationY != shift) o.animate().translationY(shift).setDuration(120).start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    for (o in list) { o.animate().cancel(); o.translationY = 0f }
                    row.elevation = 0f; row.alpha = 1f
                    h.setTextColor(dim)
                    val f = from; val t = target
                    from = -1
                    if (f >= 0 && t >= 0 && t != f) onDrop(f, t)
                    true
                }
                else -> false
            }
        }
        return h
    }

    /** Packungsrechner: Inhalt pro Packung + Verbrauch pro Tag -> Anzahl Packungen. */
    private fun calcDialog(r: Row) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun input(label: String, value: String, hintText: String, decimal: Boolean): EditText {
            box.addView(TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(10), 0, 0)
            })
            val e = EditText(this).apply {
                setText(value)
                hint = hintText
                inputType = InputType.TYPE_CLASS_NUMBER or
                    (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setSelectAllOnFocus(true)
            }
            box.addView(e)
            return e
        }
        val pack = input("Inhalt einer Packung (g, ml, Stück …)", r.perPack?.let { fmt(it) } ?: "", "z. B. 500", true)
        val day = input("Verbrauch pro Tag (gleiche Einheit)", r.perDay?.let { fmt(it) } ?: "", "z. B. 300", true)
        val days = input("Für wie viele Tage", (r.days ?: (weeks * 7)).toString(), "${weeks * 7}", false)
        val result = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(4))
        }
        box.addView(result)

        fun num(e: EditText) = e.text.toString().replace(',', '.').toDoubleOrNull()
        fun update() {
            val p = num(pack); val d = num(day); val t = num(days)
            result.text = if (p != null && d != null && t != null && p > 0 && d > 0 && t > 0) {
                val total = d * t
                val n = Math.ceil(total / p - 1e-9).toInt()
                "= $n Packungen  (${fmt(total)} gesamt)"
            } else "Packungsinhalt und Verbrauch eintragen"
        }
        for (e in listOf(pack, day, days)) e.onChange { update() }
        update()

        val b = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (r.name.isBlank()) "Packungsrechner" else "${r.name}: Packungsrechner")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Übernehmen") { _, _ ->
                val p = num(pack); val d = num(day)
                val t = num(days)?.toInt()
                if (p != null && d != null && p > 0 && d > 0) {
                    r.perPack = p; r.perDay = d
                    r.days = if (t == null || t <= 0 || t == weeks * 7) null else t
                    r.qty = qtyOf(r)
                    saveRows(); renderRows(); rowsChanged()
                }
            }
            .setNegativeButton("Abbrechen", null)
        if (hasCalc(r)) b.setNeutralButton("Rechner aus") { _, _ ->
            r.perPack = null; r.perDay = null; r.days = null
            saveRows(); renderRows(); rowsChanged()
        }
        val dialog = b.create()
        dialog.setOnShowListener {
            pack.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun focusNextRow(r: Row) {
        val i = rows.indexOf(r)
        if (i < 0) return
        if (i == rows.size - 1) { val n = Row(); rows += n; rowsBox.addView(rowView(n)) }
        val next = rowsBox.getChildAt(i + 1) ?: return
        next.findViewWithTag<View>("qty")?.requestFocus()
    }

    private fun rowsChanged() {
        lastType = System.currentTimeMillis()
        scheduleSave()
        updateMonthStats()
        renderTabs()
    }

    private fun field(value: String, hintText: String, number: Boolean) = EditText(this).apply {
        setText(value)
        hint = hintText
        setHintTextColor(dim)
        setTextColor(white)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isSingleLine = true
        inputType = if (number) InputType.TYPE_CLASS_NUMBER
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        background = rounded(card, 10)
        setPadding(dp(10), 0, dp(10), 0)
        setSelectAllOnFocus(number)
    }

    private fun EditText.onChange(block: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { block(s?.toString() ?: "") }
        })
    }

    private fun renderWeekToggle() {
        weekToggle.removeAllViews()
        for (n in 4..5) {
            val sel = n == weeks
            weekToggle.addView(TextView(this).apply {
                text = "$n Wochen"
                setTextColor(if (sel) white else grey)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = rounded(if (sel) blue else card, 10)
                setOnClickListener {
                    weeks = n
                    prefs.edit().putInt("weeks", n).apply()
                    if (tab > weeks) tab = weeks
                    renderWeekToggle()
                    saveRows()
                    render()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        }
    }

    private fun updateMonthStats() {
        val items = Planner.merge(items())
        if (items.isEmpty()) {
            monthStats.text = "Noch nichts eingetragen."
            return
        }
        val all = entries()
        val perWeek = (1..weeks).joinToString("  ·  ") { w -> "W$w: ${all.count { it.week == w && !it.covered }}" }
        monthStats.text = "${items.size} Artikel  →  $perWeek"
    }

    // ----- Woche -----

    private fun buildWeekView(week: Int): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(24))
        }
        scroll.addView(col)

        val wk = entries().filter { it.week == week }
        val open = wk.filter { !isDone(it) }
        val done = wk.filter { isDone(it) }

        col.addView(title("Woche $week"))
        weekSyncLine = syncLineView()
        col.addView(weekSyncLine)
        col.addView(small(
            if (wk.isEmpty()) "Noch nichts für diese Woche."
            else "${done.size} von ${wk.size} erledigt"
        ).apply { setPadding(0, dp(2), 0, dp(10)) })

        if (wk.isNotEmpty()) col.addView(progress(done.size, wk.size))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val openBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (e in open) {
            val rv = itemRow(e, false) as LinearLayout
            rv.setBackgroundColor(bg)
            rv.addView(dragHandle(rv, { children(openBox) }) { from, to ->
                moveRowsNear(open[from].name, open[to].name, after = to > from)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            openBox.addView(rv)
        }
        list.addView(openBox)
        if (done.isNotEmpty() && open.isNotEmpty()) {
            list.addView(View(this).apply { setBackgroundColor(line) },
                LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(6) })
        }
        for (e in done) list.addView(itemRow(e, true))
        col.addView(list)

        if (wk.isNotEmpty()) {
            col.addView(small("Lange drücken: teilweise gekauft, schon zuhause, andere Woche. ≡ ziehen = sortieren.")
                .apply { setPadding(0, dp(14), 0, 0); setTextColor(dim) })
        }

        // Nachtrag nur für diese Woche
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val addEdit = EditText(this).apply {
            hint = "Nachtrag, z. B. 3 Avocados"
            setHintTextColor(dim)
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(card, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val add = {
            val item = Planner.parseLine(addEdit.text.toString())
            if (item != null) {
                rows.removeAll { it.name.isBlank() }
                rows += Row(qty = item.qty, name = item.name, have = 0, weeksSel = setOf(week))
                saveRows()
                render()
            }
        }
        addEdit.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { add(); true } else false
        }
        addRow.addView(addEdit, LinearLayout.LayoutParams(0, WRAP, 1f))
        addRow.addView(TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            background = rounded(blue, 12)
            setOnClickListener { add() }
        }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(8) })
        col.addView(addRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(22) })

        return scroll
    }

    private fun itemRow(e: Planner.Entry, isDone: Boolean): View {
        val got = bought[e.key] ?: 0
        val partial = !isDone && got > 0
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            isClickable = true
            setOnClickListener {
                if (!e.covered) {
                    if (isDone) { checked.remove(e.key); bought.remove(e.key) }
                    else { checked.add(e.key); bought.remove(e.key) }
                    saveChecked()
                    render()
                }
            }
            setOnLongClickListener { actionDialog(e); true }
        }
        val box = TextView(this).apply {
            text = when { isDone -> "✓"; partial -> "½"; else -> "" }
            gravity = Gravity.CENTER
            setTextColor(if (partial) orange else bg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when {
                    isDone -> setColor(green)
                    partial -> { setColor(0); setStroke(dp(2), orange) }
                    else -> { setColor(0); setStroke(dp(2), grey) }
                }
            }
        }
        row.addView(box, LinearLayout.LayoutParams(dp(26), dp(26)))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            // bei Teilkauf nur noch den offenen Rest anzeigen
            text = if (partial) e.copy(qty = e.qty - got).label else e.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(if (isDone) dim else white)
            if (isDone) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        })
        val notes = listOfNotNull(
            if (partial) "noch offen · $got von ${e.qty} gekauft" else null,
            e.note,
        )
        if (notes.isNotEmpty()) texts.addView(TextView(this).apply {
            text = notes.joinToString("  ·  ")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (partial) orange else grey)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    private fun actionDialog(e: Planner.Entry) {
        val haveNow = rowsNamed(e.name).sumOf { it.have }
        val labels = mutableListOf<String>()
        val handlers = mutableListOf<() -> Unit>()
        fun act(label: String, f: () -> Unit) { labels.add(label); handlers.add(f) }
        if (e.qty > 1 && !e.covered) act("Nur teilweise gekauft …") { partialDialog(e) }
        act("Schon zuhause vorhanden …") { stockDialog(e, haveNow) }
        act("Wochen wählen …") {
            val cur = rowsNamed(e.name).firstOrNull { it.weeksSel.isNotEmpty() }?.weeksSel ?: emptySet()
            weeksDialog(e.name, cur) { sel -> moveTo(e, sel) }
        }
        act("Auf alle Wochen verteilen") { moveTo(e, emptySet()) }

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(e.name)
            .setItems(labels.toTypedArray()) { _, which -> handlers[which]() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Verschiebt alle Zeilen von Artikel [a] direkt vor bzw. hinter Artikel [b] (Reihenfolge überall). */
    private fun moveRowsNear(a: String, b: String, after: Boolean) {
        val moving = rowsNamed(a)
        if (moving.isEmpty()) return
        rows.removeAll { r -> moving.any { it === r } }
        val targets = rowsNamed(b)
        if (targets.isEmpty()) rows.addAll(moving)
        else {
            val idx = if (after) rows.indexOf(targets.last()) + 1 else rows.indexOf(targets.first())
            rows.addAll(idx, moving)
        }
        saveRows()
        render()
    }

    private fun moveTo(e: Planner.Entry, sel: Set<Int>) {
        for (r in rowsNamed(e.name)) r.weeksSel = sel
        saveRows()
        render()
    }

    /** "auto", "W3", "W1+2", "1+3+4" */
    private fun weeksLabel(sel: Set<Int>): String {
        val s = sel.filter { it in 1..weeks }.sorted()
        return when {
            s.isEmpty() || s.size == weeks -> "auto"
            s.size == 1 -> "W${s[0]}"
            s.size == 2 -> "W${s[0]}+${s[1]}"
            else -> s.joinToString("+")
        }
    }

    /** Mehrfachauswahl der Wochen; nichts oder alles angekreuzt = automatisch auf alle. */
    private fun weeksDialog(title: String, current: Set<Int>, onOk: (Set<Int>) -> Unit) {
        val labels = (1..weeks).map { "Woche $it" }.toTypedArray()
        val checkedArr = BooleanArray(weeks) { (it + 1) in current }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("$title: in welchen Wochen?")
            .setMultiChoiceItems(labels, checkedArr) { _, which, isChecked -> checkedArr[which] = isChecked }
            .setPositiveButton("OK") { _, _ ->
                val sel = (1..weeks).filter { checkedArr[it - 1] }.toSet()
                onOk(if (sel.size == weeks) emptySet() else sel)
            }
            .setNeutralButton("Alle") { _, _ -> onOk(emptySet()) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun partialDialog(e: Planner.Entry) {
        numberDialog(
            title = "${e.name}: wie viele gekauft?",
            message = "Von ${e.qty} für diese Woche. Der Rest bleibt offen.",
            initial = bought[e.key] ?: 0,
        ) { n ->
            checked.remove(e.key)
            when {
                n <= 0 -> bought.remove(e.key)
                n >= e.qty -> { bought.remove(e.key); checked.add(e.key) }
                else -> bought[e.key] = n
            }
            saveChecked()
            render()
        }
    }

    private fun stockDialog(e: Planner.Entry, current: Int) {
        numberDialog(
            title = "${e.name}: wie viele schon da?",
            message = "Wird zuerst von den frühen Wochen abgezogen. 0 = nichts vorhanden.",
            initial = current,
        ) { n ->
            rowsNamed(e.name).forEachIndexed { i, r -> r.have = if (i == 0) n else 0 }
            saveRows()
            render()
        }
    }

    private fun numberDialog(title: String, message: String, initial: Int, onOk: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (initial > 0) initial.toString() else "")
            setSelection(text.length)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setView(wrap)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().toIntOrNull() ?: 0) }
            .setNegativeButton("Abbrechen", null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    // ---------- Bausteine ----------

    private fun title(s: String) = TextView(this).apply {
        text = s
        setTextColor(white)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, 0)
    }

    private fun small(s: String) = TextView(this).apply {
        text = s
        setTextColor(grey)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun colHead(s: String) = TextView(this).apply {
        text = s.uppercase()
        setTextColor(dim)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.08f
        gravity = if (s == "Artikel") Gravity.START else Gravity.CENTER
        if (s == "Artikel") setPadding(dp(10), 0, 0, 0)
    }

    private fun progress(done: Int, total: Int): View {
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(card, 6)
        }
        val fill = View(this).apply { background = rounded(if (done == total) green else blue, 6) }
        track.addView(fill, LinearLayout.LayoutParams(0, MATCH, done.toFloat()))
        track.addView(View(this), LinearLayout.LayoutParams(0, MATCH, (total - done).toFloat()))
        track.layoutParams = LinearLayout.LayoutParams(MATCH, dp(8))
        return track
    }

    private fun button(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s
        gravity = Gravity.CENTER
        setTextColor(grey)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(0); setStroke(dp(1), line)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val QTY_W = 52
        private const val HAVE_W = 50
        private const val WEEK_W = 54
        private const val GAP = 6
        private const val HANDLE_W = 26
    }
}
