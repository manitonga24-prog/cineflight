package ca.cineflight.stage.voice

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PHASE 3 — Surveillance de la distance VLOS (visibilite directe).
 *
 * PRINCIPE : ce moniteur OBSERVE la distance horizontale drone<->pilote et publie
 * des evenements vocaux quand des seuils configurables sont franchis. Il ne commande
 * JAMAIS le drone (pas de RTH auto) : la decision reste au pilote. L'interface peut
 * afficher un bouton RTH bien visible, mais c'est tout.
 *
 * RAPPEL REGLEMENTAIRE : la limite configuree est OPERATIONNELLE, pas une garantie de
 * visibilite. La visibilite directe reelle peut etre perdue AVANT la limite configuree
 * (taille du drone, lumiere, meteo, relief). Ce moniteur n'affirme aucune conformite.
 *
 * DISTANCE UTILISEE : horizontale, entre la position ACTUELLE du drone et la position
 * ACTUELLE du pilote (telephone / telecommande). Ce n'est PAS forcement la distance au
 * point de decollage : si le pilote se deplace, on suit sa position GNSS courante.
 *
 * ISOLATION : classe pure (aucune dependance Android/DJI). Toutes les entrees arrivent
 * par observer(...). Testable a 100% hors appareil. Fail-open : si actif() est faux ou
 * si une entree est invalide, on ne publie rien (jamais d'exception propagee).
 *
 * Les 4 niveaux (defaut, limite 600 m) :
 *   NORMAL      < 70%           aucune annonce
 *   APPROCHE    >= 70%  (420 m) "Le drone s'eloigne"
 *   AVERT       >= 85%  (510 m) "Limite bientot atteinte"
 *   LIMITE      >= 100% (600 m) "Limite atteinte. Rapprochez le drone"
 *   DEPASSEMENT > 100%          "Au-dela de la limite. Revenez vers le pilote"
 *
 * ANTI-BAVARDAGE : on n'annonce qu'au CHANGEMENT de niveau. Le retour a un niveau
 * INFERIEUR exige une marge d'hysteresis (reArmMargeM) pour eviter les oscillations
 * autour d'un seuil. En depassement, si la distance CONTINUE d'augmenter, on repete
 * l'alerte au maximum toutes intervalleRepeatMs (defaut 18 s).
 */
class VlosDistanceMonitor(
    private val systeme: FlightVoiceSystem
) {
    // Niveaux internes (ordre = severite croissante).
    private enum class Niveau { NORMAL, APPROCHE, AVERT, LIMITE, DEPASSEMENT }

    // ---- reglage (modifiable ; defauts alignes sur la reco Canada/CineFlight) ----
    @Volatile var limiteM: Double = 600.0            // limite operationnelle configuree
    @Volatile var fractionApproche: Double = 0.70    // 70%
    @Volatile var fractionAvert: Double = 0.85       // 85%
    @Volatile var reArmMargeM: Double = 50.0         // hysteresis : redescendre exige -50 m
    // Marge (m) au-dela de la limite avant de passer de "limite atteinte" a "depassement".
    @Volatile var depassementMargeM: Double = 40.0
    // Precision GNSS pilote max toleree (m). Au-dela, distance jugee non fiable.
    @Volatile var precisionPiloteMaxM: Double = 30.0
    // Intervalle mini entre deux "distance augmente" en depassement.
    @Volatile var intervalleRepeatMs: Long = 18_000L
    // Increment mini de distance (m) pour considerer qu'elle "continue d'augmenter".
    @Volatile var incrementSignificatifM: Double = 15.0

    // ---- etat d'observation (pas de pilotage) ----
    @Volatile private var niveauPrecedent = Niveau.NORMAL
    @Volatile private var derniereDistanceM = Double.NaN
    @Volatile private var dernierRepeatMs = 0L
    @Volatile private var distanceAuDernierRepeatM = Double.NaN
    @Volatile private var pilotePeuFiablePrecedent = false

    private fun actif(): Boolean = try {
        systeme.settings.active && systeme.settings.observationDji
    } catch (_: Throwable) { false }

    private fun pub(e: FlightVoiceEvent, nowMs: Long) {
        try { systeme.publishVoiceEventSafely(e, nowMs) } catch (_: Throwable) {}
    }

    /** Distance horizontale (m) entre deux points WGS84 (haversine, rayon 6371 km). */
    private fun distanceHorizontaleM(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dP = Math.toRadians(lat2 - lat1)
        val dL = Math.toRadians(lon2 - lon1)
        val a = sin(dP / 2) * sin(dP / 2) +
                cos(p1) * cos(p2) * sin(dL / 2) * sin(dL / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Convertit une distance en niveau, SANS hysteresis (seuils bruts). */
    private fun niveauBrut(dM: Double): Niveau {
        val lim = limiteM
        return when {
            dM >= lim + depassementMargeM -> Niveau.DEPASSEMENT
            dM >= lim -> Niveau.LIMITE
            dM >= lim * fractionAvert -> Niveau.AVERT
            dM >= lim * fractionApproche -> Niveau.APPROCHE
            else -> Niveau.NORMAL
        }
    }

    /** Seuil (m) d'ENTREE d'un niveau (pour appliquer l'hysteresis a la descente). */
    private fun seuilEntreeM(n: Niveau): Double = when (n) {
        Niveau.NORMAL -> 0.0
        Niveau.APPROCHE -> limiteM * fractionApproche
        Niveau.AVERT -> limiteM * fractionAvert
        Niveau.LIMITE -> limiteM
        Niveau.DEPASSEMENT -> limiteM + depassementMargeM
    }

    /**
     * Entree principale. A appeler periodiquement (~1-3 Hz) avec les positions ACTUELLES.
     * @param droneLat/droneLon position drone (WGS84). NaN si pas de fix -> ignore.
     * @param piloteLat/piloteLon position pilote (telephone/RC). null si inconnue.
     * @param precisionPiloteM precision horizontale estimee de la position pilote (m).
     *        Passer NaN si inconnue (on ne bloque pas, mais on ne peut pas la valider).
     * @param nowMs horodatage (System.currentTimeMillis fourni par l'appelant).
     */
    fun observer(
        droneLat: Double, droneLon: Double,
        piloteLat: Double?, piloteLon: Double?,
        precisionPiloteM: Double,
        nowMs: Long
    ) {
        runCatching {
            if (!actif()) { return }
            // Position pilote absente -> on ne peut pas calculer : signale une fois.
            if (piloteLat == null || piloteLon == null ||
                piloteLat.isNaN() || piloteLon.isNaN() ||
                droneLat.isNaN() || droneLon.isNaN()) {
                signalerPilotePeuFiable(true, nowMs)
                return
            }
            // Precision insuffisante -> distance non fiable : signale une fois, pas d'alerte niveau.
            if (!precisionPiloteM.isNaN() && precisionPiloteM > precisionPiloteMaxM) {
                signalerPilotePeuFiable(true, nowMs)
                return
            }
            // La position est redevenue fiable : on efface l'etat (pas d'annonce de retour).
            signalerPilotePeuFiable(false, nowMs)

            val d = distanceHorizontaleM(droneLat, droneLon, piloteLat, piloteLon)
            if (d.isNaN() || d < 0) return

            val niveauCible = calculerNiveauAvecHysteresis(d)
            traiterTransition(niveauCible, d, nowMs)
            derniereDistanceM = d
        }
    }

    /** Applique l'hysteresis : monter est immediat ; descendre exige une marge. */
    private fun calculerNiveauAvecHysteresis(d: Double): Niveau {
        val brut = niveauBrut(d)
        val prec = niveauPrecedent
        if (brut.ordinal >= prec.ordinal) return brut   // montee (ou egal) : immediat
        // Descente : n'accepter le niveau inferieur que si on est repasse SOUS le
        // seuil d'entree du niveau courant MOINS la marge d'hysteresis.
        val seuilSortie = seuilEntreeM(prec) - reArmMargeM
        return if (d < seuilSortie) brut else prec
    }

    private fun traiterTransition(niveau: Niveau, d: Double, nowMs: Long) {
        val dInt = Math.round(d).toInt()
        val limInt = Math.round(limiteM).toInt()
        if (niveau != niveauPrecedent) {
            val montait = niveau.ordinal > niveauPrecedent.ordinal
            niveauPrecedent = niveau
            when (niveau) {
                Niveau.NORMAL -> pub(VoiceEvents.vlosRecovered(nowMs), nowMs)
                Niveau.APPROCHE -> if (montait) pub(VoiceEvents.vlosApproaching(nowMs, dInt, limInt), nowMs)
                                   else pub(VoiceEvents.vlosRecovered(nowMs), nowMs)
                Niveau.AVERT -> if (montait) pub(VoiceEvents.vlosWarning(nowMs, dInt, limInt), nowMs)
                                else pub(VoiceEvents.vlosRecovered(nowMs), nowMs)
                Niveau.LIMITE -> if (montait) pub(VoiceEvents.vlosLimitReached(nowMs, dInt, limInt), nowMs)
                                 else pub(VoiceEvents.vlosRecovered(nowMs), nowMs)
                Niveau.DEPASSEMENT -> {
                    pub(VoiceEvents.vlosExceeded(nowMs, dInt, limInt), nowMs)
                    dernierRepeatMs = nowMs
                    distanceAuDernierRepeatM = d
                }
            }
            return
        }
        // Meme niveau : seul le DEPASSEMENT peut se repeter, et UNIQUEMENT si la
        // distance continue d'augmenter de facon significative, a intervalle borne.
        if (niveau == Niveau.DEPASSEMENT) {
            val augmente = !distanceAuDernierRepeatM.isNaN() &&
                (d - distanceAuDernierRepeatM) >= incrementSignificatifM
            val delaiEcoule = (nowMs - dernierRepeatMs) >= intervalleRepeatMs
            if (augmente && delaiEcoule) {
                pub(VoiceEvents.vlosIncreasing(nowMs, dInt, limInt), nowMs)
                dernierRepeatMs = nowMs
                distanceAuDernierRepeatM = d
            }
        }
    }

    private fun signalerPilotePeuFiable(peuFiable: Boolean, nowMs: Long) {
        if (peuFiable && !pilotePeuFiablePrecedent) {
            pilotePeuFiablePrecedent = true
            pub(VoiceEvents.vlosPilotUnreliable(nowMs), nowMs)
        } else if (!peuFiable) {
            pilotePeuFiablePrecedent = false
        }
    }

    /** Reinitialise l'etat a chaque nouvelle session de vol (pas d'annonce residuelle). */
    fun nouvelleSessionVol() {
        runCatching {
            niveauPrecedent = Niveau.NORMAL
            derniereDistanceM = Double.NaN
            dernierRepeatMs = 0L
            distanceAuDernierRepeatM = Double.NaN
            pilotePeuFiablePrecedent = false
        }
    }
}
