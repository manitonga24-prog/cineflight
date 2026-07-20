package ca.cineflight.stage.sport.soccer

import java.util.concurrent.atomic.AtomicLong

/**
 * CommandeWatchdog — CHIEN DE GARDE de fraicheur de cycle (Phase 1.2, NC-T2-003bis).
 *
 * PROBLEME RESOLU. La commande soccer est produite par une boucle periodique (~10 Hz). Si
 * cette boucle se FIGE ou RALENTIT dangereusement (thread bloque, GC long, deadlock partiel),
 * une commande "peristante" pourrait continuer a etre emise alors que la chaine de decision
 * n'est plus vivante. Le drone poursuivrait un mouvement sur une decision perimee.
 *
 * PRINCIPE. La boucle appelle {@link #battement} a chaque cycle SAIN (fin d'iteration reussie).
 * Au point d'emission, on demande {@link #cycleFrais} : si le dernier battement est trop vieux
 * (au-dela de {@code timeoutMs}), le cycle est considere MORT et l'emission doit etre forcee a
 * ZERO. FAIL-CLOSED : tant qu'aucun battement n'a eu lieu, le cycle est considere NON frais.
 *
 * Base sur une HORLOGE MONOTONE fournie par l'appelant (System.nanoTime cote Android) : jamais
 * d'heure murale, donc insensible aux changements d'heure systeme. Classe PURE (aucun SDK/
 * Android), donc testable, y compris sous concurrence.
 *
 * @param timeoutMs age maximal toleres du dernier battement, en millisecondes. Au-dela, le
 *   cycle est declare mort. Doit couvrir plusieurs periodes de boucle (ex. 500 ms pour 10 Hz).
 */
class CommandeWatchdog(private val timeoutMs: Long = DEFAUT_TIMEOUT_MS) {

    init {
        require(timeoutMs > 0) { "timeoutMs doit etre > 0 (recu $timeoutMs)" }
    }

    // Nanos monotones du dernier battement. Long.MIN_VALUE = "aucun battement encore".
    // AtomicLong : ecrit par le thread de boucle, lu par le thread d'emission, sans verrou.
    private val dernierBattementNanos = AtomicLong(Long.MIN_VALUE)

    /**
     * Enregistre un battement (cycle sain). A appeler a la FIN de chaque iteration reussie
     * de la boucle de decision/emission.
     * @param nowNanos horloge monotone courante (System.nanoTime).
     */
    fun battement(nowNanos: Long) {
        dernierBattementNanos.set(nowNanos)
    }

    /**
     * @return true si un battement a eu lieu ET qu'il date de moins de {@code timeoutMs}.
     *   FAIL-CLOSED : false si aucun battement encore, false si le dernier est trop vieux.
     * @param nowNanos horloge monotone courante (System.nanoTime).
     */
    fun cycleFrais(nowNanos: Long): Boolean {
        val dernier = dernierBattementNanos.get()
        if (dernier == Long.MIN_VALUE) return false           // jamais bat -> non frais
        val ageNanos = nowNanos - dernier
        if (ageNanos < 0L) return false                        // horloge incoherente -> fail-closed
        return ageNanos <= timeoutMs * 1_000_000L
    }

    /** Age du dernier battement en ms, ou Long.MAX_VALUE si aucun battement. Diagnostic. */
    fun ageMs(nowNanos: Long): Long {
        val dernier = dernierBattementNanos.get()
        if (dernier == Long.MIN_VALUE) return Long.MAX_VALUE
        val ageNanos = nowNanos - dernier
        return if (ageNanos < 0L) Long.MAX_VALUE else ageNanos / 1_000_000L
    }

    /**
     * GARDE-FOU d'emission : renvoie le throttle demande SEULEMENT si le cycle est frais,
     * sinon 0. Point d'application unique cote emission 2D (altitude seule).
     * @param throttleDemandeMps throttle propose par le controleur d'altitude.
     * @param nowNanos horloge monotone courante.
     * @return throttle a emettre (0 si cycle mort).
     */
    fun filtrerThrottle(throttleDemandeMps: Float, nowNanos: Long): Float =
        if (cycleFrais(nowNanos)) throttleDemandeMps else 0f

    companion object {
        /** 500 ms : couvre 5 periodes de la boucle 10 Hz avant de declarer le cycle mort. */
        const val DEFAUT_TIMEOUT_MS: Long = 500L
    }
}
