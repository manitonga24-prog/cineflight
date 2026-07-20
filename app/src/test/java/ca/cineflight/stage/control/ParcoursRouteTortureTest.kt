package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ===================== TORTURE DE PARCOURSROUTE =====================
 * Le calcul de cible du suivi vehicule sur rail. On lui donne des ages NaN,
 * des sujets a position NaN, des vitesses aberrantes, des routes degenerees,
 * et on exige : jamais de verdict OK avec une donnee non definie, jamais de
 * cible NaN. "Non defini = non sur."
 *
 * EXPOSE_ = ecrit pour ECHOUER sur le code actuel (vraie faille).
 * ROBUSTE_ = doit passer.
 * ===================================================================
 */
class ParcoursRouteTortureTest {

    private val lat0 = 45.5
    private val lon0 = -73.56
    private val mLat = 111_320.0
    private fun mLon() = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private fun pt(estM: Double, nordM: Double) =
        GeoBarriere.Point(lat0 + nordM / mLat, lon0 + estM / mLon())
    private fun routeDroite() = ParcoursRoute(listOf(pt(0.0, 0.0), pt(200.0, 0.0)))

    // =====================================================================
    //  FAILLES ATTENDUES (ROUGE)
    // =====================================================================

    /** age RTK NaN : "NaN > seuil" faux -> la barriere d'age est contournee. */
    @Test fun EXPOSE_age_NaN_doit_bloquer() {
        val res = routeDroite().calculerCible(
            sujet = pt(50.0, 3.0), rtk = "FIX", ageRtkS = Double.NaN,
            vitesseServeurMps = 10.0, capGnssDeg = 90.0,
            decalageLateralM = 20.0, cote = "droite", anticipation = true)
        assertEquals("age NaN => non defini => BLOQUE_RTK_VIEUX",
            ParcoursRoute.Verdict.BLOQUE_RTK_VIEUX, res.verdict)
    }

    /** sujet a position NaN : aucune garde -> cible NaN avec verdict OK. */
    @Test fun EXPOSE_sujet_NaN_ne_doit_pas_donner_de_cible() {
        val res = routeDroite().calculerCible(
            sujet = GeoBarriere.Point(Double.NaN, Double.NaN), rtk = "FIX",
            ageRtkS = 0.1, vitesseServeurMps = 10.0, capGnssDeg = 90.0)
        assertTrue("sujet NaN => bloque, jamais de cible NaN",
            res.verdict != ParcoursRoute.Verdict.OK && res.cibleDrone == null)
    }

    // =====================================================================
    //  ROBUSTESSE (VERT)
    // =====================================================================

    /** Routes degenerees : < 2 points OU longueur nulle -> BLOQUE_ROUTE_INVALIDE. */
    @Test fun ROBUSTE_routes_degenerees_bloquent() {
        val unPoint = ParcoursRoute(listOf(pt(0.0, 0.0)))
        assertEquals(ParcoursRoute.Verdict.BLOQUE_ROUTE_INVALIDE,
            unPoint.calculerCible(pt(0.0, 0.0), "FIX", 0.1, 10.0).verdict)
        val longueurNulle = ParcoursRoute(listOf(pt(0.0, 0.0), pt(0.0, 0.0)))
        assertEquals(ParcoursRoute.Verdict.BLOQUE_ROUTE_INVALIDE,
            longueurNulle.calculerCible(pt(0.0, 0.0), "FIX", 0.1, 10.0).verdict)
    }

    /** Vitesses aberrantes (enorme, negative, NaN, Infini) : ignorees -> jamais de cible NaN. */
    @Test fun ROBUSTE_vitesses_aberrantes_pas_de_cible_absurde() {
        for (v in listOf(1e9, -50.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val res = routeDroite().calculerCible(
                sujet = pt(50.0, 0.0), rtk = "FIX", ageRtkS = 0.1,
                vitesseServeurMps = v, capGnssDeg = 90.0, anticipation = true)
            val c = res.cibleDrone
            if (c != null) assertTrue("cible finie malgre vitesse=$v",
                c.lat.isFinite() && c.lon.isFinite())
            assertTrue("vitesse utilisee finie malgre $v", res.vitesseUtiliseeMps.isFinite())
        }
    }

    /** Sujet tres loin du rail : la cible reste sur le rail, finie. */
    @Test fun ROBUSTE_sujet_tres_loin_reste_borne() {
        val res = routeDroite().calculerCible(
            sujet = pt(50.0, 10_000.0), rtk = "FIX", ageRtkS = 0.1,
            vitesseServeurMps = 10.0, capGnssDeg = 90.0)
        val c = res.cibleDrone
        if (c != null) assertTrue(c.lat.isFinite() && c.lon.isFinite())
    }
}
