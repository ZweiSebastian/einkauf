package de.seba.einkauf

import android.app.AlertDialog
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
    private class Row(var qty: Int = 1, var name: String = "", var have: Int = 0, var week: Int? = null)

    private val prefs by lazy { getSharedPreferences("einkauf", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    // Zustand
    private val rows = mutableListOf<Row>()
    private var weeks = 4
    private var checked = mutableSetOf<String>()
    private var bought = mutableMapOf<String, Int>() // teilweise gekauft: key -> Anzahl
    private var tab = 0 // 0 = Monat, 1..weeks = Woche

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

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(saveRowsTask)
        saveRows()
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
                        week = if (o.has("week")) o.optInt("week") else null,
                    )
                }
            } catch (e: Exception) { rows.clear() }
        } else {
            // Übernahme der alten Textliste aus Version 1.x
            for (l in (prefs.getString("text", "") ?: "").lines()) {
                val it = Planner.parseLine(l) ?: continue
                rows += Row(it.qty, it.name, it.have, it.fixedWeek)
            }
        }
        rows.removeAll { it.name.isBlank() }
    }

    private fun saveRows() {
        val arr = JSONArray()
        for (r in rows) {
            if (r.name.isBlank()) continue
            arr.put(JSONObject().apply {
                put("qty", r.qty); put("name", r.name.trim()); put("have", r.have)
                r.week?.let { put("week", it) }
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun scheduleSave() {
        handler.removeCallbacks(saveRowsTask)
        handler.postDelayed(saveRowsTask, 500)
    }

    private fun items(): List<Planner.Item> =
        rows.filter { it.name.isNotBlank() }.map { Planner.Item(it.name, it.qty, it.week, it.have) }

    private fun entries(): List<Planner.Entry> = Planner.split(items(), weeks)

    private fun saveChecked() = prefs.edit()
        .putStringSet("checked", HashSet(checked))
        .putStringSet("bought", bought.map { "${it.value}|${it.key}" }.toHashSet())
        .apply()

    private fun isDone(e: Planner.Entry) =
        e.covered || e.key in checked || (bought[e.key] ?: 0) >= e.qty

    private fun rowsNamed(name: String) = rows.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }

    // ---------- Aufbau ----------

    private fun render() {
        renderTabs()
        content.removeAllViews()
        if (tab == 0) {
            renderRows()
            updateMonthStats()
            content.addView(monthView, FrameLayout.LayoutParams(MATCH, MATCH))
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
        col.addView(small(
            "Menge und Artikel eintragen, die Menge wird auf die Wochen verteilt.\n" +
            "„da“ = schon zuhause (wird von Woche 1 abgezogen).\n" +
            "„Woche“ antippen = alles in eine feste Woche. Artikel leeren = löschen."
        ).apply { setPadding(0, dp(4), 0, dp(14)) })

        // Spaltenköpfe
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
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
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val qty = field(if (r.name.isBlank()) "" else r.qty.toString(), "1", number = true).apply {
            gravity = Gravity.CENTER
            imeOptions = EditorInfo.IME_ACTION_NEXT
            onChange { s -> r.qty = s.toIntOrNull()?.coerceAtLeast(1) ?: 1; rowsChanged() }
        }
        val name = field(r.name, if (r.name.isBlank()) "Artikel…" else "", number = false).apply {
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
            val w = r.week
            week.text = if (w == null) "auto" else "W$w"
            week.setTextColor(if (w == null) dim else blue)
            week.typeface = if (w == null) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        }
        paintWeek()
        week.setOnClickListener {
            val w = r.week
            r.week = when {
                w == null -> 1
                w >= weeks -> null
                else -> w + 1
            }
            paintWeek(); rowsChanged()
        }
        // Nach dem Artikel direkt in die Menge der nächsten Zeile springen
        name.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_NEXT || id == EditorInfo.IME_ACTION_DONE) { focusNextRow(r); true } else false
        }
        have.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_NEXT || id == EditorInfo.IME_ACTION_DONE) { focusNextRow(r); true } else false
        }

        v.addView(qty, LinearLayout.LayoutParams(dp(QTY_W), dp(48)))
        v.addView(name, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(GAP) })
        v.addView(have, LinearLayout.LayoutParams(dp(HAVE_W), dp(48)).apply { leftMargin = dp(GAP) })
        v.addView(week, LinearLayout.LayoutParams(dp(WEEK_W), dp(48)).apply { leftMargin = dp(GAP) })
        return v
    }

    private fun focusNextRow(r: Row) {
        val i = rows.indexOf(r)
        if (i < 0) return
        if (i == rows.size - 1) { val n = Row(); rows += n; rowsBox.addView(rowView(n)) }
        val next = rowsBox.getChildAt(i + 1) as? LinearLayout ?: return
        next.getChildAt(0)?.requestFocus()
    }

    private fun rowsChanged() {
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
        col.addView(small(
            if (wk.isEmpty()) "Noch nichts für diese Woche."
            else "${done.size} von ${wk.size} erledigt"
        ).apply { setPadding(0, dp(2), 0, dp(10)) })

        if (wk.isNotEmpty()) col.addView(progress(done.size, wk.size))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        for (e in open) list.addView(itemRow(e, false))
        if (done.isNotEmpty() && open.isNotEmpty()) {
            list.addView(View(this).apply { setBackgroundColor(line) },
                LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(6) })
        }
        for (e in done) list.addView(itemRow(e, true))
        col.addView(list)

        if (wk.isNotEmpty()) {
            col.addView(small("Lange drücken: nur teilweise gekauft, schon zuhause vorhanden oder in andere Woche schieben.")
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
                rows += Row(item.qty, item.name, 0, week)
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
        for (w in 1..weeks) act("Alles in Woche $w") { moveTo(e, w) }
        act("Automatisch verteilen") { moveTo(e, null) }

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(e.name)
            .setItems(labels.toTypedArray()) { _, which -> handlers[which]() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun moveTo(e: Planner.Entry, target: Int?) {
        val wasChecked = e.key in checked
        for (r in rowsNamed(e.name)) r.week = target
        saveRows()
        if (wasChecked && target != null) {
            checked.add(Planner.key(target, e.name)); saveChecked()
        }
        render()
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
        private const val QTY_W = 58
        private const val HAVE_W = 50
        private const val WEEK_W = 54
        private const val GAP = 6
    }
}
