package com.guardian.app

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * v1.6: blocked-per-day history for the 7-day chart and the widget's
 * "blocked today". Only day totals are kept (no domains, no apps) — the
 * last 35 days, on-device, in SharedPreferences like every other stat.
 */
object DailyStats {
    private const val PREFS = "guardian_daily"
    private const val KEY = "days"
    private const val KEEP_DAYS = 35

    private val counts = ConcurrentHashMap<Int, AtomicLong>()
    @Volatile private var loaded = false
    @Volatile private var dayKey = 0
    @Volatile private var dayEnds = 0L

    /** Called on every block (hot path): a cached day key, one atomic add. */
    fun recordBlock() {
        val now = System.currentTimeMillis()
        if (now >= dayEnds) rollDay(now)
        counts.computeIfAbsent(dayKey) { AtomicLong() }.incrementAndGet()
    }

    @Synchronized private fun rollDay(now: Long) {
        if (now < dayEnds) return
        val c = Calendar.getInstance().apply { timeInMillis = now }
        dayKey = keyOf(c)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, 1)
        dayEnds = c.timeInMillis
    }

    fun today(): Long {
        val c = Calendar.getInstance()
        return counts[keyOf(c)]?.get() ?: 0L
    }

    /** Oldest -> newest: (short weekday label, blocked) for the last [n] days. */
    fun lastDays(n: Int = 7): List<Pair<String, Long>> {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -(n - 1))
        val fmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        return List(n) {
            val p = fmt.format(c.time) to (counts[keyOf(c)]?.get() ?: 0L)
            c.add(Calendar.DAY_OF_YEAR, 1)
            p
        }
    }

    /** Read from disk ONCE per process: the in-memory map is the live truth
     *  (re-reading while the service runs would drop unsaved counts). */
    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val o = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    k.toIntOrNull()?.let { day -> counts.getOrPut(day) { AtomicLong() }.addAndGet(o.getLong(k)) }
                }
            } catch (_: Exception) {}
            loaded = true
        }
    }

    fun save(ctx: Context) {
        val cutoff = keyOf(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -KEEP_DAYS) })
        counts.keys.filter { it < cutoff }.forEach { counts.remove(it) }
        val o = JSONObject()
        for ((k, v) in counts) o.put(k.toString(), v.get())
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    /** 2026-09-30 -> 20260930 (sorts and compares naturally). */
    private fun keyOf(c: Calendar) =
        c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}
