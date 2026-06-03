package com.rgblight.controller.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent scene storage via SharedPreferences.
 * Each scene serializes to JSON: {id, name, mode, r, g, b, brightness}
 */
class SceneRepository(context: Context) {

    private val prefs = context.getSharedPreferences("scenes", Context.MODE_PRIVATE)

    fun save(scene: Scene) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == scene.id }
        if (idx >= 0) {
            all[idx] = scene
        } else {
            all.add(scene)
        }
        writeAll(all)
    }

    fun delete(id: Long) {
        val all = loadAll().filter { it.id != id }
        writeAll(all)
    }

    fun loadAll(): List<Scene> {
        val json = prefs.getString("scene_list", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Scene(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    mode = obj.getInt("mode"),
                    r = obj.getInt("r"),
                    g = obj.getInt("g"),
                    b = obj.getInt("b"),
                    brightness = obj.optInt("brightness", 100)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAll(scenes: List<Scene>) {
        val arr = JSONArray()
        for (s in scenes) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("mode", s.mode)
                put("r", s.r)
                put("g", s.g)
                put("b", s.b)
                put("brightness", s.brightness)
            })
        }
        prefs.edit().putString("scene_list", arr.toString()).apply()
    }
}
