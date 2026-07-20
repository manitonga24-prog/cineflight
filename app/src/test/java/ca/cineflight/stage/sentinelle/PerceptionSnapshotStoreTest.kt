package ca.cineflight.stage.sentinelle

import ca.cineflight.stage.control.ObstacleSafetyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM RÉELS du chemin de perception : publication → timestamp MONOTONE → snapshot → âge.
 *
 * Contrairement à un simple test de contrat d'horloge, ceux-ci exercent le CODE DE PRODUCTION
 * utilisé par LecteurPerception : PerceptionSnapshotStore fait EXACTEMENT la même publication
 * (à partir de valeurs brutes) et le même calcul d'âge que le callback DJI, mais sans aucun
 * type DJI/Android — donc entièrement testable en JVM.
 *
 * Horloge pilotée par une variable capturée : on publie à un instant, on AVANCE le temps, puis
 * on lit et on vérifie que ageMs est déterministe. C'est la preuve que le store lit bien
 * l'horloge injectée au moment de la LECTURE (et non de la publication).
 *
 * TIMEOUT de référence du gate : ObstacleSafetyGate.TIMEOUT_PERCEPTION_MS = 500 ms.
 */
class PerceptionSnapshotStoreTest {

    /** Horloge de test pilotable : nowMs() relit la variable [t] à chaque appel. */
    private class HorlogeTest(var t: Long = 0L) : MonotonicClock {
        override fun nowMs(): Long = t
    }

    // ---------- VERTICAL ----------

    @Test fun vertical_age_frais_apres_avancee_horloge() {
        val h = HorlogeTest(1_000L)
        val store = PerceptionSnapshotStore(h)
        store.publierVertical(upwardMm = 2500, downwardMm = 1800)  // publié à t=1000
        h.t = 1_050L                                               // 50 ms plus tard
        val snap = store.snapshotVertical()!!
        assertEquals(2500, snap.upwardMm)
        assertEquals(1800, snap.downwardMm)
        assertEquals(50L, snap.upwardAgeMs)
        assertEquals(50L, snap.downwardAgeMs)
    }

    @Test fun vertical_age_perime_au_dela_du_timeout() {
        val h = HorlogeTest(0L)
        val store = PerceptionSnapshotStore(h)
        store.publierVertical(upwardMm = 2500, downwardMm = 2500)  // t=0
        h.t = 800L                                                 // 800 ms > 500
        val snap = store.snapshotVertical()!!
        assertEquals(800L, snap.upwardAgeMs)
        assertTrue(
            "à 800ms le canal doit dépasser le timeout du gate",
            snap.upwardAgeMs > ObstacleSafetyGate.TIMEOUT_PERCEPTION_MS
        )
    }

    @Test fun vertical_canal_jamais_recu_age_max() {
        // upward présent, downward null (jamais reçu) → downwardAgeMs = Long.MAX_VALUE.
        val h = HorlogeTest(500L)
        val store = PerceptionSnapshotStore(h)
        store.publierVertical(upwardMm = 3000, downwardMm = null)
        h.t = 600L
        val snap = store.snapshotVertical()!!
        assertEquals(100L, snap.upwardAgeMs)               // 600 - 500
        assertEquals(Long.MAX_VALUE, snap.downwardAgeMs)   // jamais reçu -> INDISPONIBLE côté gate
    }

    @Test fun vertical_null_avant_toute_publication() {
        val store = PerceptionSnapshotStore(HorlogeTest(0L))
        assertNull(store.snapshotVertical())
    }

    @Test fun vertical_republication_reinitialise_le_timestamp() {
        val h = HorlogeTest(1_000L)
        val store = PerceptionSnapshotStore(h)
        store.publierVertical(upwardMm = 2000, downwardMm = 2000)  // t=1000
        h.t = 1_400L
        store.publierVertical(upwardMm = 2200, downwardMm = 2200)  // republié à t=1400
        h.t = 1_450L
        val snap = store.snapshotVertical()!!
        assertEquals(2200, snap.upwardMm)
        assertEquals(50L, snap.upwardAgeMs)   // 1450 - 1400, pas 1450 - 1000
    }

    // ---------- HORIZONTAL ----------

    @Test fun horizontal_age_frais_apres_avancee_horloge() {
        val h = HorlogeTest(2_000L)
        val store = PerceptionSnapshotStore(h)
        store.publierHorizontal(listOf(3000, 4000, 60000))   // t=2000
        h.t = 2_120L                                         // 120 ms plus tard
        val snap = store.snapshotHorizontal()!!
        assertEquals(listOf(3000, 4000, 60000), snap.distancesMm)
        assertEquals(120L, snap.ageMs)
    }

