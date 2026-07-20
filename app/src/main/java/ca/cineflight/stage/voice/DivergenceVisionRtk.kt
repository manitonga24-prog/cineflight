package ca.cineflight.stage.voice

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * PHASE 3B (complement) — Detection de DIVERGENCE entre la VISION (YOLO) et le RTK sujet.
 *
 * PROBLEME : une position RTK valide ET une detection YOLO valide peuvent pointer DEUX
 * objets differents (le RTK suit la balise sur la vraie cible, mais YOLO a verrouille
 * quelqu'un d'autre a l'ecran, ou l'inverse). Dans ce cas, l'app NE DOIT PAS presenter
 * l'automatisation comme fiable. Ce moniteur le detecte et alerte « Vision et RTK
 * incoherents ». Il OBSERVE seulement : aucune action sur le suivi.
 *
 * METHODE (angulaire horizontale, robuste, sans altitude ni yaw nacelle) :
 *   - angle attendu du sujet = cap(drone -> sujet RTK) - cap du drone  (ou pointe la camera)
 *   - angle observe du sujet  = (cxBoite - 0.5) * FOV_horizontal        (position YOLO a l'ecran)
 *   - divergence = |angleAttendu - angleObserve| (normalise a +-180)
 * Si la divergence depasse seuilDeg de facon STABLE pendant framesStables observations
 * successives, on annonce l'incoherence. Retour a la coherence apres framesRetour
 * observations sous le seuil (avec marge d'hysteresis).
 *
 * ISOLATION : classe pure (aucune dependance Android/DJI). Fail-open. L'appelant fournit
 * les primitives (positions + cxBoite) et l'etat de validite ; on ne calcule rien si une
 * entree manque.
 */
class DivergenceVisionRtk(
    private val systeme: FlightVoiceSystem
) {
    @Volatile var fovHorizontalDeg: Double = 73.0     // aligne sur la constante existante
    @Volatile var seuilDeg: Double = 20.0             // au-dela = suspect
    @Volatile var hysteresisDeg: Double = 6.0         // pour repasser "coherent"
    @Volatile var framesStables: Int = 5              // ~5 obs avant d'alerter (anti-bruit)
    @Volatile var framesRetour: Int = 5               // ~5 obs sous le seuil avant de degager

    @Volatile private var compteurDivergent = 0
    @Volatile private var compteurCoherent = 0
    @Volatile private var incoherentAnnonce = false

    private fun actif(): Boolean = try {
        systeme.settings.active && systeme.settings.observationDji
    } catch (_: Throwable) { false }

    /** Ramene un angle a l'intervalle [-180, 180]. */
    private fun normaliser180(deg: Double): Double {
        var a = deg % 360.0
        if (a > 180.0) a -= 360.0
        if (a < -180.0) a += 360.0
        return a
    }

    /** Cap (0..360) de A vers B en WGS84 (great-circle). */
    private fun capDeg(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val p1 = Math.toRadians(latA); val p2 = Math.toRadians(latB)
        val dLon = Math.toRadians(lonB - lonA)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * A appeler a chaque frame ou l'on a A LA FOIS une position RTK sujet valide ET une
     * boite YOLO selectionnee valide. Si l'un des deux manque, NE PAS appeler (ou appeler
     * reinitialiser()) : la divergence n'a de sens que quand les deux sont presents.
     *
     * @param droneLat/droneLon position drone (WGS84).
     * @param droneCapDeg cap (yaw) du drone en degres = azimut approx. de la camera.
     * @param sujetLat/sujetLon position RTK du sujet (WGS84).
     * @param cxBoite centre horizontal de la boite YOLO selectionnee, normalise 0..1.
     * @param nowMs horodatage.
     */
    fun observer(
        droneLat: Double, droneLon: Double, droneCapDeg: Double,
        sujetLat: Double, sujetLon: Double,
        cxBoite: Double, nowMs: Long
    ) {
        runCatching {
            if (!actif()) return
            if (droneLat.isNaN() || droneLon.isNaN() || droneCapDeg.isNaN() ||
                sujetLat.isNaN() || sujetLon.isNaN() || cxBoite.isNaN()) return
            if (cxBoite < 0.0 || cxBoite > 1.0) return

            // Angle attendu (RTK) : ou devrait etre le sujet par rapport a l'axe camera.
            val capVersSujet = capDeg(droneLat, droneLon, sujetLat, sujetLon)
            val angleAttendu = normaliser180(capVersSujet - droneCapDeg)
            // Angle observe (vision) : ou est la boite YOLO a l'ecran.
            val angleObserve = (cxBoite - 0.5) * fovHorizontalDeg
            val divergence = abs(normaliser180(angleAttendu - angleObserve))

            val seuilHaut = seuilDeg
            val seuilBas = seuilDeg - hysteresisDeg

            if (!incoherentAnnonce) {
                if (divergence >= seuilHaut) {
                    compteurDivergent += 1
                    compteurCoherent = 0
                    if (compteurDivergent >= framesStables) {
                        incoherentAnnonce = true
                        pubIncoherence(nowMs)
                    }
                } else {
                    compteurDivergent = 0
                }
            } else {
                // Deja annonce incoherent : attendre un retour STABLE sous le seuil bas.
                if (divergence <= seuilBas) {
                    compteurCoherent += 1
                    compteurDivergent = 0
                    if (compteurCoherent >= framesRetour) {
                        incoherentAnnonce = false
                        // Pas d'annonce de "retour a la coherence" : on redevient silencieux.
                    }
                } else {
                    compteurCoherent = 0
                }
            }
        }
    }

    private fun pubIncoherence(nowMs: Long) {
        try { systeme.publishVoiceEventSafely(VoiceEvents.fusionIncoherent(nowMs), nowMs) } catch (_: Throwable) {}
    }

    /** A appeler quand la vision OU le RTK n'est plus valide : la comparaison n'a plus de sens. */
    fun reinitialiser() {
        runCatching {
            compteurDivergent = 0
            compteurCoherent = 0
            // On garde incoherentAnnonce : si l'incoherence etait posee, elle se degagera
            // proprement au retour de donnees valides et stables.
        }
    }

    fun nouvelleSessionVol() {
        runCatching {
            compteurDivergent = 0; compteurCoherent = 0; incoherentAnnonce = false
        }
    }
}
