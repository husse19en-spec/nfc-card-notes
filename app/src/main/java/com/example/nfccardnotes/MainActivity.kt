package com.example.nfccardnotes

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
    private var internalNav = false    // عند فتح شاشة التفاصيل لا نُعيد القفل
    private var pendingTag: Tag? = null // بطاقة لُمست أثناء القفل، تُعالَج بعد فتح القفل

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CardStore(this)
        adapter = CardAdapter(onClick = { if (!locked) openDetail(it, isNew = false) })
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) binding.status.text = getString(R.string.no_nfc)

        binding.unlockBtn.setOnClickListener { promptUnlock() }

        handleNfcIntent(intent)   // في حال فُتح التطبيق بتقريب بطاقة
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    /** يلتقط البطاقة التي أطلقت التطبيق عند التقريب. */
    private fun handleNfcIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED) return
        @Suppress("DEPRECATION")
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        if (locked) pendingTag = tag   // تُعالَج بعد التحقق من الهوية
        else processTag(tag)
    }

    private fun processTag(tag: Tag) {
        Thread { onTagDiscovered(tag) }.start()
    }

    // ----- دورة الحياة والقفل -----

    override fun onStart() {
        super.onStart()
        if (locked) {
            showLocked()
            promptUnlock()
        } else {
            internalNav = false
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!locked) enableNfc()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onStop() {
        super.onStop()
        if (!internalNav) locked = true   // أُعيد القفل فقط عند مغادرة التطبيق فعلاً
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
        pendingTag?.let { t ->       // بطاقة لُمست قبل فتح القفل
            pendingTag = null
            processTag(t)
        }
    }

    private fun promptUnlock() {
        if (authInProgress) return

        if (!deviceCanAuthenticate()) {
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

    private fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private fun deviceCanAuthenticate(): Boolean {
        val bm = BiometricManager.from(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }
        if (bm.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) return true
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

    private fun onCardRead(info: EmvReader.CardInfo) {
        val key = sha256(info.pan)
        val last4 = info.pan.filter { it.isDigit() }.takeLast(4)
        val existing = store.get(key)
        if (existing != null) openDetail(existing, isNew = false)
        else openDetail(CardNote(key, last4, "", "", info.expiry), isNew = true)
    }

    // ----- التنقل والقائمة -----

    private fun openDetail(card: CardNote, isNew: Boolean) {
        internalNav = true
        val i = Intent(this, CardDetailActivity::class.java)
        i.putExtra("key", card.key)
        i.putExtra("last4", card.last4)
        i.putExtra("expiry", card.expiry)
        i.putExtra("isNew", isNew)
        startActivity(i)
    }

    private fun refresh() {
        val all = store.getAll()
        adapter.submit(all)
        binding.empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
