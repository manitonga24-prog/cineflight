package ca.cineflight.stage.control

import ca.cineflight.stage.cine.ClientRtkSujet
import ca.cineflight.stage.cine.ClientRtkSujet.StatutRtk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

/**
 * ============================ TESTS SANS PITIE ============================
 * On ne teste PAS le chemin heureux. On balance des saloperies (NaN, +/-Infini,
 * valeurs absurdes, champs manquants) et on exige que la DOCTRINE DE SECURITE
 * tienne : "non defini = non sur", fail-closed.
 *
 * >>> AVERTISSEMENT <<<
 * Les methodes prefixees EXPOSE_ sont ecrites pour ECHOUER sur le code actuel.
 * Chaque echec est une VRAIE FAILLE : une valeur NaN qui passe a travers un
 * garde-fou "if (x > seuil) bloque", parce qu'en IEEE 754 "NaN > seuil" est faux.
 * Les methodes prefixees ROBUSTE_ doivent PASSER (la ou le code se defend bien).
 *
 * Lancer SEUL :  .\gradlew testDebugUnitTest --tests "*SansPitieTest" -i
 * =========================================================================
 */
class SansPitieTest {

    // ---- fabriques minimales ----
    private fun demande(mvt: String?, ts: Double?) = DemandeMouvement(
        ok = true, deviceId = "d", kind = "k", movement = mvt, source = "s",
        safetyNote = null, serverRxTs = ts, rawJson = "{}")
    private fun rtk(present: Boolean = true, rtk: String? = "FIX", ageS: Double? = 1.0) =
        RtkSujet(present = present, lat = 45.0, lon = -73.0, altM = 100.0,
            rtk = rtk, ageS = ageS, hz = 10.0, rawJson = "{}")
    private fun maintenantS() = System.currentTimeMillis() / 1000.0

    private val autor = AutorisationControleRtkVision()
    private fun posFloat(ageS: Double? = 0.1, rateHz: Double? = 10.0) =
        ClientRtkSujet.PositionSujet(
            present = true, valid = true, lat = 45.0, lon = -73.0, rtk = StatutRtk.FLOAT,
            ageS = ageS, streamStatus = "LIVE", sourceSequence = 1L, measuredRateHz = rateHz,
            haccM = 0.30, rtcmAgeMs = 500L, groundSpeedMps = 10.0, capDeg = 90.0,
            headingValid = true, networkConnected = true)

    private val gen = GenerateurMouvement()
    private fun distSujet(c: GenerateurMouvement.Cible): Double {
        val mLon = 111_320.0 * cos(Math.toRadians(45.0))
        return hypot((c.lon - (-73.0)) * mLon, (c.lat - 45.0) * 111_320.0)
    }

    // =====================================================================
    //  FAILLES ATTENDUES (ROUGE = vraie faille a corriger)
    // =====================================================================

    /** serverRxTs = NaN (champ serveur manquant) : NaN > 30 == false -> fraicheur contournee. */
    @Test fun EXPOSE_timestamp_NaN_ne_doit_pas_etre_traite_comme_frais() {
        val v = MouvementSafetyValidator.validerDemande(demande("PAUSE_HOVER", Double.NaN), rtk())
        assertFalse("un timestamp NaN ne doit JAMAIS donner une demande autorisee", v.autorise)
    }

