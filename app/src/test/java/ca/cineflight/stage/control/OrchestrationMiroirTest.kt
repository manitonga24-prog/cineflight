package ca.cineflight.stage.control

import ca.cineflight.stage.control.ObstacleSafetyGate.GateAction
import ca.cineflight.stage.control.ObstacleSafetyGate.HorizontalSnapshot
import ca.cineflight.stage.control.ObstacleSafetyGate.SafetyState
import ca.cineflight.stage.control.ObstacleSafetyGate.VerticalSnapshot
import ca.cineflight.stage.control.ObstacleSafetyGate.Vitesses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'orchestration miroir PURE (OrchestrationMiroir).
 *
 * OBJET DE CES TESTS : prouver que le gate est bien CALCULÉ (cmdGate) mais JAMAIS envoyé en
 * mode miroir — l'invariant central est `cmdAEnvoyer == cmdAssainie`, quelle que soit la
 * décision du gate. Aucune dépendance Android/SDK : les snapshots portent déjà leurs ages.
 */
class OrchestrationMiroirTest {

    private val EPS = 1e-6f
    private fun cmd(pitch: Float, roll: Float, throttle: Float) = Vitesses(pitch, roll, throttle, 0f)
    private fun snapV(up: Int?, upAge: Long = 10, down: Int? = null, downAge: Long = 10) =
        VerticalSnapshot(up, upAge, down, downAge)
    private fun snapH(l: List<Int>?, age: Long = 10) = HorizontalSnapshot(l, age)

    private fun decide(
        cmd: Vitesses,
        v: VerticalSnapshot? = null,
        h: HorizontalSnapshot? = null,
        origin: CommandOrigin = CommandOrigin.AUTOMATIC,
        state: SafetyState = SafetyState()
    ) = OrchestrationMiroir.deciderMiroir(cmd, v, h, origin, state)

    // ---- INVARIANT MIROIR : cmdAEnvoyer == cmdAssainie ----

    @Test fun invariant_cmd_a_envoyer_egale_cmd_assainie_meme_quand_gate_bloque() {
        // Montée critique (up=800 <= D_STOP_V=1000) → le gate VEUT bloquer (throttle=0).
        val entree = cmd(0f, 0f, 1.0f)
        val d = decide(entree, v = snapV(up = 800))
        // Le gate a bien calculé un blocage : cmdGate.throttle mis à 0.
        assertEquals(0f, d.cmdGate.throttle, EPS)
        assertTrue(d.actions.contains(GateAction.BLOCK_DIRECTION))
        // MAIS en miroir, la commande à envoyer reste la commande assainie, intacte.
        assertEquals(entree, d.cmdAEnvoyer)
        assertEquals(entree, d.cmdAssainie)
        assertEquals(1.0f, d.cmdAEnvoyer.throttle, EPS)
        // cmdGate et cmdAEnvoyer DIVERGENT : preuve que le gate est calculé mais non appliqué.
        assertTrue(
            "cmdGate (bloqué) doit différer de cmdAEnvoyer (assainie)",
            d.cmdGate.throttle != d.cmdAEnvoyer.throttle
        )
    }

    @Test fun invariant_tient_aussi_quand_gate_ralentit() {
        // Approche verticale (up=2000, milieu de [1000,3000]) → SLOW facteur 0.5.
        val entree = cmd(0f, 0f, 1.0f)
        val d = decide(entree, v = snapV(up = 2000))
        assertTrue(d.actions.contains(GateAction.SLOW))
        assertEquals(0.5f, d.cmdGate.throttle, EPS)      // gate calcule la modulation
        assertEquals(1.0f, d.cmdAEnvoyer.throttle, EPS)  // mais on envoie l'assainie intacte
    }

    @Test fun invariant_tient_quand_horizontal_indisponible() {
        // Translation avec liste horizontale toute-sentinelle → horizontal INDISPONIBLE
        // (le gate voudrait stopper les translations), le vertical PASS (throttle 0).
        val entree = cmd(1.0f, 0.5f, 0f)
        val d = decide(entree, h = snapH(listOf(60000, 60000)))
        assertTrue(d.actions.contains(GateAction.PERCEPTION_STALE))
        assertEquals(0f, d.cmdGate.pitch, EPS)           // gate bloque la translation
        assertEquals(0f, d.cmdGate.roll, EPS)
        assertEquals(entree, d.cmdAEnvoyer)              // envoi = assainie, translations intactes
    }

    @Test fun gate_pass_cmd_gate_egale_cmd_assainie() {
        // Loin de tout (up=5000) et pas de translation → PASS : cmdGate == cmdAssainie.
        val entree = cmd(0f, 0f, 1.0f)
        val d = decide(entree, v = snapV(up = 5000))
        assertEquals(d.cmdAssainie, d.cmdGate)
        assertEquals(d.cmdAssainie, d.cmdAEnvoyer)
    }

    // ---- L'état miroir est propagé (pour le prochain tick) sans être appliqué ----

    @Test fun etat_miroir_verrouille_apres_blocage_critique() {
        val d = decide(cmd(0f, 0f, 1.0f), v = snapV(up = 800))
        assertTrue("le canal up doit être verrouillé dans l'état miroir", d.newMirrorState.upSuspended)
        // Rejouer avec l'état verrouillé : SUSPENDED, et cmdAEnvoyer reste l'assainie.
        val entree2 = cmd(0f, 0f, 1.0f)
        val d2 = decide(entree2, v = snapV(up = 5000), state = d.newMirrorState)
        assertTrue(d2.actions.contains(GateAction.SUSPENDED))
        assertEquals(entree2, d2.cmdAEnvoyer)
    }

    // ---- Origines non automatiques : le gate est DISABLED, l'envoi reste l'assainie ----

    @Test fun origine_manuelle_disabled_mais_invariant_tient() {
        val entree = cmd(0f, 0f, 1.0f)
        val d = decide(entree, v = snapV(up = 800), origin = CommandOrigin.MANUAL)
        assertTrue(d.actions.all { it == GateAction.DISABLED })
        assertEquals(entree, d.cmdAEnvoyer)
        assertEquals(entree, d.cmdGate)   // DISABLED => gate ne touche rien
    }

    @Test fun actions_dans_l_ordre_vertical_puis_horizontal() {
        val d = decide(cmd(0f, 0f, 1.0f), v = snapV(up = 5000))
        assertEquals(2, d.actions.size)   // [vertical, horizontal]
    }
}
