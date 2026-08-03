package ca.cineflight.stage.sport.soccer

/**
 * EssaiE03Log — JOURNALISATION STRUCTURÉE de l'essai E-03 (caractérisation de la cessation
 * Virtual Stick au banc). Pur (aucun SDK/Android) : construit les lignes de journal et
 * calcule la PERSISTANCE de la dernière vitesse à partir d'horodatages monotones (nanos).
 *
 * Le protocole (fiche E-03 du dossier v57) demande, PAR SCÉNARIO, les horodatages :
 *   T0 dernier heartbeat / dernière commande émise
 *   T1 détection (timeout watchdog / événement)
 *   T2 désarmement applicatif
 *   T3 commande neutre demandée
 *   T4 sortie Virtual Stick demandée
 *   T5 acquittement / télémétrie SDK
 *   T6 effet observé (contrôleur / aéronef)
 * + PERSISTANCE = durée pendant laquelle la dernière vitesse non nulle a été observée
 *   avant retour à zéro (critère central : ne doit pas dépasser un délai mesuré et accepté).
 *
 * Cette classe ne DÉCIDE rien et n'émet aucune commande : elle formate et mesure. Les
 * déclencheurs de scénario et l'émission restent dans l'activité (drapeau TEST_E03).
 */
class EssaiE03Log(
    /**
     * Seuil d'acceptation de la persistance (ms). Au-delà → verdict FAIL sur ce critère.
     * Valeur par défaut prise dans [ca.cineflight.stage.control.SafetyLimits] : le seuil du
     * §378 doit avoir UNE seule définition. Le dupliquer ici en dur laisserait les deux
     * dériver, et l'essai jugerait alors sur un autre seuil que celui du dossier.
     */
    private val persistanceMaxMs: Long = ca.cineflight.stage.control.SafetyLimits.PERSISTANCE_MAX_MS,
) {

    /** Les 13 scénarios de la fiche E-03 (+ volet FS). */
    enum class Scenario {
        E03_01_ARRET_NORMAL, E03_02_ZERO_MAINTENU, E03_03_GEL_THREAD,
        E03_04_EXCEPTION, E03_05_CRASH_PROCESS, E03_06_ARRIERE_PLAN,
        E03_07_DECONNEXION_USB, E03_08_PERTE_MSDK, E03_09_PERTE_RC,
        E03_10_DERNIERE_CMD_POSITIVE, E03_11_SORTIE_VS_EXPLICITE,
        E03_12_BATTERIE_STATION, E03_13_SURCHARGE_THERMIQUE,
        E03_FS1_PERTE_RC, E03_FS2_PERTE_STATION, E03_FS3_CMD_PERSISTANTE,
    }

    /** Un point d'horodatage T0..T6 (nanos monotones), plus un libellé. */
    data class Mesures(
        val t0Nanos: Long = -1, val t1Nanos: Long = -1, val t2Nanos: Long = -1,
        val t3Nanos: Long = -1, val t4Nanos: Long = -1, val t5Nanos: Long = -1,
        val t6Nanos: Long = -1,
        /** Dernière vitesse verticale non nulle émise avant la cessation (m/s). */
        val derniereVitesse: Float = 0f,
        /** Nanos du dernier échantillon où une vitesse non nulle a été observée. */
        val finVitesseNonNulleNanos: Long = -1,
    )

    /** Délai en ms entre deux horodatages nanos ; null si l'un des deux manque. */
    fun deltaMs(deNanos: Long, aNanos: Long): Long? {
        if (deNanos < 0 || aNanos < 0) return null
        return (aNanos - deNanos) / 1_000_000L
    }

    /**
     * PERSISTANCE (ms) = du dernier heartbeat/commande (T0) au dernier instant où une
     * vitesse non nulle a encore été observée. C'est le CRITÈRE CENTRAL de E-03.
     * null si non mesurable.
     */
    fun persistanceMs(m: Mesures): Long? {
        // SANS T0, AUCUNE DURÉE N'EST CALCULABLE. Ce garde-fou vient d'un relevé réel
        // (2026-07-22) : sur des répétitions où aucune commande n'avait été émise — mode 2D
        // inactif, aucun joueur détecté — `derniereVitesse` valait 0 et la méthode rendait
        // `0 ms`, c'est-à-dire « persistance parfaite ». Une ABSENCE DE DONNÉE était
        // convertie en meilleur résultat possible. Un zéro qui vient de rien ne vaut pas un
        // zéro mesuré.
        if (m.t0Nanos < 0) return null
        if (m.derniereVitesse == 0f) return 0L
        val fin = m.finVitesseNonNulleNanos
        if (fin < 0) return null
        // LA COMMANDE AVAIT DÉJÀ CESSÉ AVANT L'ÉVÉNEMENT — rien n'a persisté, rien n'a été
        // éprouvé. Relevé du 2026-07-22 (E03-07) : `persist_ms=-2413`. L'opérateur était
        // sorti du champ de la caméra pour aller débrancher, la commande était donc retombée
        // à zéro 2,4 s AVANT la perte de liaison, tandis que T0 — qui suit le heartbeat —
        // continuait d'avancer jusqu'à la détection. Une durée négative n'a aucun sens
        // physique et n'a rien à faire dans un dossier de sécurité : on rend « non mesurable ».
        if (fin < m.t0Nanos) return null
        return deltaMs(m.t0Nanos, fin)
    }

    /**
     * La répétition a-t-elle mesuré QUELQUE CHOSE ?
     *
     * T0 est l'origine de tous les délais T1..T6 et de la persistance. Sans lui, aucun
     * jalon n'est exprimable et le critère central n'est pas évalué : la répétition n'a pas
     * échoué, elle n'a pas eu lieu.
     */
    fun mesureExploitable(m: Mesures): Boolean {
        // (a) Sans T0, aucun délai n'est calculable.
        if (m.t0Nanos < 0) return false
        // (b) AUCUNE COMMANDE NON NULLE N'A JAMAIS ÉTÉ ÉMISE. Il n'y avait rien à faire
        //     cesser : le critère central n'a pas été exercé. Le capteur le signale déjà
        //     (`MESURE_SANS_OBJET`) — le verdict doit dire la même chose au lieu de
        //     prononcer un FAIL. Relevé du 2026-07-22, E03-11 : `verdict=FAIL` à côté de
        //     `MESURE_SANS_OBJET aucune_commande_non_nulle_emise`, deux lignes qui se
        //     contredisent sur la même répétition.
        if (m.derniereVitesse == 0f) return false
        // (c) La dernière commande non nulle est ANTÉRIEURE à l'événement : elle avait déjà
        //     cessé, pour une raison étrangère à la cessation qu'on mesure.
        if (m.finVitesseNonNulleNanos < 0) return false
        if (m.finVitesseNonNulleNanos < m.t0Nanos) return false
        return true
    }

    /** true si la persistance mesurée respecte le seuil d'acceptation. */
    fun persistanceAcceptee(m: Mesures): Boolean {
        val p = persistanceMs(m) ?: return false   // non mesuré = non accepté (fail-closed)
        return p in 0..persistanceMaxMs
    }

    /**
     * Verdict de scénario. Le critère DÉPEND de ce que le scénario exerce réellement.
     *
     * CAS GÉNÉRAL (cessation) — T1 détection, T2 désarmement, T3 commande neutre,
     * T4 sortie Virtual Stick, + persistance acceptée.
     *
     * VOLET FS (failsafe) — T1 et l'effet observé T6, + persistance acceptée.
     *
     * E03-02 « ZÉRO MAINTENU » — CAS À PART, et il faut être explicite sur la raison.
     * Ce scénario ne désarme rien et ne sort PAS du Virtual Stick : il maintient
     * délibérément une commande nulle et vérifie qu'aucune vitesse ne persiste. T2 et T4
     * n'y ont donc pas d'existence. Leur exiger revenait à imposer des événements que le
     * protocole interdit de produire — le verdict rendait FAIL quoi que fasse l'aéronef,
     * ce qui ne testait plus rien (constaté 2026-07-22 : 5 répétitions, 5 FAIL, avec
     * T2 et T4 systématiquement absents).
     * Critère retenu : T1 (stimulus) + T3 (commande neutre effectivement demandée)
     * + persistance acceptée. Le critère CENTRAL de E-03 — la persistance — reste jugé
     * à l'identique ; seuls les jalons inapplicables sont retirés.
     */
    fun verdict(s: Scenario, m: Mesures): String {
        // ── RÉPÉTITION SANS OBJET : NI PASS, NI FAIL ─────────────────────────────────
        //
        // DÉFAUT CORRIGÉ (2026-07-22). Sur 20 répétitions réelles où aucune commande
        // n'avait pu être émise (mode 2D inactif, aucun joueur détecté), le journal portait
        // simultanément `MESURE_INVALIDE t0_absent … repetition_a_rejouer` ET
        // `verdict=PASS`. Deux lignes contradictoires sur la même répétition — et c'est le
        // PASS qu'un lecteur pressé retient.
        //
        // Le mécanisme : sans commande émise, `derniereVitesse` valait 0, la persistance
        // était donc réputée nulle et « acceptée », et les jalons T1..T4 étaient bien
        // marqués par le stimulus. Toutes les cases du critère étaient cochées par une
        // absence de données.
        //
        // FAIL serait tout aussi faux : cela affirmerait que la sécurité n'a pas tenu, alors
        // qu'elle n'a pas été sollicitée. Un essai qui n'a rien mesuré ne réussit ni
        // n'échoue — il est NUL et doit être rejoué.
        if (!mesureExploitable(m)) return "NUL"
        val ok = when {
            s.name.startsWith("E03_FS") ->
                m.t1Nanos >= 0 && m.t6Nanos >= 0 && persistanceAcceptee(m)
            s == Scenario.E03_02_ZERO_MAINTENU ->
                m.t1Nanos >= 0 && m.t3Nanos >= 0 && persistanceAcceptee(m)
            // E03-11 « SORTIE VIRTUAL STICK EXPLICITE » — même famille que E03-02.
            //
            // Ce scénario demande au SDK de QUITTER le Virtual Stick, rien d'autre. Il ne
            // désarme pas le mode automatique (T2) et n'envoie pas de commande neutre (T3) :
            // ces deux jalons appartiennent à la chaîne d'ARRÊT D'URGENCE, que ce scénario
            // n'emprunte pas. Les exiger rendait FAIL inconditionnel — 5 répétitions, 5 FAIL,
            // T2 et T3 systématiquement absents (relevé du 2026-07-22), alors que la sortie
            // était demandée en 8 à 91 ms, acquittée en 58 à 134 ms, et qu'AUCUNE commande
            // ne persistait.
            //
            // Critère retenu : T1 (stimulus) + T4 (sortie VS effectivement demandée)
            // + persistance acceptée. Le critère CENTRAL reste jugé à l'identique ; seuls
            // les jalons qui n'ont pas d'existence dans ce scénario sont retirés.
            s == Scenario.E03_11_SORTIE_VS_EXPLICITE ->
                m.t1Nanos >= 0 && m.t4Nanos >= 0 && persistanceAcceptee(m)
            else ->
                m.t1Nanos >= 0 && m.t2Nanos >= 0 && m.t3Nanos >= 0 && m.t4Nanos >= 0 &&
                    persistanceAcceptee(m)
        }
        return if (ok) "PASS" else "FAIL"
    }

    /**
     * Ligne de journal exportable (une par répétition). Format stable, parsable :
     * "E03 scenario=… rep=… T0..T6(ms) persist_ms=… seuil_ms=… derniere_v=… verdict=…"
     * Les Tx sont exprimés en ms RELATIVES à T0 (T0=0). config_id fourni par l'appelant.
     */
    fun ligne(s: Scenario, repetition: Int, m: Mesures, configId: String): String {
        // UN JALON ANTÉRIEUR À T0 N'EST PAS CALCULABLE — il s'affiche « - », jamais en
        // négatif. Relevé du 2026-07-22 (E03-06) : `T5_ms=-12303`, la sortie du Virtual
        // Stick ayant été acquittée pendant la mise en veille tandis que T0, faute de
        // détection, continuait de suivre le heartbeat jusqu'au retour au premier plan.
        // Un délai négatif n'a pas de sens et n'a rien à faire dans un dossier ; la ligne
        // MARQUAGES dit par ailleurs si le jalon a été atteint, l'information n'est pas perdue.
        fun rel(x: Long): String {
            val d = deltaMs(m.t0Nanos, x) ?: return "-"
            return if (d < 0) "-" else d.toString()
        }
        val p = persistanceMs(m)
        return buildString {
            append("E03 scenario=").append(s.name)
            append(" rep=").append(repetition)
            append(" config_id=").append(configId)
            append(" T1_ms=").append(rel(m.t1Nanos))
            append(" T2_ms=").append(rel(m.t2Nanos))
            append(" T3_ms=").append(rel(m.t3Nanos))
            append(" T4_ms=").append(rel(m.t4Nanos))
            append(" T5_ms=").append(rel(m.t5Nanos))
            append(" T6_ms=").append(rel(m.t6Nanos))
            append(" derniere_v=").append(m.derniereVitesse)
            append(" persist_ms=").append(p?.toString() ?: "-")
            append(" seuil_ms=").append(persistanceMaxMs)
            append(" verdict=").append(verdict(s, m))
        }
    }
}
