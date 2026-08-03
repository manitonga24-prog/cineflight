package ca.cineflight.stage.sport.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Sc = EssaiE03Log.Scenario

/**
 * EssaiE03LogTest — couvre la journalisation/mesure de l'essai E-03 (pur, JVM).
 * Vérifie le calcul des délais T0..T6, la PERSISTANCE (critère central), le verdict
 * PASS/FAIL fail-closed, et le format de ligne exportable.
 */
class EssaiE03LogTest {

    private val MS = 1_000_000L
    private val L = EssaiE03Log(persistanceMaxMs = 500L)

    private fun m(t0: Long = 0, t1: Long = -1, t2: Long = -1, t3: Long = -1, t4: Long = -1,
                  t5: Long = -1, t6: Long = -1, v: Float = 0f, fin: Long = -1) =
        EssaiE03Log.Mesures(t0 * MS, t1 * MS, t2 * MS, t3 * MS, t4 * MS, t5 * MS, t6 * MS, v,
            if (fin < 0) -1 else fin * MS)

    // ── delta / persistance ──

    @Test fun delta_ms_correct() {
        assertEquals(150L, L.deltaMs(0L, 150L * MS))
        assertNull(L.deltaMs(-1L, 100L))
    }

    @Test fun persistance_zero_si_vitesse_nulle() {
        assertEquals(0L, L.persistanceMs(m(v = 0f)))
    }

    @Test fun persistance_mesuree_entre_T0_et_fin_vitesse() {
        // vitesse non nulle jusqu'à 300 ms après T0 -> persistance 300 ms
        assertEquals(300L, L.persistanceMs(m(t0 = 0, v = 0.5f, fin = 300)))
    }

    @Test fun persistance_null_si_non_mesuree() {
        assertNull(L.persistanceMs(m(t0 = 0, v = 0.5f, fin = -1)))
    }

    // ── acceptation (fail-closed) ──

    @Test fun persistance_sous_seuil_acceptee() {
        assertTrue(L.persistanceAcceptee(m(v = 0.5f, fin = 400)))   // 400 ≤ 500
    }

    @Test fun persistance_au_dela_du_seuil_refusee() {
        assertFalse(L.persistanceAcceptee(m(v = 0.5f, fin = 800)))  // 800 > 500
    }

    @Test fun persistance_non_mesuree_refusee_fail_closed() {
        assertFalse(L.persistanceAcceptee(m(v = 0.5f, fin = -1)))
    }

    // ── verdict scénario standard ──

    @Test fun verdict_pass_si_toute_la_chaine_et_persistance_ok() {
        val ok = m(t0 = 0, t1 = 120, t2 = 130, t3 = 135, t4 = 140, t5 = 150, t6 = 200, v = 0.5f, fin = 250)
        assertEquals("PASS", L.verdict(Sc.E03_10_DERNIERE_CMD_POSITIVE, ok))
    }

    @Test fun verdict_fail_si_persistance_trop_longue() {
        val trop = m(t0 = 0, t1 = 120, t2 = 130, t3 = 135, t4 = 140, v = 0.5f, fin = 900)
        assertEquals("FAIL", L.verdict(Sc.E03_10_DERNIERE_CMD_POSITIVE, trop))
    }

    /**
     * Une commande NON NULLE doit avoir été émise, sinon la répétition n'éprouve rien et le
     * verdict est NUL (voir mesureExploitable). Ici elle l'est : le FAIL porte bien sur le
     * jalon T4 manquant, pas sur une mesure vide.
     */
    @Test fun verdict_fail_si_sortie_vs_manquante() {
        val sansT4 = m(t0 = 0, t1 = 120, t2 = 130, t3 = 135, v = 0.5f, fin = 200)
        assertEquals("FAIL", L.verdict(Sc.E03_03_GEL_THREAD, sansT4))
    }

    // ── E03-02 « zéro maintenu » : critère PROPRE au scénario ──

    /**
     * Ce scénario ne désarme pas et ne sort pas du Virtual Stick : T2 et T4 n'y existent
     * pas. Le critère général les exigeait, rendant FAIL inconditionnel — 5 répétitions
     * réelles, 5 FAIL, avec T2/T4 absents à chaque fois (relevé 2026-07-22). Un critère
     * qu'aucun comportement ne peut satisfaire ne teste rien.
     */
    @Test fun verdict_e03_02_ne_reclame_ni_desarmement_ni_sortie_vs() {
        // Comme au banc : une commande de 0,2 m/s était en cours, puis les zéros sont
        // imposés et rien ne persiste (fin = t0 → persistance 0 ms).
        val zeroMaintenu = m(t0 = 0, t1 = 15, t3 = 17, v = 0.2f, fin = 0)   // ni T2 ni T4
        assertEquals("PASS", L.verdict(Sc.E03_02_ZERO_MAINTENU, zeroMaintenu))
        // Le MÊME jeu de mesures reste FAIL pour un scénario de cessation : la dérogation
        // est bornée à E03-02, elle n'affaiblit pas le critère général.
        assertEquals("FAIL", L.verdict(Sc.E03_01_ARRET_NORMAL, zeroMaintenu))
    }

