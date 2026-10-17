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
 * The PIN is kept only as a salted SHA-256 on the device. Five wrong tries
 * lock the dialog for a minute. Honest limit, also in the docs: Android lets
 * anyone uninstall an app — if that happens, a home server notices the
 * silence (see HomeReport).
 */
object ParentLock {
    const val PREFS = "guardian_lock"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_FAILS = "fails"
    private const val KEY_LOCKED_UNTIL = "locked_until"
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

    /** Pure: may a STOP / PAUSE act? Only when there is no lock, or the app unlocked it. */
    fun allows(locked: Boolean, unlocked: Boolean): Boolean = !locked || unlocked

    /** Pure: after a wrong PIN — (failures so far, locked until). */
    fun afterFailure(fails: Int, now: Long): Pair<Int, Long> {
        val n = fails + 1
        return if (n >= MAX_FAILS) 0 to now + LOCKOUT_MS else n to 0L
    }

    fun set(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString(KEY_SALT, salt).putString(KEY_HASH, hash(salt, pin))
            .putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, 0L).apply()
    }

    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }

    /** Seconds the dialog is still locked after too many wrong tries (0 = open). */
    fun lockedFor(ctx: Context, now: Long = System.currentTimeMillis()): Long =
        ((prefs(ctx).getLong(KEY_LOCKED_UNTIL, 0L) - now) / 1000).coerceAtLeast(0L)

    fun check(ctx: Context, pin: String, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(ctx)
        if (lockedFor(ctx, now) > 0) return false
        val ok = hash(p.getString(KEY_SALT, "") ?: "", pin) == p.getString(KEY_HASH, "")
        if (ok) p.edit().putInt(KEY_FAILS, 0).apply()
        else {
            val (fails, until) = afterFailure(p.getInt(KEY_FAILS, 0), now)
            p.edit().putInt(KEY_FAILS, fails).putLong(KEY_LOCKED_UNTIL, until).apply()
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
            Toast.makeText(activity, "Too many wrong tries — wait $left s.", Toast.LENGTH_LONG).show()
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
