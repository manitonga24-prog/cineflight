package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests JVM de ParcoursRoute (PHASE 1). Aucune dependance Android/DJI.
 * Couvre les 11 cas obligatoires figes avant toute integration.
 *
 * Repere de reference : petite portion de route pres de Montreal. On construit des
 * polylignes en metres convertis en deg pour rester lisibles.
 */
class ParcoursRouteTest {

    private val lat0 = 45.5000
    private val lon0 = -73.5600
    private val mLat = 111_320.0
    private fun mLon(lat: Double) = 111_320.0 * Math.cos(Math.toRadians(lat))

    /** Cree un point a (estM, nordM) metres du point de reference. */
    private fun pt(estM: Double, nordM: Double): GeoBarriere.Point =
        GeoBarriere.Point(lat0 + nordM / mLat, lon0 + estM / mLon(lat0))

    /** Route droite est-ouest : de (0,0) a (200,0) le long de l'axe est. */
    private fun routeDroite() = listOf(pt(0.0, 0.0), pt(200.0, 0.0))

    /** Route courbe : quart de cercle grossier (multi-segments). */
    private fun routeCourbe(): List<GeoBarriere.Point> {
        val pts = ArrayList<GeoBarriere.Point>()
        for (i in 0..10) {
            val ang = Math.toRadians(90.0 * i / 10.0)
            pts.add(pt(100.0 * Math.sin(ang), 100.0 * (1 - Math.cos(ang))))
        }
        return pts
    }

    /** Virage serre a 90 degres : deux segments perpendiculaires. */
    private fun routeVirage() = listOf(pt(0.0, 0.0), pt(100.0, 0.0), pt(100.0, 100.0))

    // 1. ROUTE DROITE, vitesse serveur, anticipation.
    @Test
    fun t01_routeDroite_projeteEtAnticipe() {
        val r = ParcoursRoute(routeDroite())
        // sujet a 50 m sur la route, decale 3 m au nord (bruit lateral)
        val sujet = pt(50.0, 3.0)
        val res = r.calculerCible(
            sujet = sujet, rtk = "FIX", ageRtkS = 0.3,
            vitesseServeurMps = 10.0, capGnssDeg = 90.0,  // cap est = sens avant
            decalageLateralM = 20.0, cote = "droite",
            anticipation = true
        )
        assertEquals(ParcoursRoute.Verdict.OK, res.verdict)
        assertEquals(ParcoursRoute.SourceVitesse.SERVEUR, res.sourceVitesse)
        assertEquals(ParcoursRoute.Sens.AVANT, res.sens)
        // abscisse projetee ~50 m ; anticipee = 50 + 10*tau(10 m/s=36 km/h ~0.44 s) > 50
        assertNotNull(res.projection)
        assertTrue("abscisse ~50", abs(res.projection!!.s - 50.0) < 1.0)
        assertNotNull(res.positionAnticipee)
        // ecart lateral mesure ~3 m
        assertTrue("ecart lateral ~3", abs(res.projection!!.ecartLateralM - 3.0) < 0.5)
    }

    // 2. ROUTE COURBE : la cible reste sur/pres du rail (pas de sortie en espace libre).
    @Test
    fun t02_routeCourbe_cibleSurRail() {
        val r = ParcoursRoute(routeCourbe())
        val sujet = r.pointAPublic(r.longueurM * 0.5)   // pile au milieu du rail
        val res = r.calculerCible(
            sujet = sujet, rtk = "FIX", ageRtkS = 0.2,
            vitesseServeurMps = 8.0, capGnssDeg = null,
            decalageLateralM = 0.0, cote = "droite",   // pas de lateral : cible = point-sujet
            anticipation = true
        )
        assertEquals(ParcoursRoute.Verdict.OK, res.verdict)
        // avec decalage lateral 0, la cible drone doit coincider avec un point du rail :
        // on reprojette la cible et l'ecart lateral doit etre ~0.
        val reproj = r.projeter(res.cibleDrone!!)
        assertTrue("cible sur le rail (ecart<1m)", reproj.ecartLateralM < 1.0)
    }

