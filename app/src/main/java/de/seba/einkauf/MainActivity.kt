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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Einkauf: Monatsliste blind runterschreiben -> automatisch auf Wochenlisten
 * verteilt -> im Laden abhaken. Alles bleibt lokal auf dem Handy gespeichert.
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

    private val prefs by lazy { getSharedPreferences("einkauf", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    // Zustand
    private var monthText = ""
    private var weeks = 4
    private var checked = mutableSetOf<String>()
    private var bought = mutableMapOf<String, Int>() // teilweise gekauft: key -> Anzahl
    private var tab = 0 // 0 = Monat, 1..weeks = Woche

    // Views
    private lateinit var tabBar: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var monthView: View
    private lateinit var monthEdit: EditText
    private lateinit var monthStats: TextView
    private lateinit var weekToggle: LinearLayout

    private val saveText = Runnable { prefs.edit().putString("text", monthText).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        monthText = prefs.getString("text", "") ?: ""
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
        handler.removeCallbacks(saveText)
        saveText.run()
    }

    // ---------- Daten ----------

    private fun entries(): List<Planner.Entry> = Planner.split(Planner.parse(monthText), weeks)

    private fun saveChecked() = prefs.edit()
        .putStringSet("checked", HashSet(checked))
        .putStringSet("bought", bought.map { "${it.value}|${it.key}" }.toHashSet())
        .apply()

    private fun isDone(e: Planner.Entry) = e.key in checked || (bought[e.key] ?: 0) >= e.qty

    private fun setMonthText(newText: String, updateEditor: Boolean) {
        monthText = newText
        handler.removeCallbacks(saveText)
        saveText.run()
        if (updateEditor && monthEdit.text.toString() != newText) {
            monthEdit.setText(newText)
        }
    }

    // ---------- Aufbau ----------

    private fun render() {
        renderTabs()
        content.removeAllViews()
        if (tab == 0) {
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
                sub = "${Planner.parse(monthText).size}"
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

    private fun buildMonthView(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(24))
        }
        scroll.addView(col)

        col.addView(title("Was brauchen wir diesen Monat?"))
        col.addView(small(
            "Einfach runterschreiben, eine Zeile pro Artikel.\n" +
            "60 Bananen → 15 pro Woche  ·  60 Bananen, 4 da → Woche 1 nur 11\nGrillkohle @3 → fest in Woche 3"
        ).apply { setPadding(0, dp(4), 0, dp(12)) })

        monthEdit = EditText(this).apply {
            setText(monthText)
            hint = "60 Bananen\n8 Milch\n4 Brot\nKlopapier\nWaschmittel\n2 Kaffee\n…"
            setHintTextColor(dim)
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setLineSpacing(dp(4).toFloat(), 1f)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 12
            background = rounded(card, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val txt = s?.toString() ?: ""
                    if (txt == monthText) return
                    monthText = txt
                    handler.removeCallbacks(saveText)
                    handler.postDelayed(saveText, 600)
                    updateMonthStats()
                    renderTabs()
                }
            })
        }
        col.addView(monthEdit, LinearLayout.LayoutParams(MATCH, WRAP))

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

        // Neuer Monat
        col.addView(button("Neuer Monat – alles leeren", outline = true) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Neuen Monat anfangen?")
                .setMessage("Die Monatsliste und alle Haken werden gelöscht.")
                .setPositiveButton("Leeren") { _, _ ->
                    checked.clear(); bought.clear(); saveChecked()
                    setMonthText("", updateEditor = true)
                    render()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(28)
        })

        return scroll
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
                    render()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        }
    }

    private fun updateMonthStats() {
        val items = Planner.parse(monthText)
        if (items.isEmpty()) {
            monthStats.text = "Noch nichts eingetragen."
            return
        }
        val all = entries()
        val perWeek = (1..weeks).joinToString("  ·  ") { w -> "W$w: ${all.count { it.week == w }}" }
        monthStats.text = "${items.size} Artikel  →  $perWeek"
    }

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
            hint = "Nachtrag für Woche $week"
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
            val txt = addEdit.text.toString().trim()
            if (txt.isNotEmpty()) {
                val lineText = txt.replace(Regex("""\s*@\s*\d+\s*$"""), "") + " @$week"
                val base = monthText.trimEnd()
                setMonthText(if (base.isEmpty()) lineText else "$base\n$lineText", updateEditor = true)
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
                if (isDone) { checked.remove(e.key); bought.remove(e.key) }
                else { checked.add(e.key); bought.remove(e.key) }
                saveChecked()
                render()
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
        val item = Planner.parse(monthText).firstOrNull { it.name.equals(e.name, ignoreCase = true) }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (e.qty > 1) actions += "Nur teilweise gekauft …" to { partialDialog(e) }
        actions += "Schon zuhause vorhanden …" to { stockDialog(e, item?.have ?: 0) }
        for (w in 1..weeks) actions += "Alles in Woche $w" to { moveTo(e, w) }
        actions += "Automatisch verteilen" to { moveTo(e, null) }

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(e.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun moveTo(e: Planner.Entry, target: Int?) {
        val wasChecked = e.key in checked
        setMonthText(Planner.setFixedWeek(monthText, e.name, target), updateEditor = true)
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
            setMonthText(Planner.setStock(monthText, e.name, n), updateEditor = true)
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
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
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

    private fun button(s: String, outline: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = s
        gravity = Gravity.CENTER
        setTextColor(if (outline) grey else white)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (outline) { setColor(0); setStroke(dp(1), line) } else setColor(blue)
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
    }
}
