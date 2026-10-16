package com.example.nfccardnotes

import android.nfc.tech.IsoDep
import java.io.ByteArrayOutputStream

/**
 * Reads a contactless (EMV) bank card over NFC to get a stable identifier.
 *
 * It only extracts the PAN (card number) and expiry so the card can be
 * recognized. The app then stores a *hash* of the PAN, never the number itself.
 *
 * Works with many Visa / Mastercard contactless cards, but not 100% of cards:
 * some require a more complex flow (a non-empty PDOL) and will not be read.
 */
object EmvReader {

    data class CardInfo(val pan: String, val expiry: String)

    fun readCard(isoDep: IsoDep): CardInfo? {
        isoDep.timeout = 5000

        // 1) SELECT PPSE (2PAY.SYS.DDF01) -> discover the payment application id
        val ppse = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)
        var resp = isoDep.transceive(buildSelect(ppse))
        if (!ok(resp)) return null
        val aid = Tlv.find(body(resp), 0x4F) ?: return null

        // 2) SELECT the application (AID)
        resp = isoDep.transceive(buildSelect(aid))
        if (!ok(resp)) return null

        val records = mutableListOf<ByteArray>()

        // 3) GET PROCESSING OPTIONS (assuming an empty PDOL)
        resp = isoDep.transceive(hex("80A8000002830000"))
        if (ok(resp)) {
            val gpoBody = body(resp)
            records.add(gpoBody)
            Tlv.find(gpoBody, 0x94)?.let { afl -> readAfl(isoDep, afl, records) }
        }

        // 4) Fallback: if PAN not found yet, brute-force common records
        if (records.none { Tlv.find(it, 0x57) != null || Tlv.find(it, 0x5A) != null }) {
            bruteForce(isoDep, records)
        }

        var pan: String? = null
        var expiry: String? = null
        for (r in records) {
            if (pan == null) Tlv.find(r, 0x5A)?.let { pan = hexStr(it).trimEnd('F', 'f') }
            if (expiry == null) Tlv.find(r, 0x5F24)?.let { expiry = hexStr(it) }
            Tlv.find(r, 0x57)?.let { t2 ->
                parseTrack2(t2)?.let { (p, e) ->
                    if (pan == null) pan = p
                    if (expiry == null && e.isNotEmpty()) expiry = e
                }
            }
            if (pan != null && expiry != null) break
        }

        val finalPan = pan ?: return null
        return CardInfo(finalPan, formatExpiry(expiry))
    }

    private fun readAfl(isoDep: IsoDep, afl: ByteArray, out: MutableList<ByteArray>) {
        var i = 0
        while (i + 4 <= afl.size) {
            val sfi = (afl[i].toInt() and 0xFF) shr 3
            val first = afl[i + 1].toInt() and 0xFF
            val last = afl[i + 2].toInt() and 0xFF
            var rec = first
            while (rec <= last) {
                val cmd = byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), ((sfi shl 3) or 4).toByte(), 0x00)
                try {
                    val r = isoDep.transceive(cmd)
                    if (ok(r)) out.add(body(r))
                } catch (_: Exception) { }
                rec++
            }
            i += 4
        }
    }

    private fun bruteForce(isoDep: IsoDep, out: MutableList<ByteArray>) {
        for (sfi in 1..10) {
            for (rec in 1..5) {
                val cmd = byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), ((sfi shl 3) or 4).toByte(), 0x00)
                try {
                    val r = isoDep.transceive(cmd)
                    if (ok(r)) out.add(body(r))
                } catch (_: Exception) { }
            }
        }
    }

    private fun buildSelect(aid: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00); out.write(0xA4); out.write(0x04); out.write(0x00)
        out.write(aid.size)
        out.write(aid)
        out.write(0x00)
        return out.toByteArray()
    }

    private fun ok(r: ByteArray?): Boolean =
        r != null && r.size >= 2 &&
        r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()

    private fun body(r: ByteArray): ByteArray = r.copyOf(r.size - 2)

    /** Track 2 Equivalent Data (tag 57): PAN 'D' YYMM ... -> (pan, YYMM) */
    private fun parseTrack2(t: ByteArray): Pair<String, String>? {
        val h = hexStr(t)
        val sep = h.indexOfFirst { it == 'D' || it == 'd' }
        if (sep <= 0) return null
        val pan = h.substring(0, sep)
        val rest = h.substring(sep + 1)
        val expiry = if (rest.length >= 4) rest.substring(0, 4) else ""
        return pan to expiry
    }

    /** tag 5F24 is YYMMDD, track2 expiry is YYMM -> "MM/YY" */
    private fun formatExpiry(yymm: String?): String {
        if (yymm.isNullOrBlank() || yymm.length < 4) return ""
        val yy = yymm.substring(0, 2)
        val mm = yymm.substring(2, 4)
        return "$mm/$yy"
    }

    private fun hexStr(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte()
        }
        return out
    }

    /** Minimal BER-TLV search: finds a tag anywhere, recursing into constructed objects. */
    object Tlv {
        fun find(data: ByteArray, tag: Int): ByteArray? {
            var i = 0
            while (i < data.size) {
                val tagStart = i
                var t = data[i].toInt() and 0xFF
                i++
                if ((t and 0x1F) == 0x1F) {
                    while (i < data.size && (data[i].toInt() and 0x80) != 0) {
                        t = (t shl 8) or (data[i].toInt() and 0xFF); i++
                    }
                    if (i < data.size) { t = (t shl 8) or (data[i].toInt() and 0xFF); i++ }
                }
                if (i >= data.size) break
                var len = data[i].toInt() and 0xFF
                i++
                if (len and 0x80 != 0) {
                    val n = len and 0x7F
                    len = 0
                    for (k in 0 until n) {
                        if (i >= data.size) return null
                        len = (len shl 8) or (data[i].toInt() and 0xFF); i++
                    }
                }
                if (len < 0 || i + len > data.size) return null
                val value = data.copyOfRange(i, i + len)
                if (t == tag) return value
                val constructed = (data[tagStart].toInt() and 0x20) != 0
                if (constructed) {
                    find(value, tag)?.let { return it }
                }
                i += len
            }
            return null
        }
    }
}