    /** Le critère CENTRAL — la persistance — reste jugé à l'identique pour E03-02. */
    @Test fun verdict_e03_02_echoue_si_la_commande_persiste() {
        val persiste = m(t0 = 0, t1 = 15, t3 = 17, v = 0.2f, fin = 2998)
        assertEquals("FAIL", L.verdict(Sc.E03_02_ZERO_MAINTENU, persiste))
    }

    /** Sans stimulus (T1) ou sans commande neutre (T3), E03-02 échoue toujours. */
    @Test fun verdict_e03_02_exige_t1_et_t3() {
        assertEquals("FAIL", L.verdict(Sc.E03_02_ZERO_MAINTENU, m(t0 = 0, t3 = 17, v = 0.2f, fin = 0)))
        assertEquals("FAIL", L.verdict(Sc.E03_02_ZERO_MAINTENU, m(t0 = 0, t1 = 15, v = 0.2f, fin = 0)))
    }

    /**
     * AUCUNE COMMANDE NON NULLE ÉMISE = rien à faire cesser = rien d'éprouvé.
     * Relevé du 2026-07-22, E03-11 : `verdict=FAIL` écrit à côté de
     * `MESURE_SANS_OBJET aucune_commande_non_nulle_emise`. Le verdict doit dire NUL.
     */
    @Test fun verdict_nul_si_aucune_commande_non_nulle_n_a_ete_emise() {
        val rienEmis = m(t0 = 0, t1 = 13, t4 = 43, t5 = 59, v = 0f)
        assertEquals("NUL", L.verdict(Sc.E03_11_SORTIE_VS_EXPLICITE, rienEmis))
        assertFalse(L.mesureExploitable(rienEmis))
        // Y compris pour E03-02 : « zéro maintenu » suppose qu'une vitesse existait avant.
        assertEquals("NUL", L.verdict(Sc.E03_02_ZERO_MAINTENU, rienEmis))
    }

    // ── RÉPÉTITION SANS T0 : NI PASS, NI FAIL ──
    //
    // Relevé du 2026-07-22 : 20 répétitions où aucune commande n'avait pu être émise
    // portaient `verdict=PASS` À CÔTÉ de `MESURE_INVALIDE t0_absent`. Toutes les cases du
    // critère étaient cochées par une absence de données.

    @Test fun verdict_nul_si_t0_absent_meme_avec_tous_les_jalons() {
        // Le cas EXACT du relevé : stimulus marqué, aucune commande émise avant.
        val sansT0 = EssaiE03Log.Mesures(
            t0Nanos = -1, t1Nanos = 120 * MS, t2Nanos = 130 * MS,
            t3Nanos = 135 * MS, t4Nanos = 140 * MS, t5Nanos = 150 * MS,
            derniereVitesse = 0f, finVitesseNonNulleNanos = -1,
        )
        assertEquals("NUL", L.verdict(Sc.E03_01_ARRET_NORMAL, sansT0))
        assertFalse("une repetition sans T0 n'est pas exploitable", L.mesureExploitable(sansT0))
    }

    /** La dérogation E03-02 ne doit pas rouvrir la porte : sans T0, c'est NUL aussi. */
    @Test fun verdict_nul_sans_t0_y_compris_pour_e03_02_et_le_volet_fs() {
        val sansT0 = EssaiE03Log.Mesures(t0Nanos = -1, t1Nanos = 15 * MS, t3Nanos = 17 * MS,
            t6Nanos = 300 * MS, derniereVitesse = 0f)
        assertEquals("NUL", L.verdict(Sc.E03_02_ZERO_MAINTENU, sansT0))
        assertEquals("NUL", L.verdict(Sc.E03_FS1_PERTE_RC, sansT0))
    }

    /**
     * La persistance ne doit PAS valoir 0 quand T0 manque : ce serait convertir une absence
     * de donnée en meilleur résultat possible, sur le critère central de l'essai.
     */
    @Test fun persistance_non_mesurable_sans_t0_meme_si_la_vitesse_est_nulle() {
        val sansT0 = EssaiE03Log.Mesures(t0Nanos = -1, derniereVitesse = 0f)
        assertNull(L.persistanceMs(sansT0))
        assertFalse("fail-closed : non mesuree = non acceptee", L.persistanceAcceptee(sansT0))
    }

    /** Une répétition NULLE doit être lisible comme telle dans la ligne exportée. */
    @Test fun ligne_d_une_repetition_sans_t0_porte_verdict_nul_et_persistance_absente() {
        val sansT0 = EssaiE03Log.Mesures(t0Nanos = -1, t1Nanos = 120 * MS, derniereVitesse = 0f)
        val l = L.ligne(Sc.E03_01_ARRET_NORMAL, 1, sansT0, "cfg")
        assertTrue(l.contains("verdict=NUL"))
        assertTrue(l.contains("persist_ms=-"))
    }

