package com.guardian.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * v1.14 parent lock — OFF by default (More settings → Parent lock).
 *
 * On a child's phone, tablet or the TV, a parent sets a PIN. From then on,
 * turning protection off, pausing it, letting an app bypass Noxa, allowing a
 * blocked site, or changing the home-server settings asks for that PIN. The
 * Quick Settings tile, the widget and the notification can no longer switch
 * protection off (they open the app, which asks). Blocking MORE never needs
 * the PIN.
 *
 * The PIN is kept only as a salted slow hash (PBKDF2) on the device. Five wrong
 * tries lock the dialog for a minute, then longer each time; changing the
 * clock doesn't shorten the wait. Honest limit, also in the docs: Android lets
 * anyone uninstall an app — if that happens, a home server notices the
 * silence (see HomeReport).
 */
object ParentLock {
    const val PREFS = "guardian_lock"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_FAILS = "fails"
    private const val KEY_LOCKED_UNTIL = "locked_until"
    // v1.16 (security review of 19 Nov 2026): a slow hash, and a lockout that grows and can't be skipped by
    // changing the clock (it runs on the time since boot; a reboot starts the wait again).
    private const val KEY_ALGO = "pin_algo"                 // "" = the old single SHA-256, "pbkdf2" = the slow form
    private const val KEY_LEVEL = "lock_level"              // how many lockouts in a row
    private const val KEY_LOCK_ELAPSED = "locked_until_elapsed"
    private const val KEY_LOCK_BOOT = "locked_boot"
    private const val KEY_LOCK_MS = "locked_ms"
    private const val PBKDF2_ROUNDS = 120_000
    /** On a STOP / PAUSE intent: the app checked the PIN (or it is its own automation). */
    const val EXTRA_UNLOCKED = "parent_unlocked"
    const val MAX_FAILS = 5
    const val LOCKOUT_MS = 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = !prefs(ctx).getString(KEY_HASH, "").isNullOrEmpty()

    /** Pure: 4 to 8 digits. */
    fun pinOk(pin: String): Boolean = pin.length in 4..8 && pin.all { it in '0'..'9' }

