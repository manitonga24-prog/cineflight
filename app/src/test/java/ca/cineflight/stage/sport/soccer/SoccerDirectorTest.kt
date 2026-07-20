package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du realisateur autonome : recherche locale predictive de l'altitude, rate limiter,
 * dt monotone borne, zone morte, plafond phase/35 m, NaN. Tout pur.
 */
class SoccerDirectorTest {

    private fun ns(ms: Long) = ms * 1_000_000L   // ms -> nanos monotones

    // --- Modele predictif : sens du deplacement ---

    @Test fun joueurs_trop_petits_font_descendre() {
        val d = SoccerDirector(vitesseMaxMps = 0.5)
        // taille observee 0.06 (< cible 0.12) -> il faut DESCENDRE pour agrandir.
        // 1ere frame = repli dt ; 2e frame porte la decision.
        d.realiser(ns(0), 22.0, 0.06f, 6, 0.32f, 0.5f, 1f, 18.0, 22.0)
        val p = d.realiser(ns(50), 22.0, 0.06f, 6, 0.32f, 0.5f, 1f, 18.0, 22.0)
        assertEquals(-1, p.sens)
        assertTrue("doit descendre sous 22", p.altitudeM < 22.0)
    }

    @Test fun joueurs_trop_grands_font_monter() {
        val d = SoccerDirector(vitesseMaxMps = 0.5)
        // taille 0.24 (2x trop grand) -> MONTER pour reduire.
        d.realiser(ns(0), 18.0, 0.24f, 6, 0.32f, 0.5f, 1f, 18.0, 22.0)
        val p = d.realiser(ns(50), 18.0, 0.24f, 6, 0.32f, 0.5f, 1f, 18.0, 22.0)
        assertEquals(1, p.sens)
        assertTrue("doit monter au-dessus de 18", p.altitudeM > 18.0)
    }

