package com.ivan.playermusica

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

sealed class Row {
    data class GroupRow(
        val key: String,
        val title: String,
        val subtitle: String,
        val iconRes: Int
    ) : Row()

    data class SongRow(val song: Song) : Row()
}

class RowAdapter(
    private var items: List<Row>,
    private val onClick: (Row) -> Unit,
    private val onLongClick: (Row) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    private var playingId: Long = -1L
    private val artUriBase: Uri = Uri.parse("content://media/external/audio/albumart")

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.item_art)
        val title: TextView = view.findViewById(R.id.item_title)
        val sub: TextView = view.findViewById(R.id.item_sub)
    }

    fun update(newItems: List<Row>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setPlaying(id: Long) {
        playingId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        when (val row = items[position]) {
            is Row.GroupRow -> {
                holder.title.text = row.title
                holder.title.setTextColor(0xFFF2F2F7.toInt())
                holder.sub.text = row.subtitle
                holder.art.setImageResource(row.iconRes)
                holder.art.setColorFilter(0xFFF6C915.toInt())
            }
            is Row.SongRow -> {
                val song = row.song
                holder.art.clearColorFilter()
                holder.title.text = song.title
                val dur = formatTime(song.duration)
                val artistTxt = if (song.artist.isBlank() || song.artist == "<unknown>")
                    "Artista desconhecido" else song.artist
                holder.sub.text = "$artistTxt • $dur"
                holder.title.setTextColor(
                    if (song.id == playingId) 0xFFF6C915.toInt() else 0xFFF2F2F7.toInt()
                )
                loadArt(holder, song)
            }
        }
        holder.itemView.setOnClickListener { onClick(items[holder.bindingAdapterPosition]) }
        holder.itemView.setOnLongClickListener {
            onLongClick(items[holder.bindingAdapterPosition]); true
        }
    }

    private fun loadArt(holder: VH, song: Song) {
        val albumArtUri = ContentUris.withAppendedId(artUriBase, song.albumId)
        try {
            val pfd = holder.art.context.contentResolver.openFileDescriptor(albumArtUri, "r")
            if (pfd != null) {
                val bmp = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                pfd.close()
                if (bmp != null) holder.art.setImageBitmap(bmp)
                else holder.art.setImageResource(R.drawable.ic_note)
            } else holder.art.setImageResource(R.drawable.ic_note)
        } catch (e: Exception) {
            holder.art.setImageResource(R.drawable.ic_note)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}
