package com.ivan.playermusica

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Playlist(val name: String, val songIds: MutableList<Long>)

class PlaylistStore(context: Context) {

    private val prefs = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)

    fun getAll(): MutableList<Playlist> {
        val raw = prefs.getString("data", "[]") ?: "[]"
        val list = mutableListOf<Playlist>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.getString("name")
                val idsArr = o.getJSONArray("ids")
                val ids = mutableListOf<Long>()
                for (j in 0 until idsArr.length()) ids.add(idsArr.getLong(j))
                list.add(Playlist(name, ids))
            }
        } catch (e: Exception) { /* ignore corrupt data */ }
        return list
    }

    private fun saveAll(list: List<Playlist>) {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("name", p.name)
            val ids = JSONArray()
            for (id in p.songIds) ids.put(id)
            o.put("ids", ids)
            arr.put(o)
        }
        prefs.edit().putString("data", arr.toString()).apply()
    }

    fun create(name: String): Boolean {
        val list = getAll()
        if (list.any { it.name.equals(name, ignoreCase = true) }) return false
        list.add(Playlist(name, mutableListOf()))
        saveAll(list)
        return true
    }

    fun delete(name: String) {
        val list = getAll()
        list.removeAll { it.name == name }
        saveAll(list)
    }

    fun rename(oldName: String, newName: String): Boolean {
        val list = getAll()
        if (list.any { it.name.equals(newName, ignoreCase = true) }) return false
        val p = list.find { it.name == oldName } ?: return false
        val idx = list.indexOf(p)
        list[idx] = Playlist(newName, p.songIds)
        saveAll(list)
        return true
    }

    fun addSong(playlistName: String, songId: Long) {
        val list = getAll()
        val p = list.find { it.name == playlistName } ?: return
        if (!p.songIds.contains(songId)) {
            p.songIds.add(songId)
            saveAll(list)
        }
    }

    fun removeSong(playlistName: String, songId: Long) {
        val list = getAll()
        val p = list.find { it.name == playlistName } ?: return
        p.songIds.remove(songId)
        saveAll(list)
    }
}