    /** age RTK = NaN sur un MOUVEMENT REEL (ORBITE_LARGE atteint la garde RTK ; PAUSE_HOVER
     *  serait court-circuite avant). NaN > 2.5 == false -> doit etre bloque par !isFinite. */
    @Test fun EXPOSE_age_position_NaN_ne_doit_pas_passer() {
        val v = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS()), rtk(ageS = Double.NaN))
        assertFalse("un age de position NaN ne doit pas etre autorise", v.autorise)
        assertEquals("BLOCKED_RTK_TOO_OLD", v.status)
    }

    /** Autorisation : age NaN -> le gate "age > MAX" est saute -> FLOAT autorise a tort. */
    @Test fun EXPOSE_autorisation_age_NaN_doit_bloquer() {
        val v = autor.evaluer(posFloat(ageS = Double.NaN), predictionControlReady = true,
            cibleVerrouillee = true, visionRequise = true, yoloTrouve = true,
            confianceYolo = 0.9f, nbCibles = 1, hauteurBoite = 0.30f)
        assertEquals("age NaN => non defini => doit etre BLOQUE (fail-closed)",
            AutorisationControleRtkVision.Mode.BLOQUE, v.mode)
    }

    /** Autorisation : cadence NaN -> le gate "rate < MIN" est saute -> FLOAT autorise a tort. */
    @Test fun EXPOSE_autorisation_cadence_NaN_doit_bloquer() {
        val v = autor.evaluer(posFloat(rateHz = Double.NaN), predictionControlReady = true,
            cibleVerrouillee = true, visionRequise = true, yoloTrouve = true,
            confianceYolo = 0.9f, nbCibles = 1, hauteurBoite = 0.30f)
        assertEquals("cadence NaN => non defini => doit etre BLOQUE",
            AutorisationControleRtkVision.Mode.BLOQUE, v.mode)
    }

    /** Generateur : hauteur NaN -> coerceIn(NaN) == NaN -> l'altitude "bornee" ne l'est pas. */
    @Test fun EXPOSE_generateur_altitude_NaN_doit_rester_bornee() {
        val p = GenerateurMouvement.Params(
            GenerateurMouvement.TypeMouvement.ORBITE, hauteurM = Double.NaN)
        val c = gen.cible(45.0, -73.0, p, 0.0)
        assertTrue("altitude NaN : la borne de securite doit produire une valeur FINIE",
            c.altM.isFinite() && c.altM in p.altMinM..p.altMaxM)
    }

    /** Generateur : distance NaN -> "if (dist < min)" est faux pour NaN -> cible NaN. */
    @Test fun EXPOSE_generateur_distance_NaN_doit_rester_finie() {
        val p = GenerateurMouvement.Params(
            GenerateurMouvement.TypeMouvement.RAPPROCHE,
            distanceM = Double.NaN, distanceFinM = Double.NaN)
        val c = gen.cible(45.0, -73.0, p, 0.5)
        assertTrue("distance NaN : la cible drone doit rester finie", distSujet(c).isFinite())
    }

    /** TraductionAxes : aucune garde de finitude -> NaN en entree = NaN en sortie.
     *  (Attenue en aval par PiloteDrone.finiOuZero, mais la fonction elle-meme ne garantit rien.) */
    @Test fun EXPOSE_traduction_axes_NaN_ne_doit_pas_sortir_NaN() {
        val d = TraductionAxes.versDji(Float.NaN, 1f, 2f, 3f, 45f)
        assertTrue("une entree NaN ne devrait pas produire une commande NaN",
            d.pitch.isFinite() && d.roll.isFinite())
    }

    // =====================================================================
    //  ROBUSTESSE REELLE (VERT = le code se defend bien)
    // =====================================================================

    /** La geofence est NaN-safe : un point NaN n'est jamais "dedans". */
    @Test fun ROBUSTE_geofence_refuse_un_point_NaN() {
        val g = GeoBarriere(listOf(
            GeoBarriere.Point(0.0, 0.0), GeoBarriere.Point(0.0, 0.001),
            GeoBarriere.Point(0.001, 0.001), GeoBarriere.Point(0.001, 0.0)))
        assertFalse("un point NaN ne doit jamais etre dedans",
            g.estDedans(GeoBarriere.Point(Double.NaN, Double.NaN)))
    }

    /** Une valeur ENORME mais FINIE est bien bloquee (mouvement reel atteint la garde). */
    @Test fun ROBUSTE_age_gigantesque_mais_fini_est_bloque() {
        val v = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS()), rtk(ageS = 1e18))
        assertFalse("un age enorme (fini) doit etre refuse", v.autorise)
        assertEquals("BLOCKED_RTK_TOO_OLD", v.status)
    }

    /** TROU CORRIGE : age RTK NEGATIF fini (position "du futur" = donnee corrompue).
     *  -0.001 est fini et n'est pas > 2.5 -> passait la garde AVANT le fix. Doit bloquer. */
    @Test fun EXPOSE_age_rtk_negatif_doit_etre_bloque() {
        val v = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS()), rtk(ageS = -0.001))
        assertFalse("un age RTK negatif ne doit jamais etre autorise", v.autorise)
        assertEquals("BLOCKED_RTK_TOO_OLD", v.status)
    }

    /** Demande dont serverRxTs est loin dans le FUTUR (age <= -2 s) -> rejet. */
    @Test fun EXPOSE_demande_futur_lointain_doit_etre_bloquee() {
        // serverRxTs = maintenant + 2.1 s -> ageDemande ~ -2.1 s.
        val v = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS() + 2.1), rtk())
        assertFalse("un timestamp 2.1s dans le futur doit etre bloque", v.autorise)
        assertEquals("BLOCKED_STALE_REQUEST", v.status)
    }

    /** Skew d'horloge MINEUR (age ~ -1 s) : accepte MAIS avertissement CLOCK_SKEW. */
    @Test fun ROBUSTE_skew_horloge_mineur_accepte_avec_avertissement() {
        val v = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS() + 1.0), rtk())
        assertTrue("un skew mineur (-1s) reste autorise", v.autorise)
        assertTrue("un avertissement d'horloge doit etre present",
            v.avertissement?.contains("CLOCK_SKEW_FUTURE_TIMESTAMP") == true)
    }

    /** GPS pur : toujours bloque, quel que soit le reste. */
    @Test fun ROBUSTE_gps_est_toujours_bloque() {
        val v = autor.evaluer(posFloat().copy(rtk = StatutRtk.GPS),
            predictionControlReady = true, cibleVerrouillee = true, visionRequise = true,
            yoloTrouve = true, confianceYolo = 0.9f, nbCibles = 1, hauteurBoite = 0.30f)
        assertEquals(AutorisationControleRtkVision.Mode.BLOQUE, v.mode)
    }
}
