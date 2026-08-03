package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SafetyLimitsTest — vérifie que la source unique de vérité des seuils de sécurité
 * porte les valeurs attendues (celles en vigueur dans le code et documentées au dossier).
 *
 * Ces tests figent les valeurs : toute modification d'un seuil de sécurité casse un test,
 * forçant une revue explicite (et la mise à jour du dossier + re-test E-01..E-12).
 */
class SafetyLimitsTest {

    @Test fun fraicheur_yolo_600ms() {
        assertEquals(600L, SafetyLimits.YOLO_FRAIS_MS)
    }

    @Test fun age_rtk_2s() {
        assertEquals(2.0, SafetyLimits.RTK_AGE_MAX_S, 0.0)
    }

    /**
     * Budget de détection ramené de 500 à 350 ms le 2026-07-22, sur preuve de l'essai E-03 :
     * avec 500 ms de timeout et 100 ms de période, la détection tombait entre 500 et 600 ms,
     * donc AU-DELÀ du budget de persistance de 500 ms du §378 — critère inatteignable.
     */
    @Test fun watchdog_350ms() {
        assertEquals(350L, SafetyLimits.WATCHDOG_TIMEOUT_MS)
    }

    @Test fun periode_watchdog_independant_50ms() {
        assertEquals(50L, SafetyLimits.WATCHDOG_INDEP_PERIODE_MS)
    }

    /**
     * INVARIANT DE DIMENSIONNEMENT — le cœur de la correction E03-03.
     *
     * La détection au pire vaut `timeout + periode` : le thread B ne peut constater le
     * dépassement qu'à sa vérification suivante. Ce total doit laisser de la place à la
     * RÉACTION (désarmement, commande neutre, sortie Virtual Stick — mesurée 10..40 ms)
     * à l'intérieur du budget de persistance de 500 ms du §378.
     *
     * Ce test échouera si quelqu'un remonte le timeout sans revoir le budget : c'est
     * exactement l'erreur que l'essai a révélée, et elle ne doit pas pouvoir revenir
     * silencieusement.
     */
    @Test fun detection_au_pire_laisse_une_marge_de_reaction_sous_le_seuil() {
        val detectionPireCas =
            SafetyLimits.WATCHDOG_TIMEOUT_MS + SafetyLimits.WATCHDOG_INDEP_PERIODE_MS
        val total = detectionPireCas + SafetyLimits.REACTION_BUDGET_MAX_MS
        assertTrue(
            "timeout(${SafetyLimits.WATCHDOG_TIMEOUT_MS}) + periode(${SafetyLimits.WATCHDOG_INDEP_PERIODE_MS}) " +
                "+ reaction(${SafetyLimits.REACTION_BUDGET_MAX_MS}) = $total ms " +
                "doit rester sous le budget de ${SafetyLimits.PERSISTANCE_MAX_MS} ms",
            total < SafetyLimits.PERSISTANCE_MAX_MS
        )
        assertTrue(
            "la periode doit rester strictement inferieure au timeout",
            SafetyLimits.WATCHDOG_INDEP_PERIODE_MS < SafetyLimits.WATCHDOG_TIMEOUT_MS
        )
    }

    /**
     * Le budget de réaction doit rester un CONTRAT tenable, pas un relevé du jour :
     * confortablement au-dessus de la pire réaction mesurée (40 ms au 2026-07-22), pour
     * qu'un téléphone plus lent ne fasse pas basculer l'argument de sécurité.
     */
    @Test fun le_budget_de_reaction_double_au_moins_la_pire_reaction_mesuree() {
        val pireReactionMesureeMs = 40L
        assertTrue(
            "budget ${SafetyLimits.REACTION_BUDGET_MAX_MS} ms trop proche du releve $pireReactionMesureeMs ms",
            SafetyLimits.REACTION_BUDGET_MAX_MS >= 2 * pireReactionMesureeMs
        )
    }

    /**
     * Le timeout doit tolérer plusieurs cycles manqués de la boucle pilote, sinon une
     * simple gigue d'ordonnancement Android déclencherait une mise en sécurité sans cause.
     * La relation est CALCULÉE depuis la fréquence nominale de la boucle : si celle-ci
     * change, l'exigence suit d'elle-même au lieu de rester figée sur une durée écrite en dur.
     */
    @Test fun le_timeout_tolere_le_nombre_minimal_de_cycles_de_boucle_pilote() {
        val periodeBoucleMs = 1000L / SafetyLimits.BOUCLE_PILOTE_HZ
        val minimumMs = SafetyLimits.WATCHDOG_CYCLES_BOUCLE_MIN * periodeBoucleMs
        assertTrue(
            "timeout ${SafetyLimits.WATCHDOG_TIMEOUT_MS} ms doit couvrir >= " +
                "${SafetyLimits.WATCHDOG_CYCLES_BOUCLE_MIN} cycles de $periodeBoucleMs ms ($minimumMs ms)",
            SafetyLimits.WATCHDOG_TIMEOUT_MS >= minimumMs
        )
    }

    @Test fun confiance_yolo_min_035() {
        assertEquals(0.35f, SafetyLimits.YOLO_CONF_MIN, 0f)
    }

    @Test fun batterie_min_40pct() {
        assertEquals(40, SafetyLimits.BATTERIE_MIN_PCT)
    }

    @Test fun plafond_operationnel_sous_reglementaire() {
        // Le plafond opérationnel (35 m) doit rester bien sous la limite réglementaire (122 m).
        assertTrue(SafetyLimits.ALT_OP_MAX_M < 122.0)
        assertTrue(SafetyLimits.ALT_OP_MAX_M <= SafetyLimits.ALT_MAX_M)
    }

    @Test fun vitesse_soccer_sous_plafond_general() {
        // La vitesse soccer 2D ne doit jamais dépasser le plafond horizontal général.
        assertTrue(SafetyLimits.SOCCER_2D_VMAX_MPS <= SafetyLimits.V_MAX_HORIZ_MPS)
    }

    @Test fun buffer_confinement_positif_et_coherent() {
        // Le buffer doit être strictement positif et refléter la valeur du §4.2quater.
        assertTrue(SafetyLimits.BUFFER_CONFINEMENT_M > 0.0)
        assertEquals(16.2, SafetyLimits.BUFFER_CONFINEMENT_M, 0.01)
    }

    @Test fun frequences_boucles_positives() {
        assertTrue(SafetyLimits.BOUCLE_PILOTE_HZ > 0)
        assertTrue(SafetyLimits.BOUCLE_TEST_AXES_HZ >= SafetyLimits.BOUCLE_PILOTE_HZ)
        assertTrue(SafetyLimits.POLLER_RTK_HZ > 0)
    }

    @Test fun coherence_ages_watchdog_vs_yolo() {
        // Le watchdog (350 ms) et la fraîcheur YOLO (600 ms) restent du même ordre de
        // grandeur, cohérents avec une boucle ~10 Hz.
        assertTrue(SafetyLimits.WATCHDOG_TIMEOUT_MS in 100L..1000L)
        assertTrue(SafetyLimits.YOLO_FRAIS_MS in 100L..1000L)
    }
}
