package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'indice de qualite de cadrage (Phase 2) :
 * 40% taille + 25% nombre + 20% lead room + 15% stabilite, tout dans [0,1], NaN-safe.
 */
class SoccerCadrageScoreTest {

    // --- Composante TAILLE ---

    @Test fun taille_a_la_cible_donne_1() {
        val s = SoccerCadrageScore(tailleCible = 0.12f)
        assertEquals(1f, s.composanteTaille(0.12f), 0.001f)
    }

    @Test fun taille_loin_de_la_cible_donne_0() {
        val s = SoccerCadrageScore(tailleCible = 0.12f, tailleTolerance = 0.10f)
        // 0.12 + 0.10 (une tolerance) -> 0 ; 0.9 -> 0.
        assertEquals(0f, s.composanteTaille(0.22f), 0.001f)
        assertEquals(0f, s.composanteTaille(0.9f), 0.001f)
    }

    @Test fun taille_a_mi_tolerance_donne_environ_moitie() {
        val s = SoccerCadrageScore(tailleCible = 0.12f, tailleTolerance = 0.10f)
        assertEquals(0.5f, s.composanteTaille(0.17f), 0.02f)
    }

    // --- Composante NOMBRE ---

    @Test fun nombre_sature_a_la_cible() {
        val s = SoccerCadrageScore(nbJoueursCible = 6)
        assertEquals(1f, s.composanteNombre(6), 0.001f)
        assertEquals(1f, s.composanteNombre(12), 0.001f)   // sature
        assertEquals(0.5f, s.composanteNombre(3), 0.001f)
        assertEquals(0f, s.composanteNombre(0), 0.001f)
        assertEquals(0f, s.composanteNombre(-4), 0.001f)   // fail-safe
    }

    // --- Composante LEAD ROOM ---

    @Test fun lead_room_ideal_quand_action_opposee_a_la_direction() {
        val s = SoccerCadrageScore()
        // Jeu vers la droite (dirX=+1) -> action idealement a GAUCHE (0.5-0.18=0.32).
        val bon = s.composanteLeadRoom(actionCx = 0.32f, dirX = 1f)
        assertTrue("bon lead room attendu haut, obtenu $bon", bon > 0.95f)
    }

    @Test fun lead_room_mauvais_quand_action_du_meme_cote_que_la_direction() {
        val s = SoccerCadrageScore()
        // Jeu vers la droite mais action collee a droite -> pas d'espace devant -> bas.
        val mauvais = s.composanteLeadRoom(actionCx = 0.95f, dirX = 1f)
        val bon = s.composanteLeadRoom(actionCx = 0.32f, dirX = 1f)
        assertTrue("mauvais ($mauvais) doit etre < bon ($bon)", mauvais < bon)
    }

    @Test fun lead_room_symetrique_selon_la_direction() {
        val s = SoccerCadrageScore()
        val versDroite = s.composanteLeadRoom(0.32f, 1f)   // action a gauche, jeu a droite
        val versGauche = s.composanteLeadRoom(0.68f, -1f)  // action a droite, jeu a gauche
        assertEquals(versDroite, versGauche, 0.001f)
    }

    // --- Composante STABILITE ---

    @Test fun premiere_frame_est_neutre() {
        val s = SoccerCadrageScore()
        assertEquals(1f, s.composanteStabilite(0.5f, 0.5f), 0.001f)
    }

    @Test fun action_immobile_reste_stable() {
        val s = SoccerCadrageScore()
        s.calculer(0.12f, 3, 0.5f, 0.5f, 0f)       // memorise (0.5,0.5)
        val d = s.calculer(0.12f, 3, 0.5f, 0.5f, 0f) // meme position
        assertEquals(1f, d.stabilite, 0.001f)
    }

    @Test fun grand_saut_fait_chuter_la_stabilite() {
        val s = SoccerCadrageScore()
        s.calculer(0.12f, 3, 0.1f, 0.1f, 0f)          // memorise (0.1,0.1)
        val d = s.calculer(0.12f, 3, 0.9f, 0.9f, 0f)  // saut enorme
        assertEquals(0f, d.stabilite, 0.001f)
    }

    @Test fun reset_efface_l_historique_de_stabilite() {
        val s = SoccerCadrageScore()
        s.calculer(0.12f, 3, 0.1f, 0.1f, 0f)
        s.reset()
        val d = s.calculer(0.12f, 3, 0.9f, 0.9f, 0f)  // apres reset -> premiere frame
        assertEquals(1f, d.stabilite, 0.001f)
    }

    // --- Score global ---

    @Test fun cadrage_parfait_proche_de_1() {
        val s = SoccerCadrageScore(tailleCible = 0.12f, nbJoueursCible = 6)
        // taille cible, 6 joueurs, lead room ideal, stabilite (1e frame neutre).
        val d = s.calculer(0.12f, 6, actionCx = 0.32f, actionCy = 0.5f, dirX = 1f)
        assertTrue("total attendu ~1, obtenu ${d.total}", d.total > 0.97f)
    }

    @Test fun cadrage_mauvais_proche_de_0() {
        val s = SoccerCadrageScore(tailleCible = 0.12f, tailleTolerance = 0.10f, nbJoueursCible = 6)
        // taille aberrante, 0 joueur, action du mauvais cote. (stabilite 1e frame = neutre)
        val d = s.calculer(0.9f, 0, actionCx = 0.98f, actionCy = 0.5f, dirX = 1f)
        // Seul le poids stabilite (0.15) peut contribuer sur la 1ere frame.
        assertTrue("total attendu faible, obtenu ${d.total}", d.total < 0.20f)
    }

