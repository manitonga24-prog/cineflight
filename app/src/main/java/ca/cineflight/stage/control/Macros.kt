package ca.cineflight.stage.control

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Macros - definit et stocke les sequences d'actions personnalisables.
 *
 * Une macro est associee a un tag (11..30) et contient une liste d'etapes.
 * Chaque etape = une action (meme vocabulaire que les boutons/tags) + une
 * duree d'attente apres (en secondes) avant l'etape suivante.
 *
 * Actions possibles (chaines) : "statique","orbite","travelling","revelation",
 * "approche","recul","poursuite","plan_gros","plan_americain","plan_pied",
 * "plan_ensemble","suivi","pause","stop".
 *
 * Stockage : SharedPreferences en JSON -> persiste entre les sessions.
 */
class Macros(context: Context) {

    data class Etape(val action: String, val attenteS: Float)
    data class Macro(val tagId: Int, val nom: String, val etapes: List<Etape>)

    private val prefs = context.getSharedPreferences("cineflight_macros", Context.MODE_PRIVATE)

    fun sauver(macro: Macro) {
        val arr = JSONArray()
        for (e in macro.etapes) {
            arr.put(JSONObject().apply {
                put("action", e.action)
                put("attente", e.attenteS.toDouble())
            })
        }
        val obj = JSONObject().apply {
            put("tagId", macro.tagId)
            put("nom", macro.nom)
            put("etapes", arr)
        }
        prefs.edit().putString("macro_${macro.tagId}", obj.toString()).apply()
    }

    fun charger(tagId: Int): Macro? {
        val s = prefs.getString("macro_$tagId", null) ?: return null
        return try {
            val obj = JSONObject(s)
            val arr = obj.getJSONArray("etapes")
            val etapes = ArrayList<Etape>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                etapes.add(Etape(o.getString("action"), o.getDouble("attente").toFloat()))
            }
            Macro(obj.getInt("tagId"), obj.optString("nom", "Macro $tagId"), etapes)
        } catch (e: Exception) { null }
    }

    fun supprimer(tagId: Int) {
        prefs.edit().remove("macro_$tagId").apply()
    }

    // liste des tags personnalisables qui ont une macro definie
    fun tagsAvecMacro(): List<Int> {
        return (11..30).filter { prefs.contains("macro_$it") }
    }

    companion object {
        val PLAGE_PERSO = 11..30   // tags reserves aux macros
        val ACTIONS = listOf(
            "statique", "orbite", "travelling", "revelation", "approche", "recul", "poursuite",
            "plan_gros", "plan_americain", "plan_pied", "plan_ensemble",
            "suivi", "pause", "stop"
        )
    }
}
