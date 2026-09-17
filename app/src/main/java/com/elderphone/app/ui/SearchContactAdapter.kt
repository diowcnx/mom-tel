package com.elderphone.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.elderphone.app.R
import com.elderphone.app.data.ContactRepository
import com.elderphone.app.databinding.ItemSearchContactBinding
import com.elderphone.app.model.ElderContact

class SearchContactAdapter(
    private val context: Context,
    private var contactList: List<ElderContact>,
    private val onContactClicked: (ElderContact) -> Unit
) : RecyclerView.Adapter<SearchContactAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSearchContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchContactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contactList[position]
        val b = holder.binding

        b.tvSearchContactName.text = contact.name
        b.tvSearchContactNumber.text = contact.phoneNumber

        if (!contact.photoUri.isNullOrBlank()) {
            b.imgSearchAvatar.visibility = View.VISIBLE
            b.tvSearchAvatarInitial.visibility = View.GONE
            Glide.with(context)
                .load(contact.photoUri)
                .signature(com.bumptech.glide.signature.ObjectKey(contact.photoLastModified.toString()))
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(b.imgSearchAvatar)
        } else {
            b.imgSearchAvatar.visibility = View.GONE
            b.tvSearchAvatarInitial.visibility = View.VISIBLE
            b.tvSearchAvatarInitial.text = contact.initial
            val colorRes = ContactRepository.getAvatarColorForName(contact.name)
            b.viewSearchAvatarBg.background.setTint(ContextCompat.getColor(context, colorRes))
        }

        b.root.setOnClickListener {
            onContactClicked(contact)
        }
    }

    override fun getItemCount(): Int = contactList.size

    fun updateData(newList: List<ElderContact>) {
        contactList = newList
        notifyDataSetChanged()
    }
}
