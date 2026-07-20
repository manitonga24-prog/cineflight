package ca.cineflight.stage.sentinelle

/**
 * NOYAU SÉCURITÉ — Couche 1 de la Sentinelle V2.
 *
 * Traduction Kotlin du module Python validé (46/46 tests).
 *
 * C'est le SOCLE et l'UNIQUE point de passage des commandes moteur. La couche 2
 * (MachineV2) ne commande JAMAIS le drone directement : elle SOUMET une intention,
 * et le noyau décide de la transmettre OU de la remplacer par (0,0,0,0).
 *
 * >>> RÈGLE GRAVÉE : le noyau gagne TOUJOURS. <<<
 * Priorité : STOP_PILOTE > INTRUSION > ERREUR_SYSTEME.
 * Tant qu'une condition de sécurité est active : envoyerVitesses(0,0,0,0).
 */

/** Pourquoi le noyau bloque (ou non) les commandes. */
enum class RaisonBlocage {
    AUCUNE,            // rien ne bloque, commande transmise
    STOP_PILOTE,
    INTRUSION,
    ERREUR_SYSTEME
}

/** Motif précis d'une ERREUR_SYSTEME (débogage des essais). */
enum class DetailErreur {
    AUCUN,
    VIDEO_PERDUE,
    YOLO_MUET,
    TELEMETRIE_PERDUE,
    VIDEO_PERIMEE,
    TELEMETRIE_PERIMEE,
    RTK_SUJET_PERDU       // AJOUT RTK : position RTK du sujet perdue OU drone sous la limite (3 m)
}

/**
 * Instantané des « sens » du drone, fourni à chaque cycle.
 * Tout ce qui manque / est faux = danger (doctrine « non vu != sûr »).
 */
data class EtatCapteurs(
    val videoOk: Boolean = true,
    val yoloActif: Boolean = true,
    val telemetrieOk: Boolean = true,
    val intrusionDetectee: Boolean = false,
    val stopPilote: Boolean = false,
    val ageVideoS: Double = 0.0,
    val ageTelemetrieS: Double = 0.0,
    // AJOUT RTK (option A) : le suivi du sujet RTK est-il sûr ? false =
    //   - position RTK perdue/perimee (FIX/FLOAT indisponible, signal 4G coupe), OU
    //   - drone passe sous la distance minimale de securite (3 m).
    // Defaut true : un vol SANS mode sujet mobile n'est pas affecte (le champ
    // n'est mis a false que si la couche sujet est active et signale un danger).
    val rtkSujetOk: Boolean = true
)

/** Une entrée du journal d'événements (changements de raison seulement). */
data class EvenementSecurite(
    val timestampMs: Long,
    val raison: RaisonBlocage,
    val detail: DetailErreur
)

class NoyauSecurite(private val pont: PontDrone) {

    companion object {
        // Seuils de fraîcheur : au-delà, le sens est considéré perdu. NON définitifs.
        const val SEUIL_VIDEO_PERIME_S = 0.5
        const val SEUIL_TELEMETRIE_PERIMEE_S = 0.5
    }

    private var derniereRaisonInterne = RaisonBlocage.AUCUNE
    private var enErreurVerrouillee = false          // une erreur verrouille jusqu'à acquittement
    private var detailErreurInterne = DetailErreur.AUCUN
    private val historiqueInterne = mutableListOf<EvenementSecurite>()

    val derniereRaison: RaisonBlocage get() = derniereRaisonInterne
    val detailErreur: DetailErreur get() = detailErreurInterne
    val historique: List<EvenementSecurite> get() = historiqueInterne.toList()

