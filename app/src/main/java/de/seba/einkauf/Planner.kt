package de.seba.einkauf

/**
 * Macht aus der Monatsliste (eine Zeile pro Artikel) die Wochenlisten.
 *
 * Schreibweise einer Zeile:
 *   Milch            -> 1x, landet in der Woche mit den wenigsten Artikeln
 *   60 Bananen       -> 60 Stück, gleichmäßig auf alle Wochen verteilt (15/15/15/15)
 *   8x Milch         -> dasselbe mit "x" (2/2/2/2), auch "Milch 8x" oder "Milch 8"
 *   Klopapier @3     -> fest in Woche 3 (auch mit Menge: "8x Milch @1" = alles in Woche 1)
 */
object Planner {

    data class Item(val name: String, val qty: Int, val fixedWeek: Int?, val mult: Boolean = false)

    data class Entry(val week: Int, val name: String, val qty: Int, val mult: Boolean = false) {
        val key: String get() = key(week, name)
        /** "15 Bananen" bzw. "2× Milch" (wenn mit x geschrieben) */
        val label: String get() = when {
            qty <= 1 && !mult -> name
            mult -> "$qty× $name"
            else -> "$qty $name"
        }
    }

    fun key(week: Int, name: String) = "$week|${name.trim().lowercase()}"

    private val weekTag = Regex("""\s*@\s*(\d+)\s*$""")
    // Zahl am Anfang = Menge: "60 Bananen", "8x Milch", "8 x Milch"
    private val prefixQty = Regex("""^(\d+)(?:\s*([xX×*])\s*|\s+)(.+)$""")
    // Zahl am Ende = Menge: "Milch 8x", "Milch x8", "Bananen 60"
    private val suffixQty = Regex("""^(.+?)\s+(?:(\d+)\s*([xX×*])|([xX×*])\s*(\d+)|(\d+))$""")
    private val bullet = Regex("""^[-•*·]\s+""")

    fun parseLine(raw: String): Item? {
        var s = raw.trim().replace(bullet, "").trim()
        if (s.isEmpty()) return null

        var fixed: Int? = null
        weekTag.find(s)?.let { m ->
            fixed = m.groupValues[1].toIntOrNull()
            s = s.substring(0, m.range.first).trim()
        }

        var qty = 1
        var mult = false
        val p = prefixQty.matchEntire(s)
        if (p != null) {
            qty = p.groupValues[1].toIntOrNull() ?: 1
            mult = p.groupValues[2].isNotEmpty()
            s = p.groupValues[3].trim()
        } else {
            val q = suffixQty.matchEntire(s)
            if (q != null) {
                val g = q.groupValues
                qty = listOf(g[2], g[5], g[6]).first { it.isNotEmpty() }.toIntOrNull() ?: 1
                mult = g[3].isNotEmpty() || g[4].isNotEmpty()
                s = g[1].trim()
            }
        }
        if (s.isEmpty()) return null
        return Item(s, qty.coerceIn(1, 9999), fixed, mult)
    }

    /** Liest alle Zeilen; gleiche Artikel (Groß/klein egal) werden zusammengezählt. */
    fun parse(text: String): List<Item> {
        val out = LinkedHashMap<String, Item>()
        for (line in text.lines()) {
            val it = parseLine(line) ?: continue
            val k = it.name.lowercase()
            val prev = out[k]
            out[k] = if (prev == null) it
            else prev.copy(qty = prev.qty + it.qty, fixedWeek = it.fixedWeek ?: prev.fixedWeek, mult = prev.mult || it.mult)
        }
        return out.values.toList()
    }

    /** Verteilt die Artikel auf [weeks] Wochen (1-basiert in den Einträgen). */
    fun split(items: List<Item>, weeks: Int): List<Entry> {
        val load = IntArray(weeks)
        val out = ArrayList<Entry>()

        fun leastLoaded(): Int {
            var best = 0
            for (w in 1 until weeks) if (load[w] < load[best]) best = w
            return best
        }

        for (item in items) {
            val fixed = item.fixedWeek
            when {
                fixed != null -> {
                    val w = (fixed - 1).coerceIn(0, weeks - 1)
                    out += Entry(w + 1, item.name, item.qty, item.mult)
                    load[w]++
                }
                item.qty == 1 -> {
                    val w = leastLoaded()
                    out += Entry(w + 1, item.name, 1, item.mult)
                    load[w]++
                }
                else -> {
                    val per = item.qty / weeks
                    val rem = item.qty % weeks
                    val share = IntArray(weeks) { per }
                    // Rest gleichmäßig verteilt, z. B. 2 Stück in 4 Wochen -> Woche 1 und 3
                    for (i in 0 until rem) share[i * weeks / rem]++
                    for (w in 0 until weeks) if (share[w] > 0) {
                        out += Entry(w + 1, item.name, share[w], item.mult)
                        load[w]++
                    }
                }
            }
        }
        return out
    }

    /**
     * Setzt (oder entfernt bei week = null) die feste Woche für einen Artikel,
     * indem die passenden Zeilen der Monatsliste umgeschrieben werden.
     */
    fun setFixedWeek(text: String, name: String, week: Int?): String {
        val target = name.trim().lowercase()
        return text.lines().joinToString("\n") { line ->
            val item = parseLine(line)
            if (item == null || item.name.lowercase() != target) line
            else {
                val base = line.replace(weekTag, "").trimEnd()
                if (week == null) base else "$base @$week"
            }
        }
    }
}
