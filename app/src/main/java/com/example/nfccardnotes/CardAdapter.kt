package com.example.nfccardnotes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.nfccardnotes.databinding.ItemCardBinding

class CardAdapter(
    private val onClick: (CardNote) -> Unit
) : RecyclerView.Adapter<CardAdapter.VH>() {

    private val items = mutableListOf<CardNote>()

    fun submit(list: List<CardNote>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        val ctx = h.b.root.context
        h.b.title.text =
            if (c.nickname.isNotEmpty()) c.nickname else ctx.getString(R.string.unnamed)
        h.b.root.setOnClickListener { onClick(c) }
    }
}
