package com.elderphone.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.elderphone.app.data.BlockedNumber
import com.elderphone.app.databinding.ItemBlockedNumberBinding

class BlockedNumbersAdapter(
    private var items: List<BlockedNumber>,
    private val onUnblockClick: (BlockedNumber) -> Unit
) : RecyclerView.Adapter<BlockedNumbersAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemBlockedNumberBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBlockedNumberBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvBlockedNumber.text = item.number
        holder.binding.btnUnblock.setOnClickListener {
            onUnblockClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newItems: List<BlockedNumber>) {
        items = newItems
        notifyDataSetChanged()
    }
}
