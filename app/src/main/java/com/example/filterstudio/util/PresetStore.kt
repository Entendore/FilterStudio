package com.example.filterstudio.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.filterstudio.data.BuiltInPresets
import com.example.filterstudio.model.asPresetState
import com.example.filterstudio.model.look
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.presetDataStore by preferencesDataStore("filter_presets")

class PresetStore(private val context: Context) {
    data class Preset(val name: String, val state: com.example.filterstudio.model.EditState,
                      val builtin: Boolean = false, val category: String = "My")
    private val key = stringPreferencesKey("presets_json")

    private suspend fun userPresets(): List<Preset> =
        context.presetDataStore.data.first()[key]?.let { json ->
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Preset(o.getString("name"),
                    runCatching { editStateFromJson(o.getString("state")) }
                        .getOrDefault(com.example.filterstudio.model.EditState())) }
        } ?: emptyList()

    suspend fun all(): List<Preset> {
        val user = userPresets()
        val names = user.map { it.name }.toSet()
        return BuiltInPresets.all.map { r ->
            Preset(r.name, r.look.asPresetState(), builtin = true, category = r.category)
        }.filter { it.name !in names } + user
    }

    suspend fun save(name: String, state: com.example.filterstudio.model.EditState) =
        upsert(userPresets().filterNot { it.name == name } + Preset(name, state.look.asPresetState()))

    suspend fun delete(name: String) = upsert(userPresets().filterNot { it.name == name })

    private suspend fun upsert(list: List<Preset>) {
        context.presetDataStore.edit { prefs ->
            prefs[key] = JSONArray().apply {
                list.forEach { p -> put(JSONObject()
                    .put("name", p.name).put("state", p.state.toJson())) }
            }.toString()
        }
    }

    fun export(p: Preset): String = JSONObject()
        .put("v", 2).put("name", p.name)
        .put("state", JSONObject(p.state.toJson())).toString()

    suspend fun import(json: String): Preset? = runCatching {
        val o = JSONObject(json)
        val state = editStateFromJson(o.getJSONObject("state").toString()).look.asPresetState()
        val p = Preset(o.getString("name"), state)
        save(p.name, p.state); p
    }.getOrNull()
}
