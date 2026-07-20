package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du journal HAUTE FREQUENCE de l'emission 2D (Phase 1.4). Formatage pur et
 * deterministe : on verifie que chaque champ attendu est present, correctement formate,
 * et que l'INVARIANT du double verrou est visible dans le journal.
 */
class Soccer2DEmissionLogTest {

    private val L = Soccer2DEmissionLog

    private fun snap(
        po: Boolean = false, em: Boolean = false, og: Boolean = true, vs: Boolean = true,
        iflight: Boolean = true, rv: Boolean = true, pf: Boolean = true, ac: Boolean = true,
        bo: Boolean = true, cc: Boolean = true, on: Boolean = true,
    ) = FlightCommandArbiter.SafetySnapshot(
        pilotOverride = po, emergencyStop = em, obstacleGateAllows = og,
        virtualStickAvailable = vs, inFlightCompatible = iflight, railLoadedAndValid = rv,
        dronePositionFresh = pf, actionFreshAndConfident = ac, batteryOk = bo,
        corridorClear = cc, operatorNearRail = on,
    )

    private fun ligneActive() = L.ligne(
        tsMs = 1_700_000_000_000L, generation = 42L,
        throttleDemandeMps = 0.4f, throttleSurveilleMps = 0.4f, throttleEmisMps = 0.4f,
        watchdogFrais = true, watchdogAgeMs = 12L,
        etat = Emission2DGuard.Etat.ACTIVE, raison = "soccer autorise",
        snapshot = snap(), emis = true,
    )

    // --- CHAMPS PRESENTS ET FORMATES ---

    @Test fun tous_les_champs_cles_sont_presents() {
        val s = ligneActive()
        assertTrue(s.contains("ts_ms=1700000000000"))
        assertTrue(s.contains("gen=42"))
        assertTrue(s.contains("throttle_req=0.400"))
        assertTrue(s.contains("throttle_wd=0.400"))
        assertTrue(s.contains("throttle_emis=0.400"))
        assertTrue(s.contains("wd_frais=true"))
        assertTrue(s.contains("wd_age_ms=12"))
        assertTrue(s.contains("etat=ACTIVE"))
        assertTrue(s.contains("raison=\"soccer autorise\""))
        assertTrue(s.contains("emis=true"))
        assertTrue(s.contains("snapshot="))
    }

    @Test fun floats_formates_a_3_decimales_point_us() {
        val s = L.ligne(
            tsMs = 1L, generation = 1L,
            throttleDemandeMps = 0.123456f, throttleSurveilleMps = 0f, throttleEmisMps = 0f,
            watchdogFrais = false, watchdogAgeMs = 999L,
            etat = Emission2DGuard.Etat.BLOQUE_ARBITRE, raison = "x", snapshot = snap(), emis = false,
        )
        assertTrue(s.contains("throttle_req=0.123"))   // point, 3 decimales
        // Les throttles utilisent un POINT decimal (locale US), pas de virgule.
        // (Les seules virgules de la ligne sont dans le libelle du snapshot [PO,EM,...].)
        val avantSnapshot = s.substringBefore(" snapshot=")
        assertFalse("pas de virgule decimale hors libelle snapshot", avantSnapshot.contains(","))
    }

    @Test fun age_infini_est_note_inf() {
        val s = L.ligne(
            tsMs = 1L, generation = 1L,
            throttleDemandeMps = 0f, throttleSurveilleMps = 0f, throttleEmisMps = 0f,
            watchdogFrais = false, watchdogAgeMs = Long.MAX_VALUE,
            etat = Emission2DGuard.Etat.INERTE_FLAG_OFF, raison = "flag reel off",
            snapshot = snap(), emis = false,
        )
        assertTrue(s.contains("wd_age_ms=inf"))
    }

    // --- SNAPSHOT BITS ---

    @Test fun snapshot_bits_ordonnes_et_libelles() {
        // tout vrai sauf PO/EM (defauts) : 0 0 puis 1 x9.
        val bits = L.snapshotBits(snap())
        assertTrue(bits.startsWith("00111111111"))
        assertTrue(bits.endsWith("[PO,EM,OG,VS,IF,RV,PF,AC,BO,CC,ON]"))
    }

    @Test fun snapshot_bits_reflete_chaque_condition() {
        // batterie a false -> le 9e bit (BO) doit etre 0.
        val bits = L.snapshotBits(snap(bo = false)).substringBefore("[")
        assertEquals(11, bits.length)
        assertEquals('0', bits[8])   // index 8 = BO (PO=0,EM=1,OG=2,VS=3,IF=4,RV=5,PF=6,AC=7,BO=8)
        // urgence a true -> 2e bit (EM) = 1.
        val bits2 = L.snapshotBits(snap(em = true)).substringBefore("[")
        assertEquals('1', bits2[1])
    }

    // --- INVARIANT DOUBLE VERROU visible dans le journal ---

    @Test fun invariant_bloque_montre_throttle_emis_zero_et_etat_non_active() {
        val s = L.ligne(
            tsMs = 5L, generation = 7L,
            throttleDemandeMps = 0.4f, throttleSurveilleMps = 0.4f, throttleEmisMps = 0f,
            watchdogFrais = true, watchdogAgeMs = 3L,
            etat = Emission2DGuard.Etat.INERTE_VMAX_ZERO, raison = "vMax = 0",
            snapshot = snap(), emis = false,
        )
        assertTrue(s.contains("throttle_emis=0.000"))
        assertTrue(s.contains("etat=INERTE_VMAX_ZERO"))
        assertTrue(s.contains("emis=false"))
    }

    @Test fun watchdog_mort_montre_throttle_wd_zero() {
        // cycle mort : demande 0.4 mais surveille = 0 (watchdog a coupe), emis 0.
        val s = L.ligne(
            tsMs = 9L, generation = 8L,
            throttleDemandeMps = 0.4f, throttleSurveilleMps = 0f, throttleEmisMps = 0f,
            watchdogFrais = false, watchdogAgeMs = 1200L,
            etat = Emission2DGuard.Etat.BLOQUE_ARBITRE, raison = "soccer bloque : batterie_insuffisante",
            snapshot = snap(bo = false), emis = false,
        )
        assertTrue(s.contains("throttle_req=0.400"))
        assertTrue(s.contains("throttle_wd=0.000"))   // watchdog a coupe
        assertTrue(s.contains("wd_frais=false"))
        assertTrue(s.contains("wd_age_ms=1200"))
    }

    @Test fun tag_est_stable() {
        assertEquals("SOCCER_2D_EMISSION", Soccer2DEmissionLog.TAG)
    }
}