    // ------------------------------------------------------------------
    //  Évaluation de l'état de sécurité (pure décision, ne commande pas)
    // ------------------------------------------------------------------
    fun evaluer(c: EtatCapteurs): RaisonBlocage {
        // Met à jour le verrou d'erreur DÈS qu'une erreur est détectée, même si une
        // raison plus prioritaire est affichée ce cycle-ci (erreur survenue pendant
        // une intrusion reste verrouillée ensuite).
        val detail = detecterErreur(c)
        if (detail != DetailErreur.AUCUN) {
            enErreurVerrouillee = true
            detailErreurInterne = detail
        }

        // 1. STOP pilote : priorité absolue
        if (c.stopPilote) return RaisonBlocage.STOP_PILOTE
        // 2. Intrusion : événement immédiat, info la plus utile au pilote
        if (c.intrusionDetectee) return RaisonBlocage.INTRUSION
        // 3. Erreur système : verrouillée jusqu'à acquittement
        if (enErreurVerrouillee) return RaisonBlocage.ERREUR_SYSTEME

        return RaisonBlocage.AUCUNE
    }

    private fun detecterErreur(c: EtatCapteurs): DetailErreur {
        if (!c.videoOk) return DetailErreur.VIDEO_PERDUE
        if (!c.yoloActif) return DetailErreur.YOLO_MUET
        if (!c.telemetrieOk) return DetailErreur.TELEMETRIE_PERDUE
        if (!c.ageVideoS.isFinite() || c.ageVideoS > SEUIL_VIDEO_PERIME_S) return DetailErreur.VIDEO_PERIMEE
        if (!c.ageTelemetrieS.isFinite() || c.ageTelemetrieS > SEUIL_TELEMETRIE_PERIMEE_S) return DetailErreur.TELEMETRIE_PERIMEE
        // AJOUT RTK (option A) : sujet perdu OU drone trop proche -> danger, verrouille.
        if (!c.rtkSujetOk) return DetailErreur.RTK_SUJET_PERDU
        return DetailErreur.AUCUN
    }

    // ------------------------------------------------------------------
    //  Point de passage UNIQUE des commandes
    // ------------------------------------------------------------------
    /**
     * La couche V2 SOUMET une intention de mouvement.
     *  - si AUCUN danger : l'intention est transmise au drone ;
     *  - si danger : l'intention est IGNORÉE et (0,0,0,0) est envoyé.
     * Retourne la raison (AUCUNE = transmis, sinon = bloqué).
     */
    fun soumettreIntention(
        pitch: Double, roll: Double, throttle: Double, yaw: Double,
        capteurs: EtatCapteurs
    ): RaisonBlocage {
        val raison = evaluer(capteurs)
        derniereRaisonInterne = raison
        journaliser(raison)
        if (raison == RaisonBlocage.AUCUNE) {
            pont.envoyerVitesses(fini(pitch), fini(roll), fini(throttle), fini(yaw))
        } else {
            // GARANTIE : en cas de danger, vitesses nulles, toujours.
            pont.envoyerVitesses(0.0, 0.0, 0.0, 0.0)
        }
        return raison
    }

    /** Aucune valeur non finie ne franchit le point de passage. */
    private fun fini(v: Double): Double = if (v.isFinite()) v else 0.0

    /** Force (0,0,0,0) sans condition (STOP câblé en dur, hors logique). */
    fun arretImmediat() {
        pont.envoyerVitesses(0.0, 0.0, 0.0, 0.0)
    }

    /** Le pilote prend acte d'une erreur système : on déverrouille. */
    fun acquitterErreur() {
        enErreurVerrouillee = false
        detailErreurInterne = DetailErreur.AUCUN
    }

    // ------------------------------------------------------------------
    //  Journal (changements de raison seulement, pour ne pas spammer)
    // ------------------------------------------------------------------
    private fun journaliser(raison: RaisonBlocage) {
        val detail = if (raison == RaisonBlocage.ERREUR_SYSTEME) detailErreurInterne else DetailErreur.AUCUN
        val dernier = historiqueInterne.lastOrNull()
        if (dernier == null || dernier.raison != raison || dernier.detail != detail) {
            historiqueInterne.add(EvenementSecurite(System.currentTimeMillis(), raison, detail))
        }
    }
}

