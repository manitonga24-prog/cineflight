package ca.cineflight.stage.sentinelle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Tests du NOYAU SECURITE (couche 1)  #11 du plan d'audit.
 *
 * Verifie la doctrine gravee, INCHANGEE apres le reroutage (NoyauSecurite n'a pas
 * ete modifie ; seul l'adaptateur en aval a change) :
 *   - priorite STOP_PILOTE > INTRUSION > ERREUR_SYSTEME ;
 *   - verrou d'erreur jusqu'a acquittement (une erreur survenue reste active) ;
 *   - RTK sujet perdu / capteurs perimes -> blocage ;
 *   - le noyau est l'UNIQUE point de passage : danger -> (0,0,0,0), sinon transmis.
 */
class NoyauSecuriteTest {

    private class FauxPont : PontDrone {
        data class V(val p: Double, val r: Double, val t: Double, val y: Double)
        val sorties = ConcurrentLinkedQueue<V>()
        override fun envoyerVitesses(pitch: Double, roll: Double, throttle: Double, yaw: Double) {
            sorties += V(pitch, roll, throttle, yaw)
        }
        override fun orienterNacelle(pitchDeg: Double, yawDeg: Double, yawAbsolu: Boolean) {}
    }
    private val ZERO = FauxPont.V(0.0, 0.0, 0.0, 0.0)
    private fun sain() = EtatCapteurs()   // tout OK par defaut

    // ------------------------------------------------------------ priorite
    @Test fun stop_gagne_sur_intrusion_et_erreur() {
        val n = NoyauSecurite(FauxPont())
        // STOP + intrusion + video perdue simultanement -> STOP gagne
        val r = n.evaluer(EtatCapteurs(stopPilote = true, intrusionDetectee = true, videoOk = false))
        assertEquals(RaisonBlocage.STOP_PILOTE, r)
    }

    @Test fun intrusion_gagne_sur_erreur() {
        val n = NoyauSecurite(FauxPont())
        val r = n.evaluer(EtatCapteurs(intrusionDetectee = true, videoOk = false))
        assertEquals(RaisonBlocage.INTRUSION, r)
    }

    @Test fun erreur_systeme_si_capteur_manquant() {
        val n = NoyauSecurite(FauxPont())
        assertEquals(RaisonBlocage.ERREUR_SYSTEME, n.evaluer(EtatCapteurs(videoOk = false)))
        assertEquals(DetailErreur.VIDEO_PERDUE, n.detailErreur)
    }

    // ------------------------------------------------------------ point de passage unique
    @Test fun aucun_danger_transmet_la_commande() {
        val pont = FauxPont(); val n = NoyauSecurite(pont)
        val r = n.soumettreIntention(0.0, 0.0, 1.0, 0.0, sain())
        assertEquals(RaisonBlocage.AUCUNE, r)
        assertEquals(FauxPont.V(0.0, 0.0, 1.0, 0.0), pont.sorties.last())
    }

    @Test fun danger_force_vitesses_nulles() {
        val pont = FauxPont(); val n = NoyauSecurite(pont)
        val r = n.soumettreIntention(0.0, 0.0, 1.0, 0.0, EtatCapteurs(stopPilote = true))
        assertEquals(RaisonBlocage.STOP_PILOTE, r)
        assertEquals("danger -> (0,0,0,0)", ZERO, pont.sorties.last())
    }

    // ------------------------------------------------------------ verrou d'erreur
    @Test fun erreur_verrouillee_jusqu_a_acquittement() {
        val n = NoyauSecurite(FauxPont())
        assertEquals(RaisonBlocage.ERREUR_SYSTEME, n.evaluer(EtatCapteurs(videoOk = false)))
        // le capteur redevient OK -> reste ERREUR_SYSTEME (verrouille)
        assertEquals(RaisonBlocage.ERREUR_SYSTEME, n.evaluer(sain()))
        n.acquitterErreur()
        assertEquals(RaisonBlocage.AUCUNE, n.evaluer(sain()))
    }

    @Test fun erreur_pendant_intrusion_reste_verrouillee_ensuite() {
        val n = NoyauSecurite(FauxPont())
        // intrusion ET video perdue : renvoie INTRUSION mais verrouille l'erreur
        assertEquals(RaisonBlocage.INTRUSION,
            n.evaluer(EtatCapteurs(intrusionDetectee = true, videoOk = false)))
        // intrusion levee, video revenue : l'erreur verrouillee ressort
        assertEquals(RaisonBlocage.ERREUR_SYSTEME, n.evaluer(sain()))
    }

    // ------------------------------------------------------------ RTK + peremption
    @Test fun rtk_sujet_perdu_bloque() {
        val n = NoyauSecurite(FauxPont())
        assertEquals(RaisonBlocage.ERREUR_SYSTEME, n.evaluer(EtatCapteurs(rtkSujetOk = false)))
        assertEquals(DetailErreur.RTK_SUJET_PERDU, n.detailErreur)
    }

    @Test fun video_perimee_bloque() {
        val n = NoyauSecurite(FauxPont())
        assertEquals(RaisonBlocage.ERREUR_SYSTEME, n.evaluer(EtatCapteurs(ageVideoS = 1.0)))  // > 0.5 s
        assertEquals(DetailErreur.VIDEO_PERIMEE, n.detailErreur)
    }

    @Test fun telemetrie_perimee_bloque() {
        val n = NoyauSecurite(FauxPont())
        assertEquals(RaisonBlocage.ERREUR_SYSTEME, n.evaluer(EtatCapteurs(ageTelemetrieS = 1.0)))
        assertEquals(DetailErreur.TELEMETRIE_PERIMEE, n.detailErreur)
    }

    // ------------------------------------------------------------ arret immediat
    @Test fun arret_immediat_envoie_zero() {
        val pont = FauxPont(); val n = NoyauSecurite(pont)
        n.arretImmediat()
        assertEquals(ZERO, pont.sorties.last())
    }
}
