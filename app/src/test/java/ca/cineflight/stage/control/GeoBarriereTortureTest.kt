package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * ===================== TORTURE DE LA GEO-BARRIERE =====================
 * La geofence est une FONDATION de securite : "le drone ne sort JAMAIS".
 * On la martele avec des polygones degeneres, des coordonnees absurdes, des
 * marges negatives, et 500 cibles aleatoires. L'invariant grave :
 *   une cible ecretee est TOUJOURS dans la zone (ou l'origine sure).
 * =====================================================================
 */
class GeoBarriereTortureTest {

    private fun pt(lat: Double, lon: Double) = GeoBarriere.Point(lat, lon)
    private fun carre() = GeoBarriere(listOf(
        pt(0.0, 0.0), pt(0.0, 0.001), pt(0.001, 0.001), pt(0.001, 0.0)))
    private val centre = pt(0.0005, 0.0005)

    // ------------------------------------------------- polygones invalides
    @Test fun moins_de_trois_sommets_rien_n_est_dedans() {
        for (poly in listOf(
            emptyList(),
            listOf(pt(0.0, 0.0)),
            listOf(pt(0.0, 0.0), pt(0.0, 0.001)))) {
            val g = GeoBarriere(poly)
            assertFalse("polygone < 3 sommets doit etre invalide", g.valide)
            assertFalse("rien n'est dedans sans enveloppe valide", g.estDedans(centre))
            assertEquals("distance au bord = 0 si invalide", 0.0, g.distanceAuBordM(centre), 0.0)
        }
    }

    // ------------------------------------------------- degenerescences
    @Test fun points_colineaires_aire_nulle_ne_contiennent_rien() {
        // 3 sommets alignes -> aire nulle -> fail-safe : aucun point dedans
        val g = GeoBarriere(listOf(pt(0.0, 0.0), pt(0.0, 0.001), pt(0.0, 0.002)))
        assertTrue(g.valide)
        assertFalse(g.estDedans(pt(0.0, 0.001)))
        assertFalse(g.estDedans(centre))
    }

    @Test fun sommets_dupliques_ne_plantent_pas() {
        val g = GeoBarriere(listOf(
            pt(0.0, 0.0), pt(0.0, 0.0), pt(0.001, 0.001), pt(0.001, 0.0)))
        // pas de division par zero / crash ; sorties finies
        assertTrue(g.distanceAuBordM(centre).isFinite())
        val r = g.ecreterCible(centre, pt(0.01, 0.01), margeM = 5.0)
        assertTrue("point ecrete fini", r.point.lat.isFinite() && r.point.lon.isFinite())
    }

    // ------------------------------------------------- entrees absurdes
    @Test fun coordonnees_enormes_ne_sont_jamais_dedans() {
        val g = carre()
        assertFalse(g.estDedans(pt(1e6, 1e6)))
        assertFalse(g.estDedans(pt(-1e9, 1e9)))
        assertFalse("NaN jamais dedans", g.estDedans(pt(Double.NaN, Double.NaN)))
        assertFalse("Infini jamais dedans",
            g.estDedans(pt(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)))
    }

    @Test fun marge_negative_equivaut_a_sans_marge() {
        val g = carre()
        val r = g.ecreterCible(centre, centre, margeM = -5.0)
        assertFalse("cible = origine dedans -> pas ramenee", r.ramenee)
        assertEquals(centre, r.point)
    }

    // ------------------------------------------------- L'INVARIANT DE SURETE
    @Test fun toute_cible_ecretee_reste_dans_la_zone_500_alea() {
        val g = carre()
        val origine = centre                         // le drone est dans la zone
        val rnd = Random(20260714L)
        var ramenees = 0
        repeat(500) {
            // cible n'importe ou dans un carre 10x plus grand (souvent hors zone)
            val cible = pt(rnd.nextDouble() * 0.01 - 0.0045, rnd.nextDouble() * 0.01 - 0.0045)
            val r = g.ecreterCible(origine, cible, margeM = 5.0)
            if (r.ramenee) ramenees++
            assertTrue(
                "INVARIANT VIOLE : une cible ecretee est HORS zone -> $cible => ${r.point}",
                g.estDedans(r.point) || r.point == origine)
        }
        assertTrue("le test doit reellement exercer l'ecretage", ramenees > 100)
        println("GEO-BARRIERE torture ok : 500 cibles, $ramenees ecretees, 0 hors zone")
    }
}
