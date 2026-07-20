package ca.cineflight.stage.sport.soccer

import ca.cineflight.stage.control.AssainisseurVitesse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════ TORTURE DES COMPOSANTS DE SECURITE SOCCER ═══════════
 * On martele les composants critiques avec des valeurs ABERRANTES (NaN, Infini,
 * hors plage, extremes) et on exige : jamais de commande non finie, jamais au-dela
 * des bornes, jamais de mouvement sans conditions. "Non defini = non sur."
 */
class SoccerTortureTest {

    private val rail = DroneRail(RailPoint(45.0, -73.0), RailPoint(45.0, -72.999))

    // ── RailMotionController : jamais de vitesse hors [-max, max], jamais NaN ──

    @Test fun motion_vitesse_toujours_dans_les_bornes() {
        val c = RailMotionController(maxSpeedMps = 2f, deadband = 0.03f, staleAfterMs = 500L,
            gainMps = 999f, maxDeltaVMps = 999f)
        val cibles = listOf(-100f, -1f, 0f, 0.5f, 1f, 100f, Float.MAX_VALUE)
        for (cur in cibles) for (tgt in cibles) {
            val v = c.compute(RailControlInput(cur, tgt, 0L, 0.9f)).requestedVelocityMps
            assertTrue("vitesse finie ($cur->$tgt): $v", v.isFinite())
            assertTrue("vitesse bornee ($cur->$tgt): $v", v in -2f..2f)
        }
    }

    @Test fun motion_perime_ou_confiance_faible_ne_bouge_jamais() {
        val c = RailMotionController(maxSpeedMps = 2f, deadband = 0.03f, staleAfterMs = 500L,
            maxDeltaVMps = 999f)
        // perime
        assertEquals(0f, c.compute(RailControlInput(0.1f, 0.9f, 9999L, 0.9f)).requestedVelocityMps, 1e-4f)
        c.reset()
        // confiance faible
        assertEquals(0f, c.compute(RailControlInput(0.1f, 0.9f, 0L, 0.0f)).requestedVelocityMps, 1e-4f)
    }

    // ── Soccer2DMotionController : vitesses 2D bornees, finies, arret conditionnel ──

    @Test fun motion2d_vitesses_bornees_et_finies() {
        val c = Soccer2DMotionController(maxSpeedMps = 2f, maxDeltaVMps = 999f, gainMps = 999f)
        val coords = listOf(-50f, 0f, 0.5f, 1f, 50f)
        for (ax in coords) for (ay in coords) {
            val o = c.compute(Point2D(0.5f, 0.5f), Point2D(ax, ay), 0L, 0.9f)
            assertTrue("vx fini/borne: ${o.vx}", o.vx.isFinite() && o.vx in -2f..2f)
            assertTrue("vy fini/borne: ${o.vy}", o.vy.isFinite() && o.vy in -2f..2f)
        }
    }

    @Test fun motion2d_nan_en_entree_donne_arret_fini() {
        val c = Soccer2DMotionController(maxSpeedMps = 2f)
        val o = c.compute(Point2D(Float.NaN, 0.5f), Point2D(0.8f, 0.8f), 0L, 0.9f)
        assertTrue(o.vx.isFinite() && o.vy.isFinite())
        assertEquals(0f, o.vx, 1e-4f); assertEquals(0f, o.vy, 1e-4f)
    }

    @Test fun motion_rail_nan_en_entree_donne_arret_fini() {
        val c = RailMotionController(maxSpeedMps = 2f, deadband = 0.03f, staleAfterMs = 500L)
        val v = c.compute(RailControlInput(Float.NaN, 0.9f, 0L, 0.9f)).requestedVelocityMps
        assertTrue(v.isFinite()); assertEquals(0f, v, 1e-4f)
    }

    @Test fun motion2d_maxspeed_zero_immobile() {
        // Double verrou : maxSpeed=0 -> aucune vitesse quelle que soit la cible.
        val c = Soccer2DMotionController(maxSpeedMps = 0f, maxDeltaVMps = 999f, gainMps = 999f)
        val o = c.compute(Point2D(0f, 0f), Point2D(1f, 1f), 0L, 0.9f)
        assertEquals(0f, o.vx, 1e-4f)
        assertEquals(0f, o.vy, 1e-4f)
    }

    // ── FlightCommandArbiter : une seule condition fausse -> neutre, jamais soccer ──

