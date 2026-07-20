package ca.cineflight.stage.sport.soccer

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * WatchdogIndependant — WATCHDOG SUR THREAD STRICTEMENT INDEPENDANT (REQ-WDG-001).
 *
 * PROBLEME RESOLU. CommandeWatchdog (watchdog de cycle) bat ET verifie dans le MEME fil :
 * un gel TOTAL de ce fil n'est pas detecte, et rien ne neutralise alors la derniere
 * commande Virtual Stick potentiellement persistante (RPN 75). Ce watchdog-ci s'execute
 * sur son PROPRE thread (thread B), independant du fil d'emission (thread A) :
 *
 *   Thread A (emission)  : appelle battement() a chaque cycle sain.
 *   Thread B (watchdog)  : verifie periodiquement l'age du dernier battement.
 *   A gele/meurt         : B le detecte (battement perime) -> onDefaillance() UNE fois :
 *                          desarmer le mode + neutraliser la commande + demander la
 *                          sortie du Virtual Stick (actions fournies par l'integrateur).
 *
 * PROPRIETES DE SECURITE :
 *  - HORLOGE MONOTONE injectable (System.nanoTime par defaut) : insensible a l'heure murale.
 *  - FAIL-CLOSED : armer() vaut premier battement ; si le fil A ne bat JAMAIS apres
 *    l'armement, le declenchement survient dans timeoutMs.
 *  - Recul d'horloge (age negatif) = anomalie -> traite comme PERIME (fail-closed).
 *  - LATCH one-shot : une defaillance declenche UNE seule fois ; pas de rearmement
 *    automatique — reset() explicite requis (puis armer() de nouveau).
 *  - Les exceptions de onDefaillance sont contenues : le latch reste pose, le thread B survit.
 *  - Thread B est un daemon nomme, arretable proprement (arreter()).
 *
 * TESTABILITE : la decision est PURE (verifier(nowNanos)) et testable sans thread ;
 * le thread B n'est qu'un porteur (teste aussi, y compris avec un fil A reellement gele).
 *
 * LIMITE HONNETE (dossier de securite) : cette classe demontre la DETECTION independante
 * et l'EMISSION des demandes de neutralisation/sortie Virtual Stick depuis un fil vivant.
 * L'EFFET PHYSIQUE sur l'aeronef (annulation reelle de la derniere commande) depend du
 * comportement DJI apres cessation et reste a caracteriser au banc par E-03.
 *
 * @param timeoutMs age maximal tolere du dernier battement (ms). > 0.
 * @param periodeMs periode de verification du thread B (ms). > 0, < timeoutMs.
 * @param horlogeNanos horloge monotone (injectable pour les tests).
 * @param onDefaillance actions de mise en securite, executees SUR LE THREAD B :
 *        doivent etre non bloquantes et ne PAS toucher l'UI directement.
 */
class WatchdogIndependant(
    private val timeoutMs: Long = ca.cineflight.stage.control.SafetyLimits.WATCHDOG_TIMEOUT_MS,
    private val periodeMs: Long = ca.cineflight.stage.control.SafetyLimits.WATCHDOG_INDEP_PERIODE_MS,
    private val horlogeNanos: () -> Long = System::nanoTime,
    private val onDefaillance: (ageMs: Long) -> Unit,
) {

    init {
        require(timeoutMs > 0) { "timeoutMs doit etre > 0 (recu $timeoutMs)" }
        require(periodeMs in 1 until timeoutMs) {
            "periodeMs doit etre dans [1, timeoutMs) (recu $periodeMs pour timeout $timeoutMs)"
        }
    }

    // Nanos monotones du dernier battement. Long.MIN_VALUE = aucun battement.
    private val dernierBattementNanos = AtomicLong(Long.MIN_VALUE)

    /** Surveillance active ? (armee au moment ou le mode soccer est arme.) */
    private val surveillanceArmee = AtomicBoolean(false)

    /** Latch one-shot : une defaillance a ete declenchee (pas de rearmement auto). */
    private val defaillanceDeclenchee = AtomicBoolean(false)

    @Volatile private var thread: Thread? = null
    private val doitTourner = AtomicBoolean(false)

    // ── API thread A (emission) ────────────────────────────────────────────────

    /** Battement de cycle sain, publie par le fil d'emission. Lock-free. */
    fun battement(nowNanos: Long = horlogeNanos()) {
        dernierBattementNanos.set(nowNanos)
    }

    // ── Armement / etat ────────────────────────────────────────────────────────

    /**
     * Arme la surveillance. L'armement vaut PREMIER battement : si le fil d'emission ne
     * bat jamais ensuite, la defaillance est declaree dans timeoutMs (fail-closed).
     * Sans effet si une defaillance est deja verrouillee (reset() d'abord).
     */
    fun armer(nowNanos: Long = horlogeNanos()) {
        if (defaillanceDeclenchee.get()) return
        dernierBattementNanos.set(nowNanos)
        surveillanceArmee.set(true)
    }

    /** Suspend la surveillance (desarmement volontaire du mode : pas une defaillance). */
    fun desarmerSurveillance() {
        surveillanceArmee.set(false)
    }

    /** true si une defaillance a ete declenchee (latch). */
    fun defaillanceDeclenchee(): Boolean = defaillanceDeclenchee.get()

    /** true si la surveillance est actuellement armee. */
    fun surveillanceArmee(): Boolean = surveillanceArmee.get()

    /**
     * Reinitialisation EXPLICITE apres defaillance (decision humaine) : leve le latch et
     * desarme la surveillance. Un nouvel armer() est requis pour surveiller a nouveau.
     */
    fun reset() {
        defaillanceDeclenchee.set(false)
        surveillanceArmee.set(false)
        dernierBattementNanos.set(Long.MIN_VALUE)
    }

    // ── Decision PURE (testable sans thread) ───────────────────────────────────

    /**
     * Verifie l'age du battement et declenche la defaillance si necessaire.
     * PURE vis-a-vis du temps (nowNanos fourni). Retourne true si la defaillance a ete
     * declenchee PAR CET APPEL (one-shot).
     */
    fun verifier(nowNanos: Long = horlogeNanos()): Boolean {
        if (!surveillanceArmee.get()) return false
        if (defaillanceDeclenchee.get()) return false
        val dernier = dernierBattementNanos.get()
        if (dernier == Long.MIN_VALUE) return false   // pas encore arme correctement
        val ageMs = (nowNanos - dernier) / 1_000_000L
        // age negatif = recul/anomalie d'horloge -> PERIME (fail-closed).
        val perime = ageMs < 0 || ageMs > timeoutMs
        if (!perime) return false
        // one-shot : seul le premier gagnant declenche.
        if (!defaillanceDeclenchee.compareAndSet(false, true)) return false
        surveillanceArmee.set(false)
        try {
            onDefaillance(ageMs)
        } catch (_: Throwable) {
            // Les actions de securite ont echoue en partie : le latch RESTE pose et le
            // thread B survit. L'integrateur journalise dans onDefaillance.
        }
        return true
    }

    // ── Thread B (porteur) ─────────────────────────────────────────────────────

    /** Demarre le thread B (idempotent). Daemon nomme, periode fixe. */
    fun demarrer() {
        if (doitTourner.getAndSet(true)) return
        val t = Thread {
            while (doitTourner.get()) {
                try {
                    verifier(horlogeNanos())
                    Thread.sleep(periodeMs)
                } catch (_: InterruptedException) {
                    // arret demande -> sortir si doitTourner est false
                } catch (_: Throwable) {
                    // jamais laisser mourir le thread B sur une exception impromptue
                }
            }
        }
        t.name = "WatchdogIndependant"
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Arrete le thread B proprement (fin d'activite). */
    fun arreter() {
        doitTourner.set(false)
        thread?.interrupt()
        thread = null
    }
}
