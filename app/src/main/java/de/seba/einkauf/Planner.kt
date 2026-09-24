package de.seba.einkauf

/**
 * Verteilt die Monatsliste auf Wochenlisten.
 *
 * Jede Zeile der Monatsliste hat eigene Felder: Menge, Artikel, schon da, Woche.
 *   60 Bananen                -> 15 / 15 / 15 / 15
 *   60 Bananen, 4 schon da    -> 11 / 15 / 15 / 15 (Vorrat wird von vorne abgezogen)
 *   1 Müllbeutel              -> in die Woche mit den wenigsten Artikeln
 *   Wochen W3+W4              -> nur auf Woche 3 und 4 verteilt
 */
object Planner {

    data class Item(
        val name: String,
        val qty: Int,
        val onlyWeeks: Set<Int> = emptySet(), // leer = alle Wochen (automatisch)
        val have: Int = 0,          // schon vorhanden
    )

    data class Entry(
        val week: Int,
        val name: String,
        val qty: Int,               // was in dieser Woche noch gekauft werden muss
        val showQty: Boolean,
        val have: Int = 0,          // davon in dieser Woche schon vorhanden (abgezogen)
        val covered: Boolean = false, // komplett durch Vorrat gedeckt
    ) {
        val key: String get() = key(week, name)
        val label: String get() = if (showQty) "$qty $name" else name
        val note: String? get() = when {
            covered -> "alles schon da"
            have > 0 -> "$have schon da"
            else -> null
        }
    }

    fun key(week: Int, name: String) = "$week|${name.trim().lowercase()}"

    /** Macht aus exotischen Leerzeichen (z. B. aus Notiz-Apps) normale. */
    fun normalize(s: String): String =
        s.replace(Regex("[\\p{Z}\\t\\u200B\\u200C\\u200D\\u2060\\uFEFF]+"), " ").trim()

    private val bullet = Regex("""^[\p{Pd}•*·▪◦‣]+\s*""")
    private val weekTag = Regex("""\s*@\s*(\d+)\s*$""")
    private val stockTag = Regex("""\s*[,;(]?\s*(\d+)\s*(?:schon\s+)?(?:da|vorhanden)\s*\)?\s*$""", RegexOption.IGNORE_CASE)
    private val prefixQty = Regex("""^(\d+)(?:\s*[xX×*]\s*|\s+)(\S.*)$""")
    private val suffixQty = Regex("""^(.+?)\s+(?:(\d+)\s*[xX×*]?|[xX×*]\s*(\d+))$""")

    /** Liest eine freie Textzeile wie "60 Bananen" (für Nachträge und die alte Textliste). */
    fun parseLine(raw: String): Item? {
        var s = normalize(raw).replace(bullet, "").trim()
        if (s.isEmpty()) return null
        var week: Int? = null
        var have = 0
        repeat(2) { // "@3" und "4 da" aus der alten Textliste
            weekTag.find(s)?.let { m -> week = m.groupValues[1].toIntOrNull(); s = s.substring(0, m.range.first).trim() }
            stockTag.find(s)?.let { m -> have = m.groupValues[1].toIntOrNull() ?: 0; s = s.substring(0, m.range.first).trim() }
        }
        var qty = 1
        val p = prefixQty.matchEntire(s)
        if (p != null) {
            qty = p.groupValues[1].toIntOrNull() ?: 1
            s = p.groupValues[2].trim()
        } else {
            val q = suffixQty.matchEntire(s)
            if (q != null) {
                qty = q.groupValues[2].ifEmpty { q.groupValues[3] }.toIntOrNull() ?: 1
                s = q.groupValues[1].trim()
            }
        }
        if (s.isEmpty()) return null
        val w = week
        return Item(s, qty.coerceIn(1, 9999), if (w != null) setOf(w) else emptySet(), have)
    }

    /** Gleiche Artikel (Groß/klein egal) werden zusammengezählt. */
    fun merge(items: List<Item>): List<Item> {
        val out = LinkedHashMap<String, Item>()
        for (it in items) {
            val name = normalize(it.name)
            if (name.isEmpty()) continue
            val k = name.lowercase()
            val prev = out[k]
            out[k] = if (prev == null) it.copy(name = name)
            else prev.copy(
                qty = prev.qty + it.qty,
                onlyWeeks = it.onlyWeeks.ifEmpty { prev.onlyWeeks },
                have = prev.have + it.have,
            )
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

        for (item in merge(items)) {
            val share = IntArray(weeks)
            // erlaubte Wochen (0-basiert); leer oder ungültig = alle
            val allowed = item.onlyWeeks.map { it - 1 }.filter { it in 0 until weeks }.sorted()
                .ifEmpty { (0 until weeks).toList() }
            val n = allowed.size
            if (item.qty <= 1) {
                // einzelner Artikel: in die erlaubte Woche mit den wenigsten Artikeln
                share[allowed.minByOrNull { load[it] } ?: leastLoaded()] = 1
            } else {
                val per = item.qty / n
                val rem = item.qty % n
                for (w in allowed) share[w] = per
                // Rest gleichmäßig verteilt, z. B. 2 Stück in 4 Wochen -> Woche 1 und 3
                for (i in 0 until rem) share[allowed[i * n / rem]]++
            }
            // Vorrat zuerst von den frühen Wochen abziehen
            var stock = item.have
            val showQty = item.qty > 1
            for (w in 0 until weeks) {
                if (share[w] == 0) continue
                val used = minOf(stock, share[w])
                stock -= used
                val need = share[w] - used
                out += if (need > 0) Entry(w + 1, item.name, need, showQty, used)
                else Entry(w + 1, item.name, share[w], showQty, used, covered = true)
                load[w]++
            }
        }
        return out
    }
}
