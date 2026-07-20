package ca.cineflight.stage.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de GeoBarriere : enveloppe de vol polygonale (le drone ne sort JAMAIS).
 * Module pur (geometrie). Doctrine "non defini = non sur" : polygone invalide
 * (< 3 sommets) => rien n'est dedans.
 *
 * Carre de reference ~111 m de cote, place autour de (0,0) pour une projection
 * plane simple (cos(0)=1 => 0.001 deg = 111.32 m en lat comme en lon).
 */
class GeoBarriereTest {

    private fun pt(lat: Double, lon: Double) = GeoBarriere.Point(lat, lon)

    // carre : (0,0) -> (0,0.001) -> (0.001,0.001) -> (0.001,0)
    private val carre = GeoBarriere(listOf(
        pt(0.0, 0.0), pt(0.0, 0.001), pt(0.001, 0.001), pt(0.001, 0.0)
    ))
    private val centre = pt(0.0005, 0.0005)
    private val dehors = pt(0.002, 0.002)

    // ------------------------------------------------------- validite
    @Test fun validite_exige_trois_sommets() {
        assertTrue(carre.valide)
        assertFalse("2 sommets -> invalide", GeoBarriere(listOf(pt(0.0, 0.0), pt(0.0, 0.001))).valide)
        assertFalse("vide -> invalide", GeoBarriere().valide)
    }

    // ------------------------------------------------------- estDedans
    @Test fun centre_est_dedans_exterieur_est_dehors() {
        assertTrue("le centre doit etre dedans", carre.estDedans(centre))
        assertFalse("un point loin doit etre dehors", carre.estDedans(dehors))
    }

    @Test fun polygone_invalide_bloque_tout() {
        // doctrine "non defini = non sur" : sans enveloppe valide, aucun point n'est dedans
        assertFalse(GeoBarriere().estDedans(centre))
        assertFalse(GeoBarriere(listOf(pt(0.0, 0.0), pt(0.0, 0.001))).estDedans(centre))
    }

    // ------------------------------------------------------- distanceAuBordM
    @Test fun distance_au_bord_du_centre() {
        // centre a 0.0005 deg de chaque bord ~ 55.7 m
        assertEquals(55.7, carre.distanceAuBordM(centre), 1.5)
        // polygone invalide -> 0
        assertEquals(0.0, GeoBarriere().distanceAuBordM(centre), 0.0)
    }

    // ------------------------------------------------------- ecreterCible
    @Test fun cible_dans_la_zone_renvoyee_telle_quelle() {
        val cible = pt(0.0005, 0.0006)   // dedans, bien au-dela de la marge de 5 m
        val res = carre.ecreterCible(centre, cible, margeM = 5.0)
        assertFalse("cible sure -> pas ramenee", res.ramenee)
        assertTrue(res.dansZone)
        assertEquals(cible, res.point)
    }

    @Test fun cible_hors_zone_ramenee_a_la_frontiere() {
        val res = carre.ecreterCible(centre, dehors, margeM = 5.0)
        assertTrue("cible hors zone -> bridee", res.ramenee)
        assertTrue("le point ramene doit etre dans la zone", res.dansZone)
        assertTrue("le point ramene doit reellement etre dedans", carre.estDedans(res.point))
    }

    @Test fun origine_hors_zone_ne_bouge_pas() {
        // origine ET cible hors zone : situation anormale -> on renvoie l'origine
        // (ne pas bouger, le noyau gelera), ramenee=true. Il faut que la cible soit
        // AUSSI dangereuse, sinon la fonction va (a raison) vers une cible sure.
        val res = carre.ecreterCible(dehors, pt(0.003, 0.003), margeM = 5.0)
        assertTrue(res.ramenee)
        assertFalse(res.dansZone)
        assertEquals(dehors, res.point)
    }

    @Test fun polygone_invalide_refuse_le_mouvement() {
        val res = GeoBarriere().ecreterCible(centre, centre, margeM = 5.0)
        assertTrue("sans enveloppe -> refus (ramenee)", res.ramenee)
        assertFalse(res.dansZone)
        assertEquals(centre, res.point)   // renvoie l'origine, ne bouge pas
    }
}
