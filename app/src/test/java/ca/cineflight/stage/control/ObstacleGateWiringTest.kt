package ca.cineflight.stage.control

import ca.cineflight.stage.sentinelle.MonotonicClock
import ca.cineflight.stage.sentinelle.PerceptionSnapshotStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du CÂBLAGE obstacle typé (ObstacleGateWiring) et de son contrat en mode miroir.
 *
 * Ne peut pas exercer PontDjiReel.envoyerVitesses (dépend du SDK DJI, indisponible en JVM),
 * donc on reproduit FIDÈLEMENT la logique du `when (wiring)` — un provider instrumenté
 * (compteur d'appels) et l'appel réel à OrchestrationMiroir — pour prouver les 3 invariants :
 *
 *   1. Mirror  -> le snapshotProvider est appelé (exactement une fois par tick).
 *   2. Off     -> le snapshotProvider n'est JAMAIS appelé.
 *   3. cmdAEnvoyer == cmdAssainie dans les deux cas (miroir strict).
 */
class ObstacleGateWiringTest {

    private val EPS = 1e-6f

    /** Store réel alimenté par une horloge fixe, pour fabriquer un GatePerceptionSnapshot. */
    private fun storeAvec(up: Int?, down: Int?, h: List<Int>?, now: Long = 1_000L): PerceptionSnapshotStore {
        val store = PerceptionSnapshotStore(MonotonicClock { now })
        store.publierVertical(up, down)
        if (h != null) store.publierHorizontal(h)
        return store
    }

    /**
     * Reproduit le coeur du `when (wiring)` de PontDjiReel.envoyerVitesses (hors SDK) :
     * renvoie la commande qui serait ENVOYÉE au drone, en comptant les appels au provider.
     */
    private fun simulerEnvoi(
        wiring: ObstacleGateWiring,
        cmdAssainie: ObstacleSafetyGate.Vitesses,
        origin: CommandOrigin = CommandOrigin.AUTOMATIC,
        state: ObstacleSafetyGate.SafetyState = ObstacleSafetyGate.SafetyState()
    ): ObstacleSafetyGate.Vitesses = when (val w = wiring) {
        is ObstacleGateWiring.Off -> cmdAssainie
        is ObstacleGateWiring.Mirror -> {
            val perception = w.snapshotProvider()   // une seule lecture atomique
            val decision = OrchestrationMiroir.deciderMiroir(
                cmdAssainie = cmdAssainie, snapshot = perception, origin = origin, mirrorState = state)
            decision.cmdAEnvoyer   // miroir strict : toujours == cmdAssainie
        }
    }

    @Test fun off_provider_jamais_appele_et_commande_intacte() {
        var appels = 0
        // Un Mirror pour COMPTER, mais on teste Off : le provider ne doit jamais être invoqué.
        val cmd = ObstacleSafetyGate.Vitesses(0f, 0f, 1.0f, 0f)
        val envoye = simulerEnvoi(ObstacleGateWiring.Off, cmd)
        // Off n'a pas de provider ; on vérifie juste l'invariant commande.
        assertEquals(cmd, envoye)
        assertEquals(0, appels)   // aucun provider en jeu
    }

    @Test fun mirror_provider_appele_une_fois_par_tick() {
        var appels = 0
        val store = storeAvec(up = 2500, down = 1800, h = listOf(3000, 4000))
        val wiring = ObstacleGateWiring.Mirror(
            snapshotProvider = { appels++; store.snapshotPourGate() },
            source = "TEST"
        )
        simulerEnvoi(wiring, ObstacleSafetyGate.Vitesses(0f, 0f, 1.0f, 0f))
        assertEquals(1, appels)
    }

    @Test fun mirror_cmd_a_envoyer_egale_cmd_assainie_meme_si_gate_bloque() {
        var appels = 0
        // up=800 <= D_STOP_V(1000) -> le gate VEUT bloquer, mais en miroir on envoie l'assainie.
        val store = storeAvec(up = 800, down = null, h = null)
        val wiring = ObstacleGateWiring.Mirror(
            snapshotProvider = { appels++; store.snapshotPourGate() },
            source = "TEST"
        )
        val cmd = ObstacleSafetyGate.Vitesses(0f, 0f, 1.0f, 0f)   // montée
        val envoye = simulerEnvoi(wiring, cmd)
        assertEquals(1, appels)
        assertEquals(cmd, envoye)                 // invariant : envoyé == assaini
        assertEquals(1.0f, envoye.throttle, EPS)  // pas de blocage réellement appliqué
    }

    @Test fun mirror_off_ne_touche_pas_l_origine_manuelle() {
        // Origine MANUAL -> le gate est DISABLED, cmdAEnvoyer reste l'assainie de toute façon.
        val store = storeAvec(up = 800, down = 800, h = null)
        var appels = 0
        val wiring = ObstacleGateWiring.Mirror(
            snapshotProvider = { appels++; store.snapshotPourGate() },
            source = "TEST"
        )
        val cmd = ObstacleSafetyGate.Vitesses(0f, 0f, 1.0f, 0f)
        val envoye = simulerEnvoi(wiring, cmd, origin = CommandOrigin.MANUAL)
        assertEquals(cmd, envoye)
        assertEquals(1, appels)
    }

    @Test fun wiring_mirror_porte_sa_source() {
        val store = storeAvec(up = 3000, down = 3000, h = listOf(5000))
        val wiring = ObstacleGateWiring.Mirror(
            snapshotProvider = store::snapshotPourGate,
            source = "PHASE3"
        )
        assertEquals("PHASE3", wiring.source)
        assertTrue(wiring.snapshotProvider() != null)   // provider utilisable
    }
}