    @Test fun total_toujours_dans_0_1() {
        val s = SoccerCadrageScore()
        val d = s.calculer(0.12f, 100, 0.5f, 0.5f, 0.5f)
        assertTrue(d.total in 0f..1f)
        assertTrue(d.taille in 0f..1f && d.nombre in 0f..1f)
        assertTrue(d.leadRoom in 0f..1f && d.stabilite in 0f..1f)
    }

    @Test fun ponderation_respecte_40_25_20_15() {
        // Score isolant chaque composante : on met une seule composante a 1, le reste a 0.
        // Astuce : impossible d'isoler proprement via l'API publique, on verifie plutot
        // que le total = somme ponderee des composantes rapportees dans Detail.
        val s = SoccerCadrageScore()
        val d = s.calculer(0.15f, 4, 0.4f, 0.5f, 0.6f)
        val attendu = d.taille * 0.40f + d.nombre * 0.25f + d.leadRoom * 0.20f + d.stabilite * 0.15f
        assertEquals(attendu.coerceIn(0f, 1f), d.total, 0.001f)
    }

    // --- NaN / torture (fail-safe) ---

    @Test fun nan_partout_ne_casse_pas() {
        val s = SoccerCadrageScore()
        val d = s.calculer(Float.NaN, 3, Float.NaN, Float.NaN, Float.NaN)
        assertTrue(!d.total.isNaN() && d.total in 0f..1f)
        assertEquals(0f, d.taille, 0.001f)
        assertEquals(0f, d.leadRoom, 0.001f)
        assertEquals(0f, d.stabilite, 0.001f)
    }

    @Test fun infini_neutralise() {
        val s = SoccerCadrageScore()
        val d = s.calculer(Float.POSITIVE_INFINITY, 3, Float.NEGATIVE_INFINITY, 2f, Float.POSITIVE_INFINITY)
        assertTrue(!d.total.isNaN() && d.total in 0f..1f)
    }

    @Test fun poids_non_normalises_sont_renormalises() {
        // Poids sommant a 2 -> le total reste dans [0,1] et coherent.
        val s = SoccerCadrageScore(
            poidsTaille = 0.8f, poidsNombre = 0.5f, poidsLeadRoom = 0.4f, poidsStabilite = 0.3f)
        val d = s.calculer(0.12f, 6, 0.32f, 0.5f, 1f)
        assertTrue(d.total in 0f..1f)
        assertTrue("cadrage bon attendu, obtenu ${d.total}", d.total > 0.9f)
    }

    // --- 5e critere : FACTEUR DE CONFIANCE (Option 2, multiplicateur, plancher 0.5) ---

    @Test fun facteur_confiance_suit_la_table_validee() {
        val s = SoccerCadrageScore()
        assertEquals(1.00f, s.facteurConfiance(1.0f), 0.001f)
        assertEquals(0.90f, s.facteurConfiance(0.8f), 0.001f)
        assertEquals(0.75f, s.facteurConfiance(0.5f), 0.001f)
        assertEquals(0.60f, s.facteurConfiance(0.2f), 0.001f)
        assertEquals(0.50f, s.facteurConfiance(0.0f), 0.001f)
    }

    @Test fun facteur_confiance_plancher_meme_si_negatif_ou_nan() {
        val s = SoccerCadrageScore()
        assertEquals(0.50f, s.facteurConfiance(-3f), 0.001f)
        assertEquals(0.50f, s.facteurConfiance(Float.NaN), 0.001f)
        assertEquals(1.00f, s.facteurConfiance(5f), 0.001f)   // borne haute
    }

    @Test fun confiance_module_le_score_final() {
        val s = SoccerCadrageScore()
        // cadrage parfait (~0.80+ selon composantes) ; conf 1 -> total = scoreCadrage.
        val plein = s.calculer(0.12f, 6, 0.32f, 0.5f, 1f, confiance = 1f)
        assertEquals(plein.scoreCadrage, plein.total, 0.001f)
        assertEquals(1f, plein.facteurConfiance, 0.001f)
    }

    @Test fun confiance_faible_baisse_le_total_mais_pas_le_score_cadrage() {
        val s = SoccerCadrageScore()
        val bon = s.calculer(0.12f, 6, 0.32f, 0.5f, 1f, confiance = 1f)
        s.reset()
        val douteux = s.calculer(0.12f, 6, 0.32f, 0.5f, 1f, confiance = 0.0f)
        // meme qualite de cadrage...
        assertEquals(bon.scoreCadrage, douteux.scoreCadrage, 0.001f)
        // ...mais total divise par ~2 (facteur 1.0 -> 0.5).
        assertEquals(bon.total * 0.5f, douteux.total, 0.01f)
        assertEquals(0.5f, douteux.facteurConfiance, 0.001f)
    }

    @Test fun total_jamais_zero_meme_confiance_nulle_si_cadrage_bon() {
        val s = SoccerCadrageScore()
        val d = s.calculer(0.12f, 6, 0.32f, 0.5f, 1f, confiance = 0f)
        // plancher 0.5 -> le realisateur n'est jamais fige a 0.
        assertTrue("total doit rester > 0, obtenu ${d.total}", d.total > 0.3f)
    }
}
