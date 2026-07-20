package ca.cineflight.stage.control

import ca.cineflight.stage.sentinelle.EtatCapteurs
import ca.cineflight.stage.sentinelle.NoyauSecurite
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * ExecuteurMouvement — le maillon qui fait BOUGER le drone (étape 5, final).
 *
 * >>> LE MODULE LE PLUS SENSIBLE DU PROJET <<<
 * Il transforme une CIBLE (position visée par un mouvement) en INTENTION de vitesse,
 * et la SOUMET AU NOYAU. Il n'appelle JAMAIS pont.envoyerVitesses directement :
 * seul le noyau commande le drone (soumettreIntention -> envoyerVitesses si sûr).
 *
 * Chaîne à chaque tick (~10 Hz) :
 *   1. cible = generateur.cible(position sujet RTK, mouvement, phase)   [prouvé]
 *   2. cible = geoBarriere.ecreterCible(position drone, cible)          [prouvé]
 *   3. vecteur monde (est/nord/haut) = cible - position drone
 *   4. -> vitesses drone (projection selon le cap) PLAFONNÉES + rampe + zone morte
 *   5. noyau.soumettreIntention(...) -> le noyau valide OU gèle (0,0,0,0)
 *
 * GARDE-FOUS : vitesse max lente (personne à pied), rampe douce (pas d'à-coups),
 * zone morte (pas de tremblement près de la cible), écrêtage géo-barrière AVANT
 * l'intention, tout via le noyau. TEST SIMULATEUR OBLIGATOIRE avant tout vol réel.
 *
 * >>> CONVENTION DES AXES (à VÉRIFIER au simulateur) <<<
 * Virtual stick DJI (mode courant supposé) :
 *   pitch  > 0 = avancer (nez)      roll   > 0 = aller à droite
 *   throttle > 0 = monter           yaw    > 0 = tourner à droite
 * Les signes sont PARAMÉTRABLES (signePitch/signeRoll/...) : si un axe est inversé
 * au test simulateur, on corrige ici sans toucher au reste. NE PAS voler en réel
 * tant que le simulateur n'a pas confirmé chaque direction.
 */
class ExecuteurMouvement(
    private val noyau: NoyauSecurite,
    private val cfg: Config = Config()
) {

    data class Config(
        val vitesseMaxMs: Double = 1.5,     // personne à pied (marche ~1.4 m/s)
        val vitesseMonteeMaxMs: Double = 1.0,
        val vitesseYawMaxDegS: Double = 45.0,
        val zoneMorteM: Double = 0.5,       // sous cette distance à la cible : pas de translation
        val zoneMorteAltM: Double = 0.5,
        val rampeMaxParTick: Double = 0.15, // variation max de vitesse par tick (douceur)
        val margeGeoM: Double = 5.0,        // marge intérieure de la géo-barrière
        // signes des axes (corrigés au simulateur si besoin)
        val signePitch: Double = 1.0,
        val signeRoll: Double = 1.0,
        val signeThrottle: Double = 1.0,
        val signeYaw: Double = 1.0
    )

    // état de rampe (dernières vitesses envoyées, pour lisser).
    private var vPitch = 0.0
    private var vRoll = 0.0
    private var vThrottle = 0.0

    /** Résultat d'un tick (pour l'affichage / le journal). */
    data class ResultatTick(
        val transmis: Boolean,          // le noyau a-t-il transmis (true) ou gelé (false) ?
        val distanceCibleM: Double,     // distance restante à la cible
        val cibleRamenee: Boolean,      // la cible a-t-elle été bridée par la géo-barrière ?
        val pitch: Double, val roll: Double, val throttle: Double, val yaw: Double
    )

    /**
     * Un tick de suivi. NE bouge le drone QUE si le noyau valide.
     *
     * @param droneLat/droneLon/droneAltM/droneCapDeg position + cap du drone (feedback).
     * @param cible cible visée (déjà calculée par GenerateurMouvement).
     * @param geo géo-barrière (la cible est écrêtée avant l'intention).
     * @param capteurs état capteurs pour le noyau (RTK, corridor, zone... déjà agrégés).
     */
    fun tick(
        droneLat: Double, droneLon: Double, droneAltM: Double, droneCapDeg: Double,
        cible: GenerateurMouvement.Cible,
        geo: GeoBarriere,
        capteurs: EtatCapteurs
    ): ResultatTick {
        // 1. ÉCRÊTAGE GÉO-BARRIÈRE : la cible ne doit jamais être hors zone.
        val cibleGeo = geo.ecreterCible(
            GeoBarriere.Point(droneLat, droneLon),
            GeoBarriere.Point(cible.lat, cible.lon),
            cfg.margeGeoM
        )
        val cLat = cibleGeo.point.lat
        val cLon = cibleGeo.point.lon

        // 2. VECTEUR MONDE (est, nord, haut) du drone vers la cible, en mètres.
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(droneLat))
        val est = (cLon - droneLon) * mLon
        val nord = (cLat - droneLat) * mLat
        val dHaut = cible.altM - droneAltM
        val distHoriz = hypot(est, nord)

        // 3. VITESSES CIBLES (avant rampe). Zone morte -> 0 (pas de tremblement).
        var cmdPitch = 0.0   // avancer/reculer (repère drone)
        var cmdRoll = 0.0    // droite/gauche (repère drone)
        if (distHoriz > cfg.zoneMorteM) {
            // vitesse proportionnelle à la distance, plafonnée à vitesseMax.
            val v = minOf(cfg.vitesseMaxMs, distHoriz * 0.5)   // gain doux
            // direction monde normalisée
            val ux = est / distHoriz
            val uy = nord / distHoriz
            // PROJECTION monde -> repère drone selon le cap (0=N, 90=E).
            // avant du drone = direction du cap. Composante avant = projection sur
            // le vecteur cap ; composante droite = projection sur la perpendiculaire.
            val capRad = Math.toRadians(droneCapDeg)
            // vecteur "avant" du drone en (est, nord) : (sin(cap), cos(cap)).
            val avEst = sin(capRad); val avNord = cos(capRad)
            // vecteur "droite" du drone : (cos(cap), -sin(cap)).
            val drEst = cos(capRad); val drNord = -sin(capRad)
            val compAvant = ux * avEst + uy * avNord      // projection sur l'avant
            val compDroite = ux * drEst + uy * drNord     // projection sur la droite
            cmdPitch = v * compAvant
            cmdRoll = v * compDroite
        }
        // throttle (montée/descente)
        var cmdThrottle = 0.0
        if (abs(dHaut) > cfg.zoneMorteAltM) {
            cmdThrottle = (if (dHaut > 0) 1.0 else -1.0) *
                minOf(cfg.vitesseMonteeMaxMs, abs(dHaut) * 0.5)
        }
        // yaw : tourner vers le cap voulu (cible.yawDeg). erreur d'angle -> vitesse.
        var errYaw = cible.yawDeg - droneCapDeg
        while (errYaw > 180) errYaw -= 360
        while (errYaw < -180) errYaw += 360
        var cmdYaw = (errYaw / 180.0) * cfg.vitesseYawMaxDegS
        if (cmdYaw > cfg.vitesseYawMaxDegS) cmdYaw = cfg.vitesseYawMaxDegS
        if (cmdYaw < -cfg.vitesseYawMaxDegS) cmdYaw = -cfg.vitesseYawMaxDegS

        // 4. RAMPE DOUCE : limiter la variation par tick (pas d'à-coups).
        vPitch = rampe(vPitch, cmdPitch)
        vRoll = rampe(vRoll, cmdRoll)
        vThrottle = rampe(vThrottle, cmdThrottle)

        // 5. SIGNES d'axes (corrigés au simulateur si besoin).
        val pitch = (vPitch * cfg.signePitch)
        val roll = (vRoll * cfg.signeRoll)
        val throttle = (vThrottle * cfg.signeThrottle)
        val yaw = (cmdYaw * cfg.signeYaw)

        // 6. SOUMISSION AU NOYAU : lui seul décide de transmettre ou de geler.
        //    (aucun accès direct à pont.envoyerVitesses ici.)
        val raison = noyau.soumettreIntention(
            pitch, roll, throttle, yaw, capteurs)
        val transmis = raison == ca.cineflight.stage.sentinelle.RaisonBlocage.AUCUNE
        // si le noyau a gelé, on remet nos rampes à zéro (repartir doux au dégel).
        if (!transmis) { vPitch = 0.0; vRoll = 0.0; vThrottle = 0.0 }

        return ResultatTick(
            transmis = transmis,
            distanceCibleM = distHoriz,
            cibleRamenee = cibleGeo.ramenee,
            pitch = pitch, roll = roll, throttle = throttle, yaw = yaw
        )
    }

    /** Limite la variation d'une vitesse par tick (rampe douce). */
    private fun rampe(actuel: Double, cible: Double): Double {
        val d = cible - actuel
        return when {
            d > cfg.rampeMaxParTick -> actuel + cfg.rampeMaxParTick
            d < -cfg.rampeMaxParTick -> actuel - cfg.rampeMaxParTick
            else -> cible
        }
    }

    /** Remet les rampes à zéro (à l'arrêt d'un mouvement). */
    fun reinitialiser() { vPitch = 0.0; vRoll = 0.0; vThrottle = 0.0 }
}