    // 3. VIRAGE SERRE : la tangente lissee ne provoque pas de saut aberrant de yaw.
    @Test
    fun t03_virageSerre_tangenteLissee() {
        val r = ParcoursRoute(routeVirage(), ParcoursRoute.Config(lissageTangenteM = 12.0))
        // deux abscisses proches de part et d'autre du coin (a s=100)
        val avant = r.tangenteAPublic(95.0)
        val apres = r.tangenteAPublic(105.0)
        // les tangentes changent (virage) mais restent unitaires et finies
        assertTrue(avant.first.isFinite() && avant.second.isFinite())
        assertTrue(apres.first.isFinite() && apres.second.isFinite())
        val nAvant = Math.hypot(avant.first, avant.second)
        val nApres = Math.hypot(apres.first, apres.second)
        assertTrue("tangente unitaire avant", abs(nAvant - 1.0) < 1e-6)
        assertTrue("tangente unitaire apres", abs(nApres - 1.0) < 1e-6)
    }

    // 4. FIN DE POLYLIGNE : sujet au-dela du dernier point -> abscisse clampee a L.
    @Test
    fun t04_finDePolyligne_abscisseClampee() {
        val r = ParcoursRoute(routeDroite())
        val sujet = pt(260.0, 0.0)   // 60 m au-dela de la fin (200)
        val res = r.calculerCible(
            sujet = sujet, rtk = "FIX", ageRtkS = 0.2,
            vitesseServeurMps = 12.0, anticipation = true
        )
        assertEquals(ParcoursRoute.Verdict.OK, res.verdict)
        assertTrue("abscisse clampee a L", abs(res.projection!!.s - r.longueurM) < 0.5)
        // l'anticipation ne depasse jamais L
        val reproj = r.projeter(res.positionAnticipee!!)
        assertTrue(reproj.s <= r.longueurM + 1e-6)
    }

    // 5. VITESSE SERVEUR valide -> source = SERVEUR (prioritaire sur derivee).
    @Test
    fun t05_vitesseServeur_prioritaire() {
        val r = ParcoursRoute(routeDroite())
        val hist = listOf(
            ParcoursRoute.EchantillonPosition(pt(40.0, 0.0), 0L, "FIX", 0.2),
            ParcoursRoute.EchantillonPosition(pt(50.0, 0.0), 500_000_000L, "FIX", 0.2) // +10m/0.5s=20m/s
        )
        val res = r.calculerCible(
            sujet = pt(50.0, 0.0), rtk = "FIX", ageRtkS = 0.2,
            vitesseServeurMps = 9.0, historique = hist, anticipation = true
        )
        assertEquals(ParcoursRoute.SourceVitesse.SERVEUR, res.sourceVitesse)
        assertEquals(9.0, res.vitesseUtiliseeMps, 1e-9)
    }

    // 6. FALLBACK ANDROID : pas de vitesse serveur -> derivee filtree utilisee.
    @Test
    fun t06_fallbackAndroid_deriveeUtilisee() {
        val r = ParcoursRoute(routeDroite())
        val hist = listOf(
            ParcoursRoute.EchantillonPosition(pt(40.0, 0.0), 0L, "FIX", 0.2),
            ParcoursRoute.EchantillonPosition(pt(45.0, 0.0), 500_000_000L, "FIX", 0.2) // 5m/0.5s=10m/s
        )
        val res = r.calculerCible(
            sujet = pt(45.0, 0.0), rtk = "FIX", ageRtkS = 0.2,
            vitesseServeurMps = null, historique = hist, anticipation = true
        )
        assertEquals(ParcoursRoute.SourceVitesse.DERIVEE_ANDROID, res.sourceVitesse)
        assertEquals(10.0, res.vitesseUtiliseeMps, 0.5)
    }

    // 7. SAUT GNSS REJETE : deplacement aberrant -> derivee refusee -> source AUCUNE.
    @Test
    fun t07_sautGnss_rejete() {
        val r = ParcoursRoute(routeDroite())
        val hist = listOf(
            ParcoursRoute.EchantillonPosition(pt(0.0, 0.0), 0L, "FIX", 0.2),
            ParcoursRoute.EchantillonPosition(pt(500.0, 0.0), 300_000_000L, "FIX", 0.2) // 500m/0.3s aberrant
        )
        val res = r.calculerCible(
            sujet = pt(50.0, 0.0), rtk = "FIX", ageRtkS = 0.2,
            vitesseServeurMps = null, historique = hist, anticipation = true
        )
        assertEquals(ParcoursRoute.SourceVitesse.AUCUNE, res.sourceVitesse)
        assertEquals(0.0, res.vitesseUtiliseeMps, 1e-9)
        // sans vitesse, pas d'anticipation : abscisse anticipee == projetee
        assertEquals(res.projection!!.s, r.projeter(res.positionAnticipee!!).s, 0.5)
    }

