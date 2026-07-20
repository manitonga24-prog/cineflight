package ca.cineflight.stage.cine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MissionsPrepareesStore — mini-bibliotheque locale des missions PRETES A VOLER.
 *
 * Une mission preparee = un KMZ deja genere (drone eteint, a l'avance) + son
 * contexte de lumiere. Persiste dans missions_preparees.json (survit a la
 * fermeture de l'app). Le vol reel se fait plus tard, sur place, drone allume.
 *
 * Separation stricte :
 *   PREPARER (ici)  : sauvegarder, drone eteint OK.
 *   VOLER (ailleurs): charger une mission, verifier drone+GPS+RTH, lancer.
 *
 * Le store ne fait AUCUNE verification de vol : c'est volontaire.
 */
class MissionsPrepareesStore(private val ctx: Context) {

    /** Une mission prete a voler. Tous les champs sont des donnees, pas du comportement. */
    data class MissionPreparee(
        val id: String,
        val nomMission: String,
        val kmzPath: String,
        val nomLieu: String,
        val momentCle: String,
        val momentNom: String,
        val momentDebut: String,
        val momentFin: String,
        val momentPic: String,
        val recap: String,
        val preparesLe: Long           // epoch ms
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("nomMission", nomMission)
            put("kmzPath", kmzPath)
            put("nomLieu", nomLieu)
            put("momentCle", momentCle)
            put("momentNom", momentNom)
            put("momentDebut", momentDebut)
            put("momentFin", momentFin)
            put("momentPic", momentPic)
            put("recap", recap)
            put("preparesLe", preparesLe)
        }

        companion object {
            fun fromJson(o: JSONObject): MissionPreparee = MissionPreparee(
                id = o.optString("id"),
                nomMission = o.optString("nomMission", ""),
                kmzPath = o.optString("kmzPath"),
                nomLieu = o.optString("nomLieu", "Lieu"),
                momentCle = o.optString("momentCle", ""),
                momentNom = o.optString("momentNom", ""),
                momentDebut = o.optString("momentDebut", ""),
                momentFin = o.optString("momentFin", ""),
                momentPic = o.optString("momentPic", ""),
                recap = o.optString("recap", "{}"),
                preparesLe = o.optLong("preparesLe", 0L)
            )
        }
    }

    private val fichier: File
        get() = File(ctx.filesDir, "missions_preparees.json")

    /** Lit toutes les missions. Robuste : fichier absent ou JSON corrompu -> liste vide. */
    fun lister(): List<MissionPreparee> {
        val f = fichier
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                try { MissionPreparee.fromJson(arr.getJSONObject(i)) } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Ajoute une mission (ou remplace si meme id). Retourne true si ecrit. */
    fun sauvegarder(m: MissionPreparee): Boolean {
        return try {
            val actuelles = lister().filter { it.id != m.id }.toMutableList()
            actuelles.add(0, m)   // plus recente en premier
            ecrire(actuelles)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Supprime une mission par id. Retourne true si la liste a change. */
    fun supprimer(id: String): Boolean {
        val actuelles = lister()
        val restantes = actuelles.filter { it.id != id }
        if (restantes.size == actuelles.size) return false
        return try { ecrire(restantes); true } catch (_: Exception) { false }
    }

    /** Recupere une mission par id, ou null. */
    fun parId(id: String): MissionPreparee? = lister().firstOrNull { it.id == id }

    /** Nettoie les missions dont le KMZ n'existe plus sur disque (menage). */
    fun nettoyerOrphelines(): Int {
        val actuelles = lister()
        val valides = actuelles.filter { File(it.kmzPath).exists() }
        if (valides.size == actuelles.size) return 0
        ecrire(valides)
        return actuelles.size - valides.size
    }

    private fun ecrire(liste: List<MissionPreparee>) {
        val arr = JSONArray()
        liste.forEach { arr.put(it.toJson()) }
        fichier.writeText(arr.toString())
    }

    companion object {
        /** Genere un id unique pour une nouvelle mission. */
        fun nouvelId(): String = "mp_" + System.currentTimeMillis() + "_" + (0..9999).random()
    }
}