    @Test fun horizontal_null_avant_toute_publication() {
        val store = PerceptionSnapshotStore(HorlogeTest(0L))
        assertNull(store.snapshotHorizontal())
    }

    // ---------- EFFACEMENT ----------

    @Test fun effacer_remet_les_deux_canaux_a_null() {
        val store = PerceptionSnapshotStore(HorlogeTest(0L))
        store.publierVertical(1000, 1000)
        store.publierHorizontal(listOf(2000))
        store.effacer()
        assertNull(store.snapshotVertical())
        assertNull(store.snapshotHorizontal())
    }

    // ---------- INTÉGRATION store -> gate (âge déterministe consommé par le gate pur) ----------

    @Test fun age_deterministe_rend_le_gate_stale_reproductible() {
        // Publie une distance parfaitement exploitable, mais laisse vieillir au-delà du timeout :
        // le gate doit alors déclarer PERCEPTION_STALE de façon 100% déterministe.
        val h = HorlogeTest(0L)
        val store = PerceptionSnapshotStore(h)
        store.publierVertical(upwardMm = 2500, downwardMm = null)  // distance saine
        h.t = 600L                                                 // périmé (> 500)
        val snap = store.snapshotVertical()!!
        val res = ObstacleSafetyGate.evaluate(
            cmd = ObstacleSafetyGate.Vitesses(0f, 0f, 1.0f, 0f),   // montée
            perception = snap,
            origin = ca.cineflight.stage.control.CommandOrigin.AUTOMATIC,
            state = ObstacleSafetyGate.SafetyState()
        )
        assertEquals(ObstacleSafetyGate.GateAction.PERCEPTION_STALE, res.action)
    }

    // ---------- snapshotPourGate() : lecture combinée, un seul now ----------

    @Test fun pour_gate_un_seul_now_ages_coherents() {
        // Vertical publié à t=1000, horizontal à t=1200. Lecture combinée à t=1500 :
        // les DEUX âges doivent être calculés avec le MÊME now (1500), pas deux appels distincts.
        val h = HorlogeTest(1_000L)
        val store = PerceptionSnapshotStore(h)
        store.publierVertical(upwardMm = 2500, downwardMm = 1800)  // ts vertical = 1000
        h.t = 1_200L
        store.publierHorizontal(listOf(3000, 4000))               // ts horizontal = 1200
        h.t = 1_500L
        val combo = store.snapshotPourGate()
        assertEquals(1_500L, combo.nowMs)
        // vertical : 1500 - 1000 = 500
        assertEquals(500L, combo.vertical!!.upwardAgeMs)
        assertEquals(500L, combo.vertical!!.downwardAgeMs)
        // horizontal : 1500 - 1200 = 300 — calculé avec le MÊME now que le vertical
        assertEquals(300L, combo.horizontal!!.ageMs)
        // cohérence : l'écart des âges (500-300=200) = l'écart des ts de publication (1200-1000).
        assertEquals(200L, combo.vertical!!.upwardAgeMs - combo.horizontal!!.ageMs)
    }

    @Test fun pour_gate_volets_null_si_jamais_publies() {
        val store = PerceptionSnapshotStore(HorlogeTest(3_000L))
        val combo = store.snapshotPourGate()
        assertEquals(3_000L, combo.nowMs)   // now lu même sans données
        assertNull(combo.vertical)
        assertNull(combo.horizontal)
    }

    @Test fun pour_gate_vertical_seul_horizontal_absent() {
        val h = HorlogeTest(0L)
        val store = PerceptionSnapshotStore(h)
        store.publierVertical(upwardMm = 2000, downwardMm = 2000)  // t=0
        h.t = 120L
        val combo = store.snapshotPourGate()
        assertEquals(120L, combo.vertical!!.upwardAgeMs)
        assertNull(combo.horizontal)   // jamais publié -> null, pas d'âge fabriqué
    }

    @Test fun pour_gate_canal_jamais_recu_age_max() {
        val h = HorlogeTest(500L)
        val store = PerceptionSnapshotStore(h)
        store.publierVertical(upwardMm = 3000, downwardMm = null)  // downward jamais reçu
        h.t = 700L
        val combo = store.snapshotPourGate()
        assertEquals(200L, combo.vertical!!.upwardAgeMs)              // 700 - 500
        assertEquals(Long.MAX_VALUE, combo.vertical!!.downwardAgeMs)  // INDISPONIBLE côté gate
    }
}
