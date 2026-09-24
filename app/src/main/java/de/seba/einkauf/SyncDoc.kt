package de.seba.einkauf

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemeinsames Datenmodell für den Abgleich (muss zu core.js der Web-App passen).
 *
 * doc = { v:1,
 *   items:   { id: {name, qty, have, weeks:[..], perPack, perDay, days, del, t} },
 *   order:   { ids:[..], t },
 *   checked: { key: {v:bool, t} },
 *   bought:  { key: {n:int, t} },
 *   weeks:   { v:4|5, t } }
 * Jeder Eintrag trägt seinen Änderungszeitpunkt t; beim Zusammenführen gewinnt der neuere.
 */
object SyncDoc {

    private const val KEEP_TOMBSTONES_MS = 60L * 24 * 3600 * 1000

    fun empty(): JSONObject = JSONObject()
        .put("v", 1)
        .put("items", JSONObject())
        .put("order", JSONObject().put("ids", JSONArray()).put("t", 0))
        .put("checked", JSONObject())
        .put("bought", JSONObject())
        .put("weeks", JSONObject().put("v", 4).put("t", 0))

    fun copy(o: JSONObject): JSONObject = JSONObject(o.toString())

    /** Schlüssel sortiert, Zahlen einheitlich – zum Vergleichen. */
    fun canon(x: Any?): String = when (x) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> x.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(x.opt(it)) }
        is JSONArray -> (0 until x.length()).joinToString(",", "[", "]") { canon(x.opt(it)) }
        is String -> JSONObject.quote(x)
        is Boolean -> x.toString()
        is Number -> {
            val d = x.toDouble()
            if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString()
            else (Math.round(d * 1e6) / 1e6).toString()
        }
        else -> JSONObject.quote(x.toString())
    }

    private fun t(o: JSONObject?): Long = o?.optLong("t", 0L) ?: 0L

    private fun newer(a: JSONObject?, b: JSONObject?): JSONObject? {
        if (a == null) return b
        if (b == null) return a
        if (t(a) != t(b)) return if (t(a) > t(b)) a else b
        return if (canon(a) >= canon(b)) a else b
    }

    private fun mergeMap(a: JSONObject?, b: JSONObject?): JSONObject {
        val out = JSONObject()
        val keys = mutableSetOf<String>()
        a?.keys()?.forEach { keys += it }
        b?.keys()?.forEach { keys += it }
        for (k in keys) newer(a?.optJSONObject(k), b?.optJSONObject(k))?.let { out.put(k, it) }
        return out
    }

    fun merge(a0: JSONObject?, b0: JSONObject?, now: Long = System.currentTimeMillis()): JSONObject {
        val a = a0 ?: empty()
        val b = b0 ?: empty()
        val items = mergeMap(a.optJSONObject("items"), b.optJSONObject("items"))
        for (id in items.keys().asSequence().toList()) {
            val it = items.getJSONObject(id)
            if (it.optBoolean("del") && now - t(it) > KEEP_TOMBSTONES_MS) items.remove(id)
        }
        val order = newer(a.optJSONObject("order"), b.optJSONObject("order"))
        val ids = normalizeOrder(order?.optJSONArray("ids"), items)
        return JSONObject()
            .put("v", 1)
            .put("items", items)
            .put("order", JSONObject().put("ids", JSONArray(ids)).put("t", t(order)))
            .put("checked", mergeMap(a.optJSONObject("checked"), b.optJSONObject("checked")))
            .put("bought", mergeMap(a.optJSONObject("bought"), b.optJSONObject("bought")))
            .put("weeks", newer(a.optJSONObject("weeks"), b.optJSONObject("weeks")) ?: JSONObject().put("v", 4).put("t", 0))
    }

    /** Reihenfolge: nur lebende Artikel; fehlende hinten anhängen (nach Zeit, dann id). */
    fun normalizeOrder(ids: JSONArray?, items: JSONObject): List<String> {
        fun alive(id: String) = items.optJSONObject(id)?.let { !it.optBoolean("del") } ?: false
        val seen = LinkedHashSet<String>()
        if (ids != null) for (i in 0 until ids.length()) {
            val id = ids.optString(i)
            if (alive(id)) seen += id
        }
        val missing = items.keys().asSequence().filter { alive(it) && it !in seen }.toList()
            .sortedWith(compareBy<String>({ t(items.optJSONObject(it)) }, { it }))
        return seen.toList() + missing
    }

    // ---------- Verbindungs-Code "EK1-…" ----------

    data class Config(val url: String, val key: String, val code: String)

    fun encodeConnect(c: Config): String {
        val json = JSONObject().put("u", c.url).put("k", c.key).put("c", c.code).toString()
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "EK1-$b64"
    }

    fun decodeConnect(s: String): Config? {
        val m = Regex("EK1-([A-Za-z0-9_-]+)").find(s) ?: return null
        return try {
            val json = String(Base64.decode(m.groupValues[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            val o = JSONObject(json)
            val u = o.optString("u").trimEnd('/')
            val k = o.optString("k")
            val c = o.optString("c")
            if (u.isEmpty() || k.isEmpty() || c.isEmpty()) null else Config(u, k, c)
        } catch (e: Exception) { null }
    }

    // ---------- Supabase ----------

    class Remote(private val cfg: Config) {
        private fun rpc(fn: String, body: JSONObject): JSONArray {
            val con = URL(cfg.url + "/rest/v1/rpc/" + fn).openConnection() as HttpURLConnection
            try {
                con.requestMethod = "POST"
                con.connectTimeout = 12000
                con.readTimeout = 12000
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json")
                con.setRequestProperty("apikey", cfg.key)
                if (cfg.key.startsWith("eyJ")) con.setRequestProperty("Authorization", "Bearer " + cfg.key)
                con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = con.responseCode
                val stream = if (code in 200..299) con.inputStream else con.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) throw RuntimeException("Server $code: ${text.take(200)}")
                return JSONArray(text.ifBlank { "[]" })
            } finally {
                con.disconnect()
            }
        }

        /** (Daten, Revision) – Daten null, wenn es die Liste noch nicht gibt. */
        fun get(): Pair<JSONObject?, Long> {
            val r = rpc("get_list", JSONObject().put("p_code", cfg.code)).optJSONObject(0)
                ?: return Pair(null, 0L)
            return Pair(r.optJSONObject("out_data"), r.optLong("out_rev", 0L))
        }

        /** (ok, Daten, Revision) */
        fun put(data: JSONObject, baseRev: Long): Triple<Boolean, JSONObject?, Long> {
            val r = rpc("put_list", JSONObject().put("p_code", cfg.code).put("p_data", data).put("p_base_rev", baseRev))
                .optJSONObject(0) ?: return Triple(true, data, baseRev)
            return Triple(r.optBoolean("out_ok"), r.optJSONObject("out_data"), r.optLong("out_rev", 0L))
        }
    }
}
