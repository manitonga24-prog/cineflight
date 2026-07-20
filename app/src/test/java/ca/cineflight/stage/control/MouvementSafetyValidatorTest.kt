package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de MouvementSafetyValidator.validerDemande : le portier "dry-run" qui
 * decide si une demande de mouvement serveur serait autorisee.
 *
 * Doctrine : refus par defaut. Chaque condition manquante ou douteuse (mouvement
 * inconnu, timestamp absent, demande perimee, position sujet absente / perdue /
 * trop vieille / non fixee) doit BLOQUER. Un seul chemin autorise, et seulement
 * quand tout est frais et sur.
 *
 * On teste une branche de refus par cas + le chemin autorise + l'ordre de priorite.
 * (Les codes de statut contenant "RTK"/"FIX" sont des identifiants internes de
 * code, jamais du texte affiche a l'utilisateur.)
 */
class MouvementSafetyValidatorTest {

    private fun maintenantS() = System.currentTimeMillis() / 1000.0

    private fun demande(movement: String?, serverRxTs: Double?) = DemandeMouvement(
        ok = true, deviceId = "dev", kind = "movement", movement = movement,
        source = "test", safetyNote = null, serverRxTs = serverRxTs, rawJson = "{}"
    )

    private fun rtk(
        present: Boolean = true, rtk: String? = "FIX", ageS: Double? = 1.0
    ) = RtkSujet(
        present = present, lat = 45.0, lon = -73.0, altM = 100.0,
        rtk = rtk, ageS = ageS, hz = 10.0, rawJson = "{}"
    )

    // ----------------------------------------------------- refus, un par branche
    @Test fun refus_sans_mouvement() {
        val v = MouvementSafetyValidator.validerDemande(demande(null, maintenantS()), rtk())
        assertFalse(v.autorise); assertEquals("BLOCKED_NO_MOVEMENT", v.status)
    }

    @Test fun refus_mouvement_inconnu() {
        val v = MouvementSafetyValidator.validerDemande(demande("LOOPING_INFINI", maintenantS()), rtk())
        assertFalse(v.autorise); assertEquals("BLOCKED_UNKNOWN_MOVEMENT", v.status)
    }

    @Test fun refus_sans_timestamp() {
        val v = MouvementSafetyValidator.validerDemande(demande("PAUSE_HOVER", null), rtk())
        assertFalse(v.autorise); assertEquals("BLOCKED_NO_TIMESTAMP", v.status)
    }

    @Test fun refus_demande_perimee() {
        val v = MouvementSafetyValidator.validerDemande(
            demande("PAUSE_HOVER", maintenantS() - 40.0), rtk())
        assertFalse(v.autorise); assertEquals("BLOCKED_STALE_REQUEST", v.status)
    }

    // NOTE : ces tests visent les BRANCHES RTK. Ils utilisent donc un mouvement REEL
    // (ORBITE_LARGE), car PAUSE_HOVER/STOP_HOVER sont court-circuites AVANT les controles
    // RTK (intention de securite acquittee sans RTK, cf. validateur). Utiliser PAUSE ici
    // ne testerait PAS la branche RTK visee.
    @Test fun refus_rtk_illisible() {
        val v = MouvementSafetyValidator.validerDemande(demande("ORBITE_LARGE", maintenantS()), null)
        assertFalse(v.autorise); assertEquals("BLOCKED_RTK_READ_ERROR", v.status)
    }

    @Test fun refus_aucun_sujet() {
        val v = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS()), rtk(present = false))
        assertFalse(v.autorise); assertEquals("BLOCKED_NO_RTK_SUBJECT", v.status)
    }

    @Test fun refus_sujet_perdu_null_ou_lost() {
        val vNull = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS()), rtk(rtk = null))
        assertEquals("BLOCKED_RTK_LOST", vNull.status)
        val vLost = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS()), rtk(rtk = "LOST"))
        assertEquals("BLOCKED_RTK_LOST", vLost.status)
    }

    @Test fun refus_age_sujet_absent() {
        val v = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS()), rtk(ageS = null))
        assertFalse(v.autorise); assertEquals("BLOCKED_RTK_NO_AGE", v.status)
    }

    @Test fun refus_position_trop_vieille() {
        val v = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS()), rtk(ageS = 5.0))   // > 2.5 s
        assertFalse(v.autorise); assertEquals("BLOCKED_RTK_TOO_OLD", v.status)
    }

    @Test fun refus_position_non_fixee() {
        val v = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS()), rtk(rtk = "FLOAT"))
        assertFalse(v.autorise); assertEquals("BLOCKED_RTK_NOT_FIX", v.status)
    }

    // ----------------------------------------------------- le SEUL chemin autorise (RTK)
    @Test fun autorise_quand_tout_est_frais_et_sur() {
        val v = MouvementSafetyValidator.validerDemande(
            demande("ORBITE_LARGE", maintenantS()), rtk(present = true, rtk = "FIX", ageS = 1.0))
        assertTrue(v.autorise); assertEquals("AUTO_VALIDATION_OK_RTK_ONLY", v.status)
    }

    @Test fun les_quatre_mouvements_permis_passent() {
        for (m in listOf("TRAVELLING_ARRIERE", "ORBITE_LARGE", "PAUSE_HOVER", "STOP_HOVER")) {
            val v = MouvementSafetyValidator.validerDemande(demande(m, maintenantS()), rtk())
            assertTrue("$m devrait etre autorise", v.autorise)
        }
    }

    // ----------------------------------------------------- ordre de priorite
    @Test fun perimee_l_emporte_sur_un_rtk_aussi_mauvais() {
        // demande perimee ET rtk absent : le controle de fraicheur passe en premier
        val v = MouvementSafetyValidator.validerDemande(
            demande("PAUSE_HOVER", maintenantS() - 60.0), rtk(present = false, rtk = "LOST"))
        assertEquals("BLOCKED_STALE_REQUEST", v.status)
    }
}