    // ── E03-11 « sortie Virtual Stick explicite » : critère PROPRE au scénario ──
    //
    // Ce scénario demande au SDK de quitter le Virtual Stick, rien d'autre. Il ne désarme
    // pas le mode (T2) et n'envoie pas de commande neutre (T3) : ces jalons appartiennent
    // à la chaîne d'arrêt d'urgence, qu'il n'emprunte pas. Relevé du 2026-07-22 :
    // 5 répétitions, 5 FAIL, T2 et T3 absents à chaque fois, alors que la sortie était
    // demandée en 8..91 ms et qu'aucune commande ne persistait.

    @Test fun verdict_e03_11_ne_reclame_ni_desarmement_ni_commande_neutre() {
        // Comme au banc : T1 (stimulus), T4 (sortie VS demandée), T5 (acquittement),
        // une commande de 0,2 m/s en cours, et rien qui persiste.
        val sortieVs = m(t0 = 0, t1 = 33, t4 = 38, t5 = 88, v = 0.2f, fin = 0)
        assertEquals("PASS", L.verdict(Sc.E03_11_SORTIE_VS_EXPLICITE, sortieVs))
        // Le MÊME jeu reste FAIL pour un scénario de cessation : la dérogation est bornée.
        assertEquals("FAIL", L.verdict(Sc.E03_01_ARRET_NORMAL, sortieVs))
    }

    /** Le critère CENTRAL — la persistance — reste jugé à l'identique pour E03-11. */
    @Test fun verdict_e03_11_echoue_si_la_commande_persiste() {
        val persiste = m(t0 = 0, t1 = 33, t4 = 38, v = 0.2f, fin = 900)
        assertEquals("FAIL", L.verdict(Sc.E03_11_SORTIE_VS_EXPLICITE, persiste))
    }

    /** Sans stimulus (T1) ou sans sortie VS demandée (T4), E03-11 échoue. */
    @Test fun verdict_e03_11_exige_t1_et_t4() {
        assertEquals("FAIL", L.verdict(Sc.E03_11_SORTIE_VS_EXPLICITE, m(t0 = 0, t4 = 38, v = 0.2f, fin = 0)))
        assertEquals("FAIL", L.verdict(Sc.E03_11_SORTIE_VS_EXPLICITE, m(t0 = 0, t1 = 33, v = 0.2f, fin = 0)))
    }

    @Test fun verdict_fs_exige_detection_et_effet() {
        val fsOk = m(t0 = 0, t1 = 100, t6 = 300, v = 0.5f, fin = 200)
        assertEquals("PASS", L.verdict(Sc.E03_FS1_PERTE_RC, fsOk))
        val fsSansEffet = m(t0 = 0, t1 = 100, v = 0.5f, fin = 200)
        assertEquals("FAIL", L.verdict(Sc.E03_FS1_PERTE_RC, fsSansEffet))
    }

    // ── ligne exportable ──

    @Test fun ligne_contient_les_champs_cles() {
        val mm = m(t0 = 0, t1 = 120, t2 = 130, t3 = 135, t4 = 140, t5 = 150, t6 = 200, v = 0.5f, fin = 250)
        val s = L.ligne(Sc.E03_10_DERNIERE_CMD_POSITIVE, repetition = 2, mm, configId = "0ab788385686e554")
        assertTrue(s.contains("scenario=E03_10_DERNIERE_CMD_POSITIVE"))
        assertTrue(s.contains("rep=2"))
        assertTrue(s.contains("config_id=0ab788385686e554"))
        assertTrue(s.contains("T4_ms=140"))
        assertTrue(s.contains("persist_ms=250"))
        assertTrue(s.contains("seuil_ms=500"))
        assertTrue(s.contains("verdict=PASS"))
    }

    /**
     * Un jalon ANTÉRIEUR à T0 s'affiche « - », jamais en négatif.
     * Relevé du 2026-07-22 (E03-06) : `T5_ms=-12303`.
     */
    @Test fun ligne_n_affiche_jamais_un_delai_negatif() {
        val t5AvantT0 = EssaiE03Log.Mesures(
            t0Nanos = 1000L * MS, t5Nanos = 100L * MS, derniereVitesse = 0.2f,
            finVitesseNonNulleNanos = 1000L * MS,
        )
        val l = L.ligne(Sc.E03_06_ARRIERE_PLAN, 1, t5AvantT0, "cfg")
        assertTrue("aucun signe moins devant un nombre", l.contains("T5_ms=-"))
        assertFalse("pas de valeur negative", l.contains("T5_ms=-9"))
        assertFalse(l.contains("T5_ms=-1"))
    }

    @Test fun ligne_gere_les_horodatages_manquants() {
        val s = L.ligne(Sc.E03_01_ARRET_NORMAL, 1, m(t0 = 0, v = 0f), "cfg")
        assertTrue("T non mesurés notés '-'", s.contains("T1_ms=-"))
        assertTrue(s.contains("persist_ms=0"))
    }

    @Test fun les_16_scenarios_sont_definis() {
        assertEquals(16, EssaiE03Log.Scenario.values().size)
    }
}
