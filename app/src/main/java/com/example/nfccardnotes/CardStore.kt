package com.example.nfccardnotes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved card entry.
 * key     = SHA-256 hash of the PAN (card number). We never store the real number.
 * last4   = last four digits, only so you can recognize the card.
 * expiry  = "MM/YY" (may be empty if the card did not expose it).
 * nickname / notes = whatever YOU type for this card.
 */
data class CardNote(
    val key: String,
    val last4: String,
    var nickname: String,
    var notes: String,
    var expiry: String
)

/** Simple local storage backed by SharedPreferences (stays only on this phone). */
class CardStore(context: Context) {

    private val prefs = context.getSharedPreferences("cards", Context.MODE_PRIVATE)

    fun getAll(): MutableList<CardNote> {
        val raw = prefs.getString("list", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<CardNote>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                CardNote(
                    o.getString("key"),
                    o.optString("last4"),
                    o.optString("nickname"),
                    o.optString("notes"),
                    o.optString("expiry")
                )
            )
        }
        return out
    }

    fun get(key: String): CardNote? = getAll().firstOrNull { it.key == key }

    fun save(card: CardNote) {
        val all = getAll()
        val idx = all.indexOfFirst { it.key == card.key }
        if (idx >= 0) all[idx] = card else all.add(card)
        persist(all)
    }

    fun delete(key: String) {
        persist(getAll().filter { it.key != key })
    }

    private fun persist(list: List<CardNote>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("key", c.key)
            o.put("last4", c.last4)
            o.put("nickname", c.nickname)
            o.put("notes", c.notes)
            o.put("expiry", c.expiry)
            arr.put(o)
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
