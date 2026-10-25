package app.gitbuilder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Histórico em arquivos JSON no armazenamento interno do app (não é apagado ao limpar cache). */
object History {
    private fun dir(c: Context) = File(c.filesDir, "history").apply { mkdirs() }
    private fun atomic(f: File, text: String) {
        val tmp = File(f.path + ".tmp"); tmp.writeText(text); tmp.renameTo(f)
    }
    fun saveMeta(c: Context, m: JSONObject) = atomic(File(dir(c), m.getString("id") + ".meta.json"), m.toString())
    fun saveStages(c: Context, id: String, s: JSONArray) = atomic(File(dir(c), "$id.stages.json"), s.toString())
    fun meta(c: Context, id: String): JSONObject? = try { JSONObject(File(dir(c), "$id.meta.json").readText()) } catch (e: Exception) { null }
    fun stages(c: Context, id: String): JSONArray = try { JSONArray(File(dir(c), "$id.stages.json").readText()) } catch (e: Exception) { JSONArray() }
    fun list(c: Context): List<JSONObject> =
        (dir(c).listFiles { f -> f.name.endsWith(".meta.json") } ?: emptyArray())
            .mapNotNull { try { JSONObject(it.readText()) } catch (e: Exception) { null } }
            .sortedByDescending { it.optLong("startedAt") }
    fun delete(c: Context, id: String) {
        File(dir(c), "$id.meta.json").delete(); File(dir(c), "$id.stages.json").delete()
    }
}
