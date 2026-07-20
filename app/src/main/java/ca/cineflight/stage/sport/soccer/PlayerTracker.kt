package ca.cineflight.stage.sport.soccer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PlayerTracker — SUIVI MULTI-JOUEURS (section 4), pur (aucun SDK/Android).
 *
 * Associe les detections d'une frame aux joueurs suivis pour donner une IDENTITE
 * persistante (evite que les ids changent quand les joueurs se croisent). Tracker
 * leger base IoU + proximite (pas de dependance ByteTrack). Gere apparition (nouveau
 * joueur) et disparition (age sans mise a jour -> retire).
 *
 * Entrees : boxes normalisees [0,1] (centre + taille). Deterministe (temps fourni).
 */
class PlayerTracker(
    /** IoU minimal pour associer une detection a un joueur existant. */
    private val iouMin: Float = 0.2f,
    /** Distance centre max (unites image) pour associer si l'IoU est faible. */
    private val distMax: Float = 0.08f,
    /** Age max sans mise a jour (ms) avant de retirer un joueur perdu. */
    private val ageMaxMs: Long = 800L,
    /** Lissage de position 0..1 (0=fige, 1=instantane). */
    private val lissage: Float = 0.5f,
) {

    /** Un joueur suivi : identite + position lissee + boite courante. */
    data class Joueur(
        val id: Int,
        val cx: Float, val cy: Float,
        val w: Float, val h: Float,
        val conf: Float,
        val vuLastMs: Long,
    )

    /** Detection d'entree (une box de classe person, normalisee). */
    data class Detection(val cx: Float, val cy: Float, val w: Float, val h: Float, val conf: Float)

    private val joueurs = LinkedHashMap<Int, Joueur>()
    private var prochainId = 1

    fun reset() { joueurs.clear(); prochainId = 1 }

    /** Joueurs actuellement suivis (frais). */
    fun joueurs(): List<Joueur> = joueurs.values.toList()

    /**
     * Ingere les detections d'une frame et met a jour le suivi. Retourne la liste
     * des joueurs suivis (avec identites persistantes).
     */
    fun update(detections: List<Detection>, nowMs: Long): List<Joueur> {
        // Assainissement : ignore les detections non finies / hors plage grossiere.
        val dets = detections.filter {
            it.cx.isFinite() && it.cy.isFinite() && it.w.isFinite() && it.h.isFinite() &&
                it.cx in -0.1f..1.1f && it.cy in -0.1f..1.1f
        }

        val nonAssignes = dets.toMutableList()
        val misAJour = HashSet<Int>()

        // 1) Associer chaque joueur existant a la MEILLEURE detection restante.
        for (j in joueurs.values.sortedByDescending { it.conf }) {
            var meilleur: Detection? = null
            var meilleurScore = 0f
            for (d in nonAssignes) {
                val iou = iou(j, d)
                val proche = distanceCentre(j, d) <= distMax
                // score = IoU, ou proximite si l'IoU est faible mais le centre proche.
                val score = if (iou >= iouMin) iou else if (proche) 0.15f else 0f
                if (score > meilleurScore) { meilleurScore = score; meilleur = d }
            }
            if (meilleur != null) {
                val d = meilleur
                nonAssignes.remove(d)
                misAJour.add(j.id)
                // position lissee (garde l'identite stable malgre le bruit).
                joueurs[j.id] = j.copy(
                    cx = j.cx + (d.cx - j.cx) * lissage,
                    cy = j.cy + (d.cy - j.cy) * lissage,
                    w = d.w, h = d.h, conf = d.conf, vuLastMs = nowMs,
                )
            }
        }

        // 2) Detections restantes -> nouveaux joueurs.
        for (d in nonAssignes) {
            val id = prochainId++
            joueurs[id] = Joueur(id, d.cx, d.cy, d.w, d.h, d.conf, nowMs)
        }

        // 3) Retirer les joueurs trop vieux (disparus).
        val it = joueurs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (nowMs - e.value.vuLastMs > ageMaxMs) it.remove()
        }
        return joueurs.values.toList()
    }

    // --- geometrie ---

    private fun iou(j: Joueur, d: Detection): Float {
        val jx1 = j.cx - j.w / 2f; val jy1 = j.cy - j.h / 2f
        val jx2 = j.cx + j.w / 2f; val jy2 = j.cy + j.h / 2f
        val dx1 = d.cx - d.w / 2f; val dy1 = d.cy - d.h / 2f
        val dx2 = d.cx + d.w / 2f; val dy2 = d.cy + d.h / 2f
        val ix = max(0f, min(jx2, dx2) - max(jx1, dx1))
        val iy = max(0f, min(jy2, dy2) - max(jy1, dy1))
        val inter = ix * iy
        val uni = j.w * j.h + d.w * d.h - inter
        return if (uni > 0f) inter / uni else 0f
    }

    private fun distanceCentre(j: Joueur, d: Detection): Float =
        max(abs(j.cx - d.cx), abs(j.cy - d.cy))
}