    private fun okSnap() = FlightCommandArbiter.SafetySnapshot(
        pilotOverride = false, emergencyStop = false, obstacleGateAllows = true,
        virtualStickAvailable = true, inFlightCompatible = true, railLoadedAndValid = true,
        dronePositionFresh = true, actionFreshAndConfident = true, batteryOk = true,
        corridorClear = true, operatorNearRail = true)

    private val soccerCmd = AssainisseurVitesse.Vitesses(1f, 1f, 1f, 1f)
    private val existante = AssainisseurVitesse.Vitesses(0.1f, 0.1f, 0.1f, 0.1f)

    @Test fun arbitre_chaque_condition_fausse_bloque_le_soccer() {
        // Pour CHAQUE condition mise a false, le soccer ne doit JAMAIS etre applique.
        val snaps = listOf<FlightCommandArbiter.SafetySnapshot>(
            okSnap().copy(virtualStickAvailable = false),
            okSnap().copy(inFlightCompatible = false),
            okSnap().copy(railLoadedAndValid = false),
            okSnap().copy(dronePositionFresh = false),
            okSnap().copy(actionFreshAndConfident = false),
            okSnap().copy(obstacleGateAllows = false),
            okSnap().copy(batteryOk = false),
            okSnap().copy(corridorClear = false),
            okSnap().copy(operatorNearRail = false),
        )
        for (s in snaps) {
            val d = FlightCommandArbiter.decide(existante, soccerCmd, existante, soccerModeArmed = true, safety = s)
            assertTrue("ne doit pas etre SoccerRail",
                d.source != FlightCommandArbiter.FlightCommandSource.SoccerRail)
            assertFalse("ne doit pas etre allowed en soccer", d.allowed && d.command == soccerCmd)
        }
    }

    @Test fun arbitre_pilote_prioritaire_sur_tout() {
        val d = FlightCommandArbiter.decide(existante, soccerCmd, existante,
            soccerModeArmed = true, safety = okSnap().copy(pilotOverride = true))
        assertEquals(FlightCommandArbiter.FlightCommandSource.Pilot, d.source)
    }

    @Test fun arbitre_urgence_donne_neutre() {
        val d = FlightCommandArbiter.decide(existante, soccerCmd, existante,
            soccerModeArmed = true, safety = okSnap().copy(emergencyStop = true))
        assertEquals(FlightCommandArbiter.NEUTRE, d.command)
        assertFalse(d.allowed)
    }

    // ── TerrainGeofence : robustesse polygones degeneres / coordonnees extremes ──

    @Test fun geofence_polygone_degenere_toujours_dehors() {
        assertFalse(TerrainGeofence.contient(RailPoint(45.0, -73.0), emptyList()))
        assertFalse(TerrainGeofence.contient(RailPoint(45.0, -73.0),
            listOf(RailPoint(45.0, -73.0), RailPoint(45.0, -73.0))))   // 2 points identiques
    }

    // ── Soccer2DPlanner : cible toujours finie et dans [0,1] ──

    @Test fun planner2d_cible_toujours_bornee() {
        val p = Soccer2DPlanner(retraitFractionDefaut = 999f, decalageFractionDefaut = 999f)
        val coords = listOf(-10f, 0f, 0.5f, 1f, 10f)
        for (cx in coords) for (cy in coords) {
            val c = p.calculer(Point2D(cx, cy), Point2D(1f, 0f), emptyList())
            assertTrue("x borne: ${c.x}", c.x.isFinite() && c.x in 0f..1f)
            assertTrue("y borne: ${c.y}", c.y.isFinite() && c.y in 0f..1f)
        }
    }

    // ── SoccerAltitudePlanner : toujours dans [min, max] ──

    @Test fun altitude_toujours_bornee_meme_entrees_aberrantes() {
        val a = SoccerAltitudePlanner(altMinM = 15.0, altMaxM = 40.0, lissage = 1f)
        val vals = listOf(-5f, 0f, 0.5f, 1f, 5f, Float.NaN)
        for (e in vals) for (v in vals) {
            val alt = a.altitudeCible(e, v)
            // Meme avec NaN en entree, l'altitude doit rester FINIE et bornee (assainie).
            assertFalse("alt jamais NaN (e=$e v=$v)", alt.isNaN())
            assertTrue("alt bornee: $alt", alt in 15.0..40.0)
        }
    }
}
