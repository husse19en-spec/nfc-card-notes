package com.example.nfccardnotes

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nfccardnotes.databinding.ActivityMainBinding
import java.security.MessageDigest

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: CardStore
    private lateinit var adapter: CardAdapter
    private var nfcAdapter: NfcAdapter? = null

    private var locked = true          // التطبيق يبدأ مقفلاً
    private var authInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CardStore(this)
        adapter = CardAdapter(onClick = { if (!locked) showEditDialog(it, isNew = false) })
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) binding.status.text = getString(R.string.no_nfc)

        binding.unlockBtn.setOnClickListener { promptUnlock() }
    }

    // ----- دورة الحياة والقفل -----

    override fun onStart() {
        super.onStart()
        if (locked) {
            showLocked()
            promptUnlock()
        }
    }

    override fun onStop() {
        super.onStop()
        // أعد القفل عند مغادرة التطبيق
        locked = true
        nfcAdapter?.disableReaderMode(this)
    }

    private fun showLocked() {
        binding.lockScreen.visibility = View.VISIBLE
        binding.content.visibility = View.INVISIBLE
    }

    private fun onUnlocked() {
        locked = false
        binding.lockScreen.visibility = View.GONE
        binding.content.visibility = View.VISIBLE
        refresh()
        enableNfc()
    }

    private fun promptUnlock() {
        if (authInProgress) return

        if (!deviceCanAuthenticate()) {
            // لا يوجد قفل مُفعّل على الجهاز — ننبّه المستخدم لكن نسمح بالدخول
            Toast.makeText(this, R.string.no_lock_set, Toast.LENGTH_LONG).show()
            onUnlocked()
            return
        }

        authInProgress = true
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authInProgress = false
                    onUnlocked()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authInProgress = false
                    // يبقى مقفلاً؛ يمكنه الضغط على "فتح القفل" للمحاولة ثانية
                }
            })

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auth_title))
            .setSubtitle(getString(R.string.auth_subtitle))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
               builder.setAllowedAuthenticators(allowedAuthenticators())
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        prompt.authenticate(builder.build())
    }

    /** يسمح بالبصمة/الوجه مع الرجوع لرمز قفل الجهاز. */
    private fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** هل يملك الجهاز وسيلة مصادقة (بصمة/وجه أو رمز قفل)؟ */
    private fun deviceCanAuthenticate(): Boolean {
        val bm = BiometricManager.from(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }
        // بصمة متوفرة؟
        if (bm.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) return true
        // أو رمز/نمط قفل الجهاز موجود؟
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return km?.isDeviceSecure == true
    }

    // ----- NFC -----

    private fun enableNfc() {
        nfcAdapter?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onTagDiscovered(tag: Tag) {
        if (locked) return
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            runOnUiThread { toast(getString(R.string.not_card)) }
            return
        }
        try {
            isoDep.connect()
            val info = EmvReader.readCard(isoDep)
            runOnUiThread {
                if (info == null) toast(getString(R.string.read_failed))
                else onCardRead(info)
            }
        } catch (e: Exception) {
            runOnUiThread { toast(getString(R.string.read_failed)) }
        } finally {
            try { isoDep.close() } catch (_: Exception) { }
        }
    }

    // ----- البيانات والواجهة -----

    private fun refresh() {
        val all = store.getAll()
        adapter.submit(all)
        binding.empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onCardRead(info: EmvReader.CardInfo) {
        val key = sha256(info.pan)
        val last4 = info.pan.filter { it.isDigit() }.takeLast(4)
        val existing = store.get(key)
        if (existing != null) showEditDialog(existing, isNew = false)
        else showEditDialog(CardNote(key, last4, "", "", info.expiry), isNew = true)
    }

    private fun showEditDialog(card: CardNote, isNew: Boolean) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val nick = EditText(this).apply {
            hint = getString(R.string.hint_nickname)
            setText(card.nickname)
        }
        val notes = EditText(this).apply {
            hint = getString(R.string.hint_notes)
            setText(card.notes)
            minLines = 3
            gravity = Gravity.TOP
        }
        container.addView(nick)
        container.addView(notes)

        val title = buildString {
            append(getString(R.string.card))
            if (card.last4.isNotEmpty()) append(" •••• ${card.last4}")
            if (card.expiry.isNotEmpty()) append("  (${card.expiry})")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                card.nickname = nick.text.toString().trim()
                card.notes = notes.text.toString().trim()
                store.save(card)
                refresh()
                toast(getString(R.string.saved))
            }
            .setNegativeButton(R.string.cancel, null)

        if (!isNew) builder.setNeutralButton(R.string.delete) { _, _ -> confirmDelete(card) }
        builder.show()
    }

    private fun confirmDelete(card: CardNote) {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ -> store.delete(card.key); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
