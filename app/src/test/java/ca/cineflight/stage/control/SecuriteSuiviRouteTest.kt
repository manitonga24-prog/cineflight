package ca.cineflight.stage.control

import ca.cineflight.stage.sentinelle.EtatCapteurs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de SecuriteSuiviRoute : traduit un verdict ParcoursRoute en rtkSujetOk
 * pour le NoyauSecurite. Regle grave : "non defini = non sur" et on ne RELACHE
 * JAMAIS la securite (AND seulement).
 */
class SecuriteSuiviRouteTest {

    private fun resultat(
        verdict: ParcoursRoute.Verdict,
        cible: GeoBarriere.Point?
    ) = ParcoursRoute.Resultat(
        verdict = verdict, raisonBlocage = null, projection = null,
        positionAnticipee = null, cibleDrone = cible, yawDroneDeg = 0.0,
        gimbalDeg = 0.0, sourceVitesse = ParcoursRoute.SourceVitesse.AUCUNE,
        vitesseUtiliseeMps = 0.0, sens = ParcoursRoute.Sens.INDETERMINE,
        erreurEstimeeM = 0.0, ageRtkMaxEffectifS = 0.0
    )

    private val cible = GeoBarriere.Point(45.0, -73.0)

    // ----------------------------------------------------- rtkSujetOk
    @Test fun ok_seulement_si_verdict_ok_et_cible_presente() {
        assertTrue(SecuriteSuiviRoute.rtkSujetOk(resultat(ParcoursRoute.Verdict.OK, cible)))
        assertFalse("OK sans cible -> non sur",
            SecuriteSuiviRoute.rtkSujetOk(resultat(ParcoursRoute.Verdict.OK, null)))
    }

    @Test fun tout_verdict_bloquant_donne_faux() {
        for (v in listOf(
            ParcoursRoute.Verdict.BLOQUE_RTK_ABSENT,
            ParcoursRoute.Verdict.BLOQUE_RTK_VIEUX,
            ParcoursRoute.Verdict.BLOQUE_ERREUR_POSITION,
            ParcoursRoute.Verdict.BLOQUE_ROUTE_INVALIDE
        )) {
            assertFalse("$v doit donner rtkSujetOk=false",
                SecuriteSuiviRoute.rtkSujetOk(resultat(v, cible)))   // meme avec une cible
        }
    }

    // ----------------------------------------------------- capteursPourRoute (AND)
    @Test fun route_ok_conserve_les_capteurs_sains() {
        val base = EtatCapteurs()   // tout OK, rtkSujetOk=true par defaut
        val out = SecuriteSuiviRoute.capteursPourRoute(base, resultat(ParcoursRoute.Verdict.OK, cible))
        assertTrue("route OK + base OK -> rtkSujetOk conserve", out.rtkSujetOk)
        // aucun autre capteur ne doit changer
        assertEquals(base.copy(rtkSujetOk = out.rtkSujetOk), out)
    }

    @Test fun route_bloquee_force_faux() {
        val base = EtatCapteurs()
        val out = SecuriteSuiviRoute.capteursPourRoute(
            base, resultat(ParcoursRoute.Verdict.BLOQUE_RTK_VIEUX, cible))
        assertFalse("route bloquee -> rtkSujetOk=false", out.rtkSujetOk)
    }

    @Test fun ne_relache_jamais_une_securite_deja_fermee() {
        // base deja rtkSujetOk=false : meme une route OK ne doit PAS le rouvrir
        val base = EtatCapteurs(rtkSujetOk = false)
        val out = SecuriteSuiviRoute.capteursPourRoute(base, resultat(ParcoursRoute.Verdict.OK, cible))
        assertFalse("AND : base false reste false meme si route OK", out.rtkSujetOk)
    }
}