    /** Pure: the stored form of a PIN. */
    fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Pure: the slow stored form (PBKDF2, 120,000 rounds) — a copied hash can't be tried a million times a second. */
    fun slowHash(salt: String, pin: String): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt.toByteArray(Charsets.UTF_8), PBKDF2_ROUNDS, 256)
        val f = try { javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
                catch (_: Exception) { javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1") }   // Android 7
        return f.generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }

    /** Pure: how long the [level]-th lockout in a row lasts — 1 min, 5 min, 30 min, then 2 hours. */
    fun lockoutFor(level: Int): Long = when {
        level <= 1 -> LOCKOUT_MS
        level == 2 -> 5 * LOCKOUT_MS
        level == 3 -> 30 * LOCKOUT_MS
        else -> 120 * LOCKOUT_MS
    }

    /** Pure: milliseconds a lockout still has to run. [sameBoot] false = the device was restarted since:
     *  the whole wait starts again (the time since boot is all a restart can't fake). */
    fun lockLeft(untilElapsed: Long, lockMs: Long, sameBoot: Boolean, elapsedNow: Long): Long = when {
        lockMs <= 0L -> 0L
        !sameBoot -> lockMs
        else -> (untilElapsed - elapsedNow).coerceIn(0L, lockMs)
    }

    /** Pure: may a STOP / PAUSE act? Only when there is no lock, or the app unlocked it. */
    fun allows(locked: Boolean, unlocked: Boolean): Boolean = !locked || unlocked

    /** Pure: after a wrong PIN — (failures so far, locked until). */
    fun afterFailure(fails: Int, now: Long): Pair<Int, Long> {
        val n = fails + 1
        return if (n >= MAX_FAILS) 0 to now + LOCKOUT_MS else n to 0L
    }

    fun set(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString(KEY_SALT, salt).putString(KEY_HASH, slowHash(salt, pin)).putString(KEY_ALGO, "pbkdf2")
            .putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, 0L).putInt(KEY_LEVEL, 0).putLong(KEY_LOCK_MS, 0L).apply()
    }

    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }

    private fun bootCount(ctx: Context): Int = try {
        android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)
    } catch (_: Exception) { 0 }

    /** Seconds the dialog is still locked after too many wrong tries (0 = open). */
    fun lockedFor(ctx: Context, now: Long = System.currentTimeMillis()): Long {
        val p = prefs(ctx)
        val lockMs = p.getLong(KEY_LOCK_MS, 0L)
        if (lockMs <= 0L) return 0L
        val sameBoot = p.getInt(KEY_LOCK_BOOT, -1) == bootCount(ctx)
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (!sameBoot)                                      // restarted: the wait starts again, from now
            p.edit().putLong(KEY_LOCK_ELAPSED, elapsed + lockMs).putInt(KEY_LOCK_BOOT, bootCount(ctx)).apply()
        val left = lockLeft(p.getLong(KEY_LOCK_ELAPSED, 0L), lockMs, sameBoot, elapsed)
        if (left <= 0L) { p.edit().putLong(KEY_LOCK_MS, 0L).apply(); return 0L }
        return (left + 999) / 1000
    }

    fun check(ctx: Context, pin: String, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(ctx)
        if (lockedFor(ctx, now) > 0) return false
        val salt = p.getString(KEY_SALT, "") ?: ""
        val slow = p.getString(KEY_ALGO, "") == "pbkdf2"
        val ok = (if (slow) slowHash(salt, pin) else hash(salt, pin)) == p.getString(KEY_HASH, "")
        if (ok) {
            val e = p.edit().putInt(KEY_FAILS, 0).putInt(KEY_LEVEL, 0).putLong(KEY_LOCK_MS, 0L)
            if (!slow) e.putString(KEY_HASH, slowHash(salt, pin)).putString(KEY_ALGO, "pbkdf2")   // upgrade an old lock
            e.apply()
        } else {
            val (fails, until) = afterFailure(p.getInt(KEY_FAILS, 0), now)
            val e = p.edit().putInt(KEY_FAILS, fails)
            if (until > 0L) {                               // five wrong tries: lock, longer each time
                val level = p.getInt(KEY_LEVEL, 0) + 1
                val ms = lockoutFor(level)
                e.putInt(KEY_LEVEL, level).putLong(KEY_LOCK_MS, ms).putInt(KEY_LOCK_BOOT, bootCount(ctx))
                    .putLong(KEY_LOCK_ELAPSED, android.os.SystemClock.elapsedRealtime() + ms)
            }
            e.apply()
        }
        return ok
    }

    private fun pinField(activity: Activity, hint: String): EditText = EditText(activity).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        isSingleLine = true
    }

    private fun boxed(activity: Activity, vararg fields: EditText): FrameLayout {
        val d = activity.resources.displayMetrics.density
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fields.forEach { col.addView(it) }
        return FrameLayout(activity).apply {
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
            addView(col)
        }
    }

    /** Runs [action] at once when there is no lock; otherwise asks the PIN first. */
    fun gate(activity: Activity, what: String, onCancel: () -> Unit = {}, action: () -> Unit) {
        if (!isOn(activity)) { action(); return }
        val left = lockedFor(activity)
        if (left > 0) {
            Toast.makeText(activity, "Too many wrong tries — wait " + (if (left >= 120) "${(left + 59) / 60} min." else "$left s."), Toast.LENGTH_LONG).show()
            onCancel(); return
        }
        val input = pinField(activity, "Parent PIN")
        AlertDialog.Builder(activity)
            .setTitle("Parent lock")
            .setMessage("A parent's PIN is needed to $what.")
            .setView(boxed(activity, input))
            .setPositiveButton("OK") { _, _ ->
                if (check(activity, input.text.toString())) action()
                else {
                    Toast.makeText(activity, "Wrong PIN.", Toast.LENGTH_SHORT).show()
                    onCancel()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    /** More settings → Parent lock: set it up, or (with the PIN) change or remove it. */
    fun manage(activity: Activity, done: () -> Unit = {}) {
        if (!isOn(activity)) { setDialog(activity, "Set a parent PIN", done); return }
        gate(activity, "change the parent lock") {
            AlertDialog.Builder(activity)
                .setTitle("Parent lock is on")
                .setItems(arrayOf("Change the PIN", "Remove the lock")) { _, i ->
                    if (i == 0) setDialog(activity, "New parent PIN", done)
                    else { clear(activity); Toast.makeText(activity, "Parent lock removed.", Toast.LENGTH_SHORT).show(); done() }
                }
                .setNegativeButton("Back", null)
                .show()
        }
    }

    private fun setDialog(activity: Activity, title: String, done: () -> Unit) {
        val a = pinField(activity, "PIN (4 to 8 digits)")
        val b = pinField(activity, "The same PIN again")
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("With the lock on, turning protection off, pausing it, letting an app bypass Noxa, allowing a " +
                "blocked site or changing the home-server settings needs this PIN. Blocking more never does.\n\n" +
                "Honest limit: Android lets anyone uninstall an app. If that happens, a home server notices the silence.")
            .setView(boxed(activity, a, b))
            .setPositiveButton("Set") { _, _ ->
                val pin = a.text.toString()
                when {
                    !pinOk(pin) -> Toast.makeText(activity, "The PIN must be 4 to 8 digits.", Toast.LENGTH_LONG).show()
                    pin != b.text.toString() -> Toast.makeText(activity, "The two PINs differ.", Toast.LENGTH_LONG).show()
                    else -> { set(activity, pin); Toast.makeText(activity, "Parent lock is on.", Toast.LENGTH_SHORT).show(); done() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
