package ca.cineflight.stage.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SafetyLimitsSourceGuardTest — GARDE ANTI-RÉINTRODUCTION de seuils dupliqués (v54).
 *
 * La migration v54 a branché Phase3Activity sur SafetyLimits (source unique des seuils
 * de sécurité). Ce test lit le CODE SOURCE et échoue si quelqu'un ré-introduit un
 * littéral local pour un seuil migré — la duplication redeviendrait alors possible
 * entre la porte pré-vol, l'arbitre, le watchdog et l'affichage.
 *
 * Vérifie aussi la présence et le format de l'empreinte de configuration (CONFIG_ID),
 * journalisée à l'armement pour la traçabilité du dossier de sécurité.
 */
class SafetyLimitsSourceGuardTest {

    private fun source(chemin: String): String {
        // Le repertoire de travail des tests unitaires est le module app/ (ou la racine).
        val candidats = listOf(File(chemin), File("app/$chemin"))
        val f = candidats.firstOrNull { it.exists() }
            ?: throw AssertionError("source introuvable : $chemin (cwd=${File(".").absolutePath})")
        return f.readText(Charsets.UTF_8)
    }

    private val phase3 by lazy { source("src/main/java/ca/cineflight/stage/Phase3Activity.kt") }

    // ── 1. Les seuils migrés CONSOMMENT SafetyLimits ─────────────────────────

    @Test fun phase3_consomme_les_seuils_depuis_SafetyLimits() {
        for (ref in listOf(
            "SafetyLimits.YOLO_CONF_MIN",
            "SafetyLimits.YOLO_FRAIS_MS",
            "SafetyLimits.BATTERIE_MIN_PCT",
            "SafetyLimits.RTK_AGE_MAX_S",
            "SafetyLimits.DIST_DECROCHAGE_M",
            "SafetyLimits.DIST_OPERATEUR_MAX_M",
            "SafetyLimits.ALT_MAX_M",
        )) {
            assertTrue("Phase3Activity doit consommer $ref", phase3.contains(ref))
        }
    }

    // ── 2. AUCUN littéral local ré-introduit pour un seuil migré ─────────────

    @Test fun aucun_litteral_local_reintroduit_dans_Phase3() {
        val interdits = listOf(
            Regex("""YOLO_CONF_MIN\s*=\s*0[.,]\d"""),        // ex. = 0.35f
            Regex("""YOLO_FRAIS_MS\s*=\s*\d"""),             // ex. = 600L
            Regex("""SOCCER_BATT_MIN_PCT\s*=\s*\d"""),       // ex. = 40
            Regex("""RTK_AGE_MAX_S\s*=\s*\d"""),             // ex. = 2.0
            Regex("""DIST_MAX_M\s*=\s*\d"""),                // ex. = 120.0
            Regex("""ALT_MAX_M\s*=\s*\d"""),                 // ex. = 60.0
            Regex("""SOCCER_DIST_MAX_M\s*=\s*\d"""),         // ex. = 200.0
        )
        for (r in interdits) {
            assertFalse(
                "Littéral de seuil ré-introduit dans Phase3Activity (interdit — utiliser SafetyLimits) : ${r.pattern}",
                r.containsMatchIn(phase3)
            )
        }
    }

    // ── 3. Les valeurs de la source unique restent celles du dossier ─────────

    @Test fun valeurs_de_la_source_unique_inchangees() {
        // Doublon volontaire avec SafetyLimitsTest : ce test-ci accompagne la garde
        // source et casse si quelqu'un modifie un seuil sans revue (dossier + re-test).
        assertTrue(SafetyLimits.YOLO_CONF_MIN == 0.35f)
        assertTrue(SafetyLimits.YOLO_FRAIS_MS == 600L)
        assertTrue(SafetyLimits.BATTERIE_MIN_PCT == 40)
        assertTrue(SafetyLimits.RTK_AGE_MAX_S == 2.0)
        assertTrue(SafetyLimits.DIST_DECROCHAGE_M == 120.0)
        assertTrue(SafetyLimits.DIST_OPERATEUR_MAX_M == 200.0)
        assertTrue(SafetyLimits.ALT_MAX_M == 60.0)
    }

    // ── 4. Empreinte de configuration (traçabilité) ──────────────────────────

    @Test fun config_id_present_stable_et_hexadecimal() {
        val id1 = SafetyLimits.CONFIG_ID
        val id2 = SafetyLimits.CONFIG_ID
        assertTrue("CONFIG_ID vide", id1.isNotBlank())
        assertTrue("CONFIG_ID doit être hexadécimal (16 car.)", Regex("^[0-9a-f]{16}$").matches(id1))
        assertTrue("CONFIG_ID doit être stable", id1 == id2)
    }

    @Test fun config_id_journalise_a_l_armement() {
        // La ligne de journalisation doit exister au point d'armement (traçabilité vol).
        assertTrue("Phase3Activity doit journaliser SafetyLimits.CONFIG_ID à l'armement",
            phase3.contains("SafetyLimits.CONFIG_ID"))
    }
}
