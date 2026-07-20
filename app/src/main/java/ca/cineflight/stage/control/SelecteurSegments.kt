package ca.cineflight.stage.control

import android.net.Uri
import ca.cineflight.stage.control.AnalyseurMontage.ClipScore

/**
 * SelecteurSegments - transforme les scores YOLO (par seconde) en segments a monter.
 * Trois strategies : BEST_OF, UN_PAR_CLIP, ADAPTATIF.
 */
object SelecteurSegments {

    /** Un segment a inclure dans le montage final. */
    data class Segment(val uri: Uri, val debutMs: Long, val finMs: Long)

    enum class Mode { BEST_OF, UN_PAR_CLIP, ADAPTATIF }

    /**
     * @param scores resultats de l'analyseur (1 score par seconde par clip)
     * @param mode strategie de selection
     * @param dureeCibleSec duree totale visee (BEST_OF) ou duree par clip (UN_PAR_CLIP)
     * @param seuil score minimum pour qu'une seconde soit "bonne" (ADAPTATIF)
     */
    fun choisir(
        scores: List<ClipScore>,
        mode: Mode,
        dureeCibleSec: Int = 30,
        dureePclipSec: Int = 4,
        seuil: Float = 0.45f
    ): List<Segment> {
        return when (mode) {
            Mode.UN_PAR_CLIP -> unParClip(scores, dureePclipSec)
            Mode.ADAPTATIF -> adaptatif(scores, seuil)
            Mode.BEST_OF -> bestOf(scores, dureeCibleSec)
        }
    }

    /** Pour chaque clip : la meilleure fenetre continue de duree donnee. */
    private fun unParClip(scores: List<ClipScore>, dureeSec: Int): List<Segment> {
        val out = ArrayList<Segment>()
        val maxSec = if (dureeSec > 0) dureeSec else 12   // plafond de securite
        for (cs in scores) {
            val sc = cs.scoresParSeconde
            val n = sc.size
            if (n == 0) continue
            // 1) trouver le pic de qualite (meilleure seconde)
            var pic = 0; var meilleur = sc[0]
            for (i in 1 until n) if (sc[i] > meilleur) { meilleur = sc[i]; pic = i }
            // seuil relatif : on etend tant que c'est "assez bon" autour du pic
            val seuilLocal = (meilleur * 0.55f).coerceAtLeast(0.3f)
            var d = pic; var fEnd = pic
            // etendre vers la gauche
            while (d - 1 >= 0 && sc[d - 1] >= seuilLocal && (fEnd - (d - 1) + 1) <= maxSec) d--
            // etendre vers la droite
            while (fEnd + 1 < n && sc[fEnd + 1] >= seuilLocal && (fEnd + 1 - d + 1) <= maxSec) fEnd++
            // au moins 2 s
            if (fEnd - d + 1 < 2) { fEnd = (d + 1).coerceAtMost(n - 1) }
            out.add(Segment(cs.uri, d * 1000L, (fEnd + 1) * 1000L))
        }
        return out
    }

    /** Toutes les plages continues au-dessus du seuil (montage plus ou moins long). */
    private fun adaptatif(scores: List<ClipScore>, seuil: Float): List<Segment> {
        val out = ArrayList<Segment>()
        for (cs in scores) {
            val sc = cs.scoresParSeconde
            var debut = -1
            for (i in sc.indices) {
                val bon = sc[i] >= seuil
                if (bon && debut < 0) debut = i
                if ((!bon || i == sc.lastIndex) && debut >= 0) {
                    val fin = if (bon && i == sc.lastIndex) i + 1 else i
                    if (fin - debut >= 2) {  // au moins 2 s pour eviter les flashs
                        out.add(Segment(cs.uri, debut * 1000L, fin * 1000L))
                    }
                    debut = -1
                }
            }
        }
        // si rien ne passe le seuil, repli : meilleur segment par clip (3 s)
        if (out.isEmpty()) return unParClip(scores, 3)
        return out
    }

    /** Best-of : les meilleures secondes tous clips confondus, jusqu'a la duree cible. */
    private fun bestOf(scores: List<ClipScore>, dureeCibleSec: Int): List<Segment> {
        // 1) lister toutes les secondes (clip, seconde, score)
        data class Sec(val ci: Int, val s: Int, val score: Float)
        val toutes = ArrayList<Sec>()
        for ((ci, cs) in scores.withIndex())
            for (s in cs.scoresParSeconde.indices)
                toutes.add(Sec(ci, s, cs.scoresParSeconde[s]))
        if (toutes.isEmpty()) return emptyList()

        // 2) garder les meilleures jusqu'a la duree cible
        val gardees = toutes.sortedByDescending { it.score }.take(dureeCibleSec)
            .filter { it.score > 0.15f }   // ignorer le vide total
        if (gardees.isEmpty()) return bestOf2Repli(scores, dureeCibleSec)

        // 3) regrouper en segments continus, par clip, en ordre chronologique
        val parClip = gardees.groupBy { it.ci }
        val out = ArrayList<Segment>()
        for ((ci, secs) in parClip) {
            val uri = scores[ci].uri
            val tries = secs.map { it.s }.sorted()
            var debut = tries.first(); var prev = tries.first()
            for (idx in 1 until tries.size) {
                val cur = tries[idx]
                if (cur == prev + 1) { prev = cur }
                else {
                    out.add(Segment(uri, debut * 1000L, (prev + 1) * 1000L))
                    debut = cur; prev = cur
                }
            }
            out.add(Segment(uri, debut * 1000L, (prev + 1) * 1000L))
        }
        // ordonner les segments par clip puis par temps (ordre de tournage)
        return out.sortedWith(compareBy({ scores.indexOfFirst { c -> c.uri == it.uri } }, { it.debutMs }))
    }

    private fun bestOf2Repli(scores: List<ClipScore>, dureeCibleSec: Int): List<Segment> {
        val parClip = (dureeCibleSec / scores.size.coerceAtLeast(1)).coerceAtLeast(2)
        return unParClip(scores, parClip)
    }
}