    // 8. RTK TROP ANCIEN selon la vitesse -> BLOQUE_RTK_VIEUX.
    @Test
    fun t08_rtkTropAncien_selonVitesse() {
        val r = ParcoursRoute(routeDroite())
        // a 25 m/s (90 km/h), ageMax dyn = clamp(40/25=1.6 -> [0.4,2.5]) = 1.6 s.
        val res = r.calculerCible(
            sujet = pt(50.0, 0.0), rtk = "FIX", ageRtkS = 2.0,  // 2.0 > 1.6
            vitesseServeurMps = 25.0, anticipation = true
        )
        assertEquals(ParcoursRoute.Verdict.BLOQUE_RTK_VIEUX, res.verdict)
        assertNull(res.cibleDrone)
        assertNotNull(res.raisonBlocage)
        // le meme age serait OK a pied (1 m/s) : ageMax = clamp(40/1=40 ->[.4,2.5])=2.5
        val resPied = r.calculerCible(
            sujet = pt(50.0, 0.0), rtk = "FIX", ageRtkS = 2.0,
            vitesseServeurMps = 1.0, anticipation = true
        )
        assertEquals(ParcoursRoute.Verdict.OK, resPied.verdict)
    }

    // 9. MARCHE ARRIERE : cap oppose a la tangente -> Sens.ARRIERE, anticipe vers s decroissant.
    @Test
    fun t09_marcheArriere_sensArriere() {
        val r = ParcoursRoute(routeDroite())
        val res = r.calculerCible(
            sujet = pt(100.0, 0.0), rtk = "FIX", ageRtkS = 0.2,
            vitesseServeurMps = 5.0, capGnssDeg = 270.0,  // cap ouest = oppose a la route est
            anticipation = true
        )
        assertEquals(ParcoursRoute.Verdict.OK, res.verdict)
        assertEquals(ParcoursRoute.Sens.ARRIERE, res.sens)
        // anticipation vers l'arriere : abscisse anticipee < projetee (100)
        val sAnticipee = r.projeter(res.positionAnticipee!!).s
        assertTrue("anticipe vers l'arriere", sAnticipee < 100.0)
    }

    // 10. VITESSE NULLE : tau=0 -> anticipation nulle -> cible = point courant.
    @Test
    fun t10_vitesseNulle_pasAnticipation() {
        val r = ParcoursRoute(routeDroite())
        val res = r.calculerCible(
            sujet = pt(80.0, 0.0), rtk = "FIX", ageRtkS = 0.2,
            vitesseServeurMps = 0.0, anticipation = true
        )
        assertEquals(ParcoursRoute.Verdict.OK, res.verdict)
        assertEquals(0.0, res.vitesseUtiliseeMps, 1e-9)
        // abscisse anticipee == projetee (~80)
        assertEquals(80.0, r.projeter(res.positionAnticipee!!).s, 0.5)
    }

    // 11. COMPATIBILITE PAR DEFAUT : anticipation OFF (defaut) -> comportement neutre,
    //     cible = point-sujet + offsets, aucune projection avancee.
    @Test
    fun t11_defautsCompatibles_anticipationOff() {
        val r = ParcoursRoute(routeDroite())   // config par defaut
        val res = r.calculerCible(
            sujet = pt(70.0, 0.0), rtk = "FIX", ageRtkS = 0.2,
            vitesseServeurMps = 15.0
            // anticipation non fournie -> false par defaut
        )
        assertEquals(ParcoursRoute.Verdict.OK, res.verdict)
        // sans anticipation, l'abscisse visee == abscisse projetee (~70), meme a 15 m/s
        assertEquals(70.0, r.projeter(res.positionAnticipee!!).s, 0.5)
    }

    // --- helpers d'acces aux fonctions internes pour les tests (memes calculs) ---
    private fun ParcoursRoute.pointAPublic(s: Double) = this.pointA(s)
    private fun ParcoursRoute.tangenteAPublic(s: Double) = this.tangenteA(s)
}
