package ca.cineflight.stage.sentinelle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ===================== TORTURE DU NOYAU SECURITE =====================
 * Le module le PLUS critique : l'unique point de passage des commandes moteur.
 * On lui balance des saloperies et on exige que la regle gravee tienne :
 * "le noyau gagne toujours", priorite STOP > INTRUSION > ERREUR, "non vu != sur".
 *
 * EXPOSE_ : ecrit pour ECHOUER sur le code actuel = vraie faille.
 * ROBUSTE_ : doit passer.
 * Lancer seul : .\gradlew testDebugUnitTest --tests "*NoyauSecuriteTortureTest" -i
 * ====================================================================
 */
class NoyauSecuriteTortureTest {

    private class FauxPont : PontDrone {
        data class V(val p: Double, val r: Double, val t: Double, val y: Double)
        var derniere: V? = null
        override fun envoyerVitesses(pitch: Double, roll: Double, throttle: Double, yaw: Double) {
            derniere = V(pitch, roll, throttle, yaw)
        }
        override fun orienterNacelle(pitchDeg: Double, yawDeg: Double, yawAbsolu: Boolean) {}
    }
    private val ZERO = FauxPont.V(0.0, 0.0, 0.0, 0.0)

    // =====================================================================
    //  FAILLES ATTENDUES (ROUGE = vraie faille)
    // =====================================================================

    /** age video NaN : "NaN > seuil" est faux -> le sens video "perime" passe pour frais. */
    @Test fun EXPOSE_age_video_NaN_doit_etre_un_danger() {
        val n = NoyauSecurite(FauxPont())
        assertEquals("age video NaN => non vu => ERREUR_SYSTEME (doctrine)",
            RaisonBlocage.ERREUR_SYSTEME, n.evaluer(EtatCapteurs(ageVideoS = Double.NaN)))
    }

    /** age telemetrie NaN : meme trou. */
    @Test fun EXPOSE_age_telemetrie_NaN_doit_etre_un_danger() {
        val n = NoyauSecurite(FauxPont())
        assertEquals(RaisonBlocage.ERREUR_SYSTEME,
            n.evaluer(EtatCapteurs(ageTelemetrieS = Double.POSITIVE_INFINITY)))
    }

    /** aucune commande NaN ne doit atteindre le pont, meme sans danger. */
    @Test fun EXPOSE_intention_NaN_ne_doit_pas_atteindre_le_pont() {
        val p = FauxPont(); val n = NoyauSecurite(p)
        n.soumettreIntention(Double.NaN, 1.0, Double.POSITIVE_INFINITY, 0.0, EtatCapteurs())
        val v = p.derniere!!
        assertTrue("le noyau ne doit JAMAIS transmettre un NaN/Infini au pont",
            v.p.isFinite() && v.r.isFinite() && v.t.isFinite() && v.y.isFinite())
    }

    // =====================================================================
    //  ROBUSTESSE (VERT)
    // =====================================================================

    /** Matrice de priorite : les 8 combinaisons (stop, intrusion, erreur) -> la bonne raison. */
    @Test fun ROBUSTE_priorite_absolue_sur_les_8_combinaisons() {
        for (stop in listOf(false, true))
            for (intr in listOf(false, true))
                for (err in listOf(false, true)) {
                    val n = NoyauSecurite(FauxPont())   // noyau frais (le verrou d'erreur ne fuit pas)
                    val c = EtatCapteurs(
                        videoOk = !err, stopPilote = stop, intrusionDetectee = intr)
                    val attendu = when {
                        stop -> RaisonBlocage.STOP_PILOTE
                        intr -> RaisonBlocage.INTRUSION
                        err -> RaisonBlocage.ERREUR_SYSTEME
                        else -> RaisonBlocage.AUCUNE
                    }
                    assertEquals("stop=$stop intr=$intr err=$err", attendu, n.evaluer(c))
                }
    }

    /** Une erreur survenue reste verrouillee meme cachee par une priorite superieure. */
    @Test fun ROBUSTE_erreur_verrouillee_sous_chaos() {
        val n = NoyauSecurite(FauxPont())
        // erreur (video perdue) PENDANT un stop : le stop masque, mais l'erreur se verrouille
        assertEquals(RaisonBlocage.STOP_PILOTE,
            n.evaluer(EtatCapteurs(videoOk = false, stopPilote = true)))
        // stop leve, video revenue : l'erreur verrouillee doit ressortir
        assertEquals(RaisonBlocage.ERREUR_SYSTEME, n.evaluer(EtatCapteurs()))
        // meme apres 100 cycles sains, elle reste verrouillee tant qu'on n'acquitte pas
        repeat(100) { n.evaluer(EtatCapteurs()) }
        assertEquals(RaisonBlocage.ERREUR_SYSTEME, n.evaluer(EtatCapteurs()))
        n.acquitterErreur()
        assertEquals(RaisonBlocage.AUCUNE, n.evaluer(EtatCapteurs()))
    }

    /** arretImmediat envoie (0,0,0,0), meme en pleine erreur. */
    @Test fun ROBUSTE_arret_immediat_toujours_zero() {
        val p = FauxPont(); val n = NoyauSecurite(p)
        n.evaluer(EtatCapteurs(videoOk = false))
        n.arretImmediat()
        assertEquals(ZERO, p.derniere)
    }

    /** Sous danger, une intention demesuree est ecrasee par (0,0,0,0). */
    @Test fun ROBUSTE_danger_ecrase_toute_intention() {
        val p = FauxPont(); val n = NoyauSecurite(p)
        val raison = n.soumettreIntention(1e9, -1e9, 1e9, 1e9, EtatCapteurs(stopPilote = true))
        assertEquals(RaisonBlocage.STOP_PILOTE, raison)
        assertEquals("danger -> (0,0,0,0) quoi qu'il arrive", ZERO, p.derniere)
    }
}