    @Test fun taille_deja_bonne_reste_en_zone_morte() {
        val d = SoccerDirector(vitesseMaxMps = 0.5, epsilonScore = 0.005f)
        // k = 0.12*20 = 2.4 ; a 20 m taille=0.12 (cible). +-0.5 m degrade -> zone morte.
        d.realiser(ns(0), 20.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        val p = d.realiser(ns(50), 20.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        assertEquals(0, p.sens)
        assertEquals(20.0, p.altitudeM, 0.001)
    }

    // --- Rate limiter ---

    @Test fun variation_bornee_par_vitesse_fois_dt() {
        val d = SoccerDirector(vitesseMaxMps = 0.5, pasSimM = 0.5)
        d.realiser(ns(0), 22.0, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 22.0)  // init
        // dt = 0.05 s -> maxDelta = 0.5*0.05 = 0.025 m. Depuis 22 -> pas plus bas que 21.975.
        val p = d.realiser(ns(50), 22.0, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 22.0)
        assertTrue("changement <= 0.025 m/frame, obtenu ${22.0 - p.altitudeM}", 22.0 - p.altitudeM <= 0.0251)
        assertTrue(p.altitudeM < 22.0)
    }

    @Test fun converge_apres_plusieurs_frames() {
        val d = SoccerDirector(vitesseMaxMps = 0.5, pasSimM = 0.5)
        var t = 0L
        d.realiser(ns(t), 22.0, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 22.0)
        var alt = 22.0
        // 200 frames a 50 ms : doit descendre franchement (joueurs trop petits).
        repeat(200) {
            t += 50
            val p = d.realiser(ns(t), alt, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 22.0)
            alt = p.altitudeM
        }
        assertTrue("apres convergence l'altitude doit avoir bien baisse, alt=$alt", alt < 20.0)
    }

    // --- dt monotone / bornage / pause ---

    @Test fun premiere_frame_utilise_le_repli_dt() {
        val d = SoccerDirector(dtRepliS = 0.05)
        val p = d.realiser(ns(123456), 20.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        assertEquals(0.05, p.dtS, 1e-9)
    }

    @Test fun dt_negatif_ou_nul_repli() {
        val d = SoccerDirector(dtRepliS = 0.05)
        d.realiser(ns(1000), 20.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        // meme timestamp -> brut = 0 -> repli.
        val p = d.realiser(ns(1000), 20.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        assertEquals(0.05, p.dtS, 1e-9)
    }

    @Test fun pause_longue_est_ecretee_pas_de_grand_saut() {
        val d = SoccerDirector(vitesseMaxMps = 0.5, dtMaxS = 0.25)
        d.realiser(ns(0), 22.0, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 22.0)  // init
        // pause de 10 s : dt DOIT etre ecrete a 0.25 -> saut <= 0.5*0.25 = 0.125 m.
        val p = d.realiser(ns(10_000), 22.0, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 22.0)
        assertEquals(0.25, p.dtS, 1e-9)
        assertTrue("saut apres pause <= 0.125 m, obtenu ${22.0 - p.altitudeM}", 22.0 - p.altitudeM <= 0.1251)
    }

    @Test fun dt_trop_court_borne_au_min() {
        val d = SoccerDirector(dtMinS = 0.02)
        d.realiser(ns(100), 20.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)  // init a 100 ms
        // +1 ms -> dt brut 0.001 s < min -> borne a 0.02.
        val p = d.realiser(ns(101), 20.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        assertEquals(0.02, p.dtS, 1e-9)
    }

    // --- Bornes phase / plafond ---

    @Test fun ne_depasse_jamais_la_plage_de_phase() {
        val d = SoccerDirector(vitesseMaxMps = 0.5)
        var t = 0L; var alt = 18.0
        d.realiser(ns(t), alt, 0.30f, 6, 0.32f, 0.5f, 1f, 15.0, 18.0)  // veut monter mais borne 18
        repeat(100) {
            t += 50
            alt = d.realiser(ns(t), alt, 0.30f, 6, 0.32f, 0.5f, 1f, 15.0, 18.0).altitudeM
        }
        assertTrue("alt bornee a la plage DUEL 15-18, alt=$alt", alt in 15.0..18.0)
    }

    @Test fun plafond_absolu_35m_respecte() {
        val d = SoccerDirector(vitesseMaxMps = 0.5, plafondAbsoluM = 35.0)
        var t = 0L; var alt = 30.0
        // plage aberrante 25-100 -> ecretee a 35.
        d.realiser(ns(t), alt, 0.40f, 6, 0.32f, 0.5f, 1f, 25.0, 100.0)
        repeat(200) {
            t += 50
            alt = d.realiser(ns(t), alt, 0.40f, 6, 0.32f, 0.5f, 1f, 25.0, 100.0).altitudeM
        }
        assertTrue("alt <= 35, alt=$alt", alt in 25.0..35.0)
    }

    // --- NaN / fail-safe ---

    @Test fun altitude_nan_ne_casse_pas() {
        val d = SoccerDirector()
        val p = d.realiser(ns(0), Double.NaN, 0.12f, 6, 0.32f, 0.5f, 1f, 18.0, 22.0)
        assertTrue(!p.altitudeM.isNaN() && p.altitudeM in 18.0..22.0)
    }

    @Test fun taille_nan_ne_casse_pas() {
        val d = SoccerDirector()
        d.realiser(ns(0), 20.0, Float.NaN, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        val p = d.realiser(ns(50), 20.0, Float.NaN, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        assertTrue(!p.altitudeM.isNaN() && p.altitudeM in 15.0..35.0)
        assertTrue(!p.scoreActuel.isNaN())
    }

    @Test fun reset_efface_l_etat() {
        val d = SoccerDirector()
        d.realiser(ns(0), 22.0, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 22.0)
        d.realiser(ns(50), 22.0, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 22.0)
        d.reset()
        // apres reset, 1ere frame -> repli dt de nouveau.
        val p = d.realiser(ns(9999), 22.0, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 22.0)
        assertEquals(0.05, p.dtS, 1e-9)
    }

    // --- 5e critere : CONFIANCE predite par candidat ---

    @Test fun confiance_predite_croit_quand_joueurs_grossissent() {
        val d = SoccerDirector()
        // taille predite > observee (descendre) -> ratio > 1 -> confiance predite >= observee.
        val plusGros = d.confiancePredite(0.6, tailleObservee = 0.10, taillePredite = 0.12)
        // taille predite < observee (monter) -> ratio < 1 -> confiance predite <= observee.
        val plusPetit = d.confiancePredite(0.6, tailleObservee = 0.10, taillePredite = 0.08)
        assertTrue("descendre ($plusGros) doit valoir >= monter ($plusPetit)", plusGros >= plusPetit)
        assertTrue(plusGros >= 0.6 && plusPetit <= 0.6)
    }

    @Test fun confiance_predite_bornee_et_nan_safe() {
        val d = SoccerDirector()
        assertEquals(0.0, d.confiancePredite(Double.NaN, 0.1, 0.1), 1e-9)
        assertEquals(0.0, d.confiancePredite(0.5, 0.0, 0.1), 1e-9)   // taille obs nulle
        // ratio ecrete a 1.15 : conf 0.9 * 1.15 = 1.035 -> borne a 1.0
        assertEquals(1.0, d.confiancePredite(0.9, 0.10, 0.50), 1e-9)
    }

    @Test fun confiance_influence_le_choix_pas_seulement_l_affichage() {
        // Joueurs LEGEREMENT trop petits : sans confiance le director voudrait descendre.
        // Avec une confiance qui S'EFFONDRE en descendant, descendre peut ne plus payer.
        // On verifie au minimum que la confiance modifie le score courant rapporte.
        val d = SoccerDirector(vitesseMaxMps = 0.5)
        d.realiser(ns(0), 20.0, 0.11f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0, confianceObservee = 1f)
        val fiable = d.realiser(ns(50), 20.0, 0.11f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0, confianceObservee = 1f)

        val d2 = SoccerDirector(vitesseMaxMps = 0.5)
        d2.realiser(ns(0), 20.0, 0.11f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0, confianceObservee = 0.3f)
        val douteux = d2.realiser(ns(50), 20.0, 0.11f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0, confianceObservee = 0.3f)

        assertTrue("score fiable (${fiable.scoreActuel}) > score douteux (${douteux.scoreActuel})",
            fiable.scoreActuel > douteux.scoreActuel)
    }

    @Test fun zero_detection_tient_l_altitude_pas_de_descente_agressive() {
        val d = SoccerDirector(vitesseMaxMps = 0.5, seuilConfianceDecision = 0.1f)
        var t = 0L; var alt = 22.0
        d.realiser(ns(t), alt, 0.06f, 0, 0.32f, 0.5f, 1f, 15.0, 22.0, confianceObservee = 0f)
        // 50 frames sans detection (conf 0) : l'altitude ne doit PAS plonger.
        repeat(50) {
            t += 50
            val p = d.realiser(ns(t), alt, 0.06f, 0, 0.32f, 0.5f, 1f, 15.0, 22.0, confianceObservee = 0f)
            alt = p.altitudeM
            assertEquals("doit tenir (sens=0) sans donnees fiables", 0, p.sens)
        }
        assertEquals("altitude quasi inchangee", 22.0, alt, 0.001)
    }

    @Test fun confiance_lissee_rapportee_dans_le_plan() {
        val d = SoccerDirector(lissageConf = 0.5f)
        d.realiser(ns(0), 20.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0, confianceObservee = 1f)
        val p = d.realiser(ns(50), 20.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0, confianceObservee = 0f)
        // 1ere: lissee=1 ; 2e: 1 + (0-1)*0.5 = 0.5.
        assertEquals(0.5f, p.confianceLissee, 0.001f)
    }

    // ==================== GRILLE 2D : altitude x lateral (realiser2D) ====================

    @Test fun lateral_corrige_une_action_mal_placee() {
        // Jeu vers la droite -> lead room ideal = action a GAUCHE (~0.32).
        // Action trop a DROITE (0.60) -> il faut la ramener a gauche -> drone vers la DROITE (+lat).
        val d = SoccerDirector()
        d.realiser2D(ns(0), 20.0, 0.0, 0.12f, 6, 0.60f, 0.5f, 1f, 15.0, 35.0)
        val p = d.realiser2D(ns(50), 20.0, 0.0, 0.12f, 6, 0.60f, 0.5f, 1f, 15.0, 35.0)
        assertEquals(1, p.sensLateral)
        assertTrue("le drone doit se decaler a droite (>0)", p.lateralM > 0.0)
    }

    @Test fun lateral_converge_vers_le_bon_cadrage() {
        val d = SoccerDirector()
        var t = 0L; var lat = 0.0
        d.realiser2D(ns(t), 20.0, lat, 0.12f, 6, 0.60f, 0.5f, 1f, 15.0, 35.0)
        repeat(100) {
            t += 50
            lat = d.realiser2D(ns(t), 20.0, lat, 0.12f, 6, 0.60f, 0.5f, 1f, 15.0, 35.0).lateralM
        }
        assertTrue("apres convergence, decalage lateral franc a droite, lat=$lat", lat > 1.0)
    }

    @Test fun cadrage_deja_bon_ne_bouge_pas_le_lateral() {
        // Action deja a la bonne place (0.32) : aucun mouvement lateral.
        val d = SoccerDirector()
        d.realiser2D(ns(0), 20.0, 0.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        val p = d.realiser2D(ns(50), 20.0, 0.0, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        assertEquals(0, p.sensLateral)
        assertEquals(0.0, p.lateralM, 1e-9)
    }

    @Test fun grille_optimise_les_deux_axes_ensemble() {
        // Joueurs trop petits (descendre) ET action mal placee (corriger lat) simultanement.
        val d = SoccerDirector()
        d.realiser2D(ns(0), 22.0, 0.0, 0.06f, 6, 0.60f, 0.5f, 1f, 15.0, 22.0)
        val p = d.realiser2D(ns(50), 22.0, 0.0, 0.06f, 6, 0.60f, 0.5f, 1f, 15.0, 22.0)
        assertEquals("doit descendre", -1, p.sens)
        assertEquals("doit corriger le lateral a droite", 1, p.sensLateral)
    }

    @Test fun lateral_borne_par_l_amplitude_de_securite() {
        val d = SoccerDirector()
        var t = 0L; var lat = 0.0
        d.realiser2D(ns(t), 20.0, lat, 0.12f, 6, 0.95f, 0.5f, 1f, 15.0, 35.0, amplitudeLateraleM = 3.0)
        repeat(300) {
            t += 50
            lat = d.realiser2D(ns(t), 20.0, lat, 0.12f, 6, 0.95f, 0.5f, 1f, 15.0, 35.0,
                amplitudeLateraleM = 3.0).lateralM
        }
        assertTrue("lateral borne a +/-3 m, lat=$lat", lat in -3.0..3.0)
    }

    @Test fun rate_limiter_lateral_borne_par_vitesse_fois_dt() {
        val d = SoccerDirector(vitesseLateraleMaxMps = 0.5)
        d.realiser2D(ns(0), 20.0, 0.0, 0.12f, 6, 0.60f, 0.5f, 1f, 15.0, 35.0)  // init (no move)
        val p = d.realiser2D(ns(50), 20.0, 0.0, 0.12f, 6, 0.60f, 0.5f, 1f, 15.0, 35.0)
        // dt=0.05 -> deplacement lateral <= 0.5*0.05 = 0.025 m.
        assertTrue("pas lateral <= 0.025 m, obtenu ${p.lateralM}", p.lateralM <= 0.0251)
    }

    @Test fun premiere_frame_2d_ne_bouge_pas() {
        val d = SoccerDirector()
        val p = d.realiser2D(ns(0), 22.0, 0.0, 0.06f, 6, 0.60f, 0.5f, 1f, 15.0, 22.0)
        assertEquals(22.0, p.altitudeM, 1e-9)
        assertEquals(0.0, p.lateralM, 1e-9)
    }

    @Test fun zero_detection_2d_tient_les_deux_axes() {
        val d = SoccerDirector(seuilConfianceDecision = 0.1f)
        var t = 0L; var alt = 22.0; var lat = 0.0
        d.realiser2D(ns(t), alt, lat, 0.06f, 0, 0.60f, 0.5f, 1f, 15.0, 22.0, confianceObservee = 0f)
        repeat(30) {
            t += 50
            val p = d.realiser2D(ns(t), alt, lat, 0.06f, 0, 0.60f, 0.5f, 1f, 15.0, 22.0, confianceObservee = 0f)
            alt = p.altitudeM; lat = p.lateralM
            assertEquals(0, p.sens); assertEquals(0, p.sensLateral)
        }
        assertEquals(22.0, alt, 0.001); assertEquals(0.0, lat, 0.001)
    }

    @Test fun realiser2d_nan_safe() {
        val d = SoccerDirector()
        val p = d.realiser2D(ns(0), Double.NaN, Double.NaN, Float.NaN, 6,
            Float.NaN, Float.NaN, Float.NaN, 15.0, 35.0)
        assertTrue(!p.altitudeM.isNaN() && p.altitudeM in 15.0..35.0)
        assertTrue(!p.lateralM.isNaN())
        assertTrue(!p.scoreActuel.isNaN())
    }

    // ==================== AXE ZOOM OPTIQUE (hierarchique, conditionnel) ====================

    @Test fun sans_zoom_optique_l_axe_reste_inactif() {
        // Mini 4 Pro : DIGITAL_ONLY -> aucun pilotage zoom, reste a 1.0.
        val d = SoccerDirector(capaciteZoom = CapaciteZoom.ZOOM_DIGITAL_ONLY)
        var t = 0L; var alt = 15.0; var lat = 0.0
        d.realiser2D(ns(t), alt, lat, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 15.0)
        repeat(50) {
            t += 50
            val p = d.realiser2D(ns(t), alt, lat, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 15.0)
            alt = p.altitudeM; lat = p.lateralM
            assertFalse("zoom ne doit jamais etre actif", p.zoomActif)
            assertEquals(1f, p.zoom, 1e-6f)
        }
    }

    @Test fun switch_tele_ne_pilote_pas_le_zoom() {
        // camera tele = changement de camera, PAS un zoom continu.
        val d = SoccerDirector(capaciteZoom = CapaciteZoom.CAMERA_SWITCH_TELE)
        var t = 0L; var alt = 15.0
        d.realiser2D(ns(t), alt, 0.0, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 15.0)
        repeat(30) {
            t += 50
            val p = d.realiser2D(ns(t), alt, 0.0, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 15.0)
            alt = p.altitudeM
            assertFalse(p.zoomActif); assertEquals(1f, p.zoom, 1e-6f)
        }
    }

    @Test fun zoom_optique_aide_quand_altitude_bornee_et_joueurs_petits() {
        // Plage 15-15 (altitude bloquee) + joueurs trop petits -> le zoom optique doit aider.
        val d = SoccerDirector(capaciteZoom = CapaciteZoom.ZOOM_OPTICAL_CONTINUOUS)
        var t = 0L; var alt = 15.0; var lat = 0.0
        d.realiser2D(ns(t), alt, lat, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 15.0)
        var z = 1f
        repeat(200) {
            t += 50
            val p = d.realiser2D(ns(t), alt, lat, 0.06f, 6, 0.32f, 0.5f, 1f, 15.0, 15.0)
            alt = p.altitudeM; lat = p.lateralM; z = p.zoom
            assertTrue(p.zoomActif)
        }
        assertTrue("zoom doit avoir augmente (>1.1), z=$z", z > 1.1f)
    }

    @Test fun zoom_optique_reste_neutre_si_taille_deja_bonne() {
        val d = SoccerDirector(capaciteZoom = CapaciteZoom.ZOOM_OPTICAL_CONTINUOUS)
        var t = 0L; var alt = 20.0; var lat = 0.0
        d.realiser2D(ns(t), alt, lat, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
        var z = 1f
        repeat(100) {
            t += 50
            val p = d.realiser2D(ns(t), alt, lat, 0.12f, 6, 0.32f, 0.5f, 1f, 15.0, 35.0)
            alt = p.altitudeM; lat = p.lateralM; z = p.zoom
        }
        assertTrue("zoom reste proche de 1.0, z=$z", z in 0.99f..1.05f)
    }

    @Test fun zoom_borne_au_max() {
        val d = SoccerDirector(capaciteZoom = CapaciteZoom.ZOOM_OPTICAL_CONTINUOUS,
            zoomMax = 1.5f)
        var t = 0L; var alt = 15.0; var lat = 0.0; var z = 1f
        d.realiser2D(ns(t), alt, lat, 0.03f, 6, 0.32f, 0.5f, 1f, 15.0, 15.0)
        repeat(400) {
            t += 50
            val p = d.realiser2D(ns(t), alt, lat, 0.03f, 6, 0.32f, 0.5f, 1f, 15.0, 15.0)
            alt = p.altitudeM; lat = p.lateralM; z = p.zoom
        }
        assertTrue("zoom borne a 1.5, z=$z", z in 1.0f..1.5f)
    }

    @Test fun zoom_gele_si_zero_detection() {
        val d = SoccerDirector(capaciteZoom = CapaciteZoom.ZOOM_OPTICAL_CONTINUOUS,
            seuilConfianceDecision = 0.1f)
        var t = 0L; var z = 1f
        d.realiser2D(ns(t), 15.0, 0.0, 0.06f, 0, 0.32f, 0.5f, 1f, 15.0, 15.0, confianceObservee = 0f)
        repeat(30) {
            t += 50
            val p = d.realiser2D(ns(t), 15.0, 0.0, 0.06f, 0, 0.32f, 0.5f, 1f, 15.0, 15.0, confianceObservee = 0f)
            z = p.zoom
        }
        assertEquals("zoom fige sans detection", 1f, z, 1e-6f)
    }
}
