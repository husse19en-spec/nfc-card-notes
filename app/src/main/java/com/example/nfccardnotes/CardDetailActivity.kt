package com.example.nfccardnotes

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.nfccardnotes.databinding.ActivityDetailBinding

class CardDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var store: CardStore
    private lateinit var card: CardNote
    private var isNew = false

    // منتقي الصور — خانة 1
    private val pick1 = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPhotoPicked(1, uri)
    }
    private val pick2 = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPhotoPicked(2, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CardStore(this)

        val key = intent.getStringExtra("key") ?: run { finish(); return }
        isNew = intent.getBooleanExtra("isNew", false)
        card = store.get(key) ?: CardNote(
            key = key,
            last4 = intent.getStringExtra("last4") ?: "",
            nickname = "",
            notes = "",
            expiry = intent.getStringExtra("expiry") ?: ""
        )

        bind()

        binding.photo1.setOnClickListener { photoOptions(1) }
        binding.photo2.setOnClickListener { photoOptions(2) }
        binding.saveBtn.setOnClickListener { save() }
        binding.deleteBtn.setOnClickListener { confirmDelete() }
        binding.deleteBtn.visibility = if (isNew) View.GONE else View.VISIBLE
    }

    private fun bind() {
        binding.headerTitle.text =
            if (card.nickname.isNotEmpty()) card.nickname else getString(R.string.unnamed)
        val num = if (card.last4.isNotEmpty()) "•••• •••• •••• ${card.last4}" else ""
        val exp = if (card.expiry.isNotEmpty()) "   ${card.expiry}" else ""
        binding.headerNumber.text = "$num$exp"
        binding.nick.setText(card.nickname)
        binding.notes.setText(card.notes)
        showPhoto(1)
        showPhoto(2)
    }

    private fun showPhoto(slot: Int) {
        val name = if (slot == 1) card.photo1 else card.photo2
        val img = if (slot == 1) binding.photo1 else binding.photo2
        val hint = if (slot == 1) binding.photo1Hint else binding.photo2Hint
        val bmp = PhotoUtil.load(this, name)
        if (bmp != null) {
            img.setImageBitmap(bmp)
            hint.visibility = View.GONE
        } else {
            img.setImageDrawable(null)
            hint.visibility = View.VISIBLE
        }
    }

    private fun photoOptions(slot: Int) {
        val has = (if (slot == 1) card.photo1 else card.photo2).isNotEmpty()
        if (!has) {
            launchPicker(slot)
            return
        }
        val options = arrayOf(getString(R.string.change_photo), getString(R.string.remove_photo))
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                if (which == 0) launchPicker(slot) else removePhoto(slot)
            }
            .show()
    }

    private fun launchPicker(slot: Int) {
        if (slot == 1) pick1.launch("image/*") else pick2.launch("image/*")
    }

    private fun onPhotoPicked(slot: Int, uri: Uri) {
        val name = PhotoUtil.savePhoto(this, card.key, slot, uri)
        if (name == null) {
            Toast.makeText(this, R.string.read_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (slot == 1) card.photo1 = name else card.photo2 = name
        store.save(card)              // حفظ فوري للصورة
        showPhoto(slot)
    }

    private fun removePhoto(slot: Int) {
        val name = if (slot == 1) card.photo1 else card.photo2
        PhotoUtil.delete(this, name)
        if (slot == 1) card.photo1 = "" else card.photo2 = ""
        store.save(card)
        showPhoto(slot)
    }

    private fun save() {
        card.nickname = binding.nick.text.toString().trim()
        card.notes = binding.notes.text.toString().trim()
        store.save(card)
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                PhotoUtil.delete(this, card.photo1)
                PhotoUtil.delete(this, card.photo2)
                store.delete(card.key)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
