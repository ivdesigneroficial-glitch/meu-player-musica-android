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

class SongAdapter(
    private var items: List<Song>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.VH>() {

    private var playingId: Long = -1L

    private val artUriBase: Uri = Uri.parse("content://media/external/audio/albumart")

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.item_art)
        val title: TextView = view.findViewById(R.id.item_title)
        val sub: TextView = view.findViewById(R.id.item_sub)
    }

    fun update(newItems: List<Song>) {
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
        val song = items[position]
        holder.title.text = song.title
        val dur = formatTime(song.duration)
        val artistTxt = if (song.artist.isBlank() || song.artist == "<unknown>") "Artista desconhecido" else song.artist
        holder.sub.text = "$artistTxt • $dur"

        val highlight = song.id == playingId
        holder.title.setTextColor(
            if (highlight) 0xFFF6C915.toInt() else 0xFFF2F2F7.toInt()
        )

        // Try to load album art; fallback to note icon
        val albumArtUri = ContentUris.withAppendedId(artUriBase, song.albumId)
        try {
            val pfd = holder.art.context.contentResolver.openFileDescriptor(albumArtUri, "r")
            if (pfd != null) {
                val bmp = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                pfd.close()
                if (bmp != null) {
                    holder.art.setImageBitmap(bmp)
                } else {
                    holder.art.setImageResource(R.drawable.ic_note)
                }
            } else {
                holder.art.setImageResource(R.drawable.ic_note)
            }
        } catch (e: Exception) {
            holder.art.setImageResource(R.drawable.ic_note)
        }

        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}
