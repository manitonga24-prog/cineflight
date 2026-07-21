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
    /** Seuil d'acceptation de la persistance (ms). Au-delà → verdict FAIL sur ce critère. */
    private val persistanceMaxMs: Long = 500L,
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
        if (m.derniereVitesse == 0f) return 0L
        return deltaMs(m.t0Nanos, m.finVitesseNonNulleNanos)
    }

    /** true si la persistance mesurée respecte le seuil d'acceptation. */
    fun persistanceAcceptee(m: Mesures): Boolean {
        val p = persistanceMs(m) ?: return false   // non mesuré = non accepté (fail-closed)
        return p in 0..persistanceMaxMs
    }

    /**
     * Verdict de scénario. PASS uniquement si : détection présente (T1), désarmement (T2),
     * demande neutre (T3), demande de sortie VS (T4), et PERSISTANCE acceptée.
     * (Le volet FS a ses propres critères : ici on exige au moins T1 et l'effet T6.)
     */
    fun verdict(s: Scenario, m: Mesures): String {
        val fs = s.name.startsWith("E03_FS")
        val ok = if (fs) {
            m.t1Nanos >= 0 && m.t6Nanos >= 0 && persistanceAcceptee(m)
        } else {
            m.t1Nanos >= 0 && m.t2Nanos >= 0 && m.t3Nanos >= 0 && m.t4Nanos >= 0 && persistanceAcceptee(m)
        }
        return if (ok) "PASS" else "FAIL"
    }

    /**
     * Ligne de journal exportable (une par répétition). Format stable, parsable :
     * "E03 scenario=… rep=… T0..T6(ms) persist_ms=… seuil_ms=… derniere_v=… verdict=…"
     * Les Tx sont exprimés en ms RELATIVES à T0 (T0=0). config_id fourni par l'appelant.
     */
    fun ligne(s: Scenario, repetition: Int, m: Mesures, configId: String): String {
        fun rel(x: Long) = deltaMs(m.t0Nanos, x)?.toString() ?: "-"
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
