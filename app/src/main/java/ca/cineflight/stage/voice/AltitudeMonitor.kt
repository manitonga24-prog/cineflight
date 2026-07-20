package ca.cineflight.stage.voice

/**
 * PHASE 3A — Surveillance de l'altitude AGL (au-dessus du point de decollage).
 *
 * PRINCIPE : observe l'altitude reelle et alerte a l'approche / au depassement d'une
 * limite configurable. Il ne commande JAMAIS le drone : le pilote decide de descendre.
 *
 * RAPPEL : la limite est configurable selon le drone, la categorie d'operation et les
 * autorisations. 122 m (400 ft) est une limite reglementaire canadienne courante, mais
 * ce moniteur n'affirme aucune conformite ; il applique seulement le seuil configure.
 *
 * ISOLATION : classe pure (aucune dependance Android/DJI). Fail-open. Anti-bavardage :
 * annonce au CHANGEMENT de niveau, hysteresis a la descente. Niveaux (defaut 122 m) :
 *   NORMAL       < 85%            aucune annonce
 *   APPROCHE     >= 85% (~104 m)  "Limite d'altitude bientot atteinte"
 *   LIMITE       >= 100% (122 m)  "Limite d'altitude atteinte"
 *   DEPASSEMENT  > limite + marge "Limite d'altitude depassee. Descendez"
 */
class AltitudeMonitor(
    private val systeme: FlightVoiceSystem
) {
    private enum class Niveau { NORMAL, APPROCHE, LIMITE, DEPASSEMENT }

    @Volatile var limiteM: Double = 122.0
    @Volatile var fractionApproche: Double = 0.85
    @Volatile var reArmMargeM: Double = 8.0
    @Volatile var depassementMargeM: Double = 10.0

    @Volatile private var niveauPrecedent = Niveau.NORMAL

    private fun actif(): Boolean = try {
        systeme.settings.active && systeme.settings.observationDji
    } catch (_: Throwable) { false }

    private fun pub(e: FlightVoiceEvent, nowMs: Long) {
        try { systeme.publishVoiceEventSafely(e, nowMs) } catch (_: Throwable) {}
    }

    private fun niveauBrut(a: Double): Niveau {
        val lim = limiteM
        return when {
            a >= lim + depassementMargeM -> Niveau.DEPASSEMENT
            a >= lim -> Niveau.LIMITE
            a >= lim * fractionApproche -> Niveau.APPROCHE
            else -> Niveau.NORMAL
        }
    }

    private fun seuilEntreeM(n: Niveau): Double = when (n) {
        Niveau.NORMAL -> 0.0
        Niveau.APPROCHE -> limiteM * fractionApproche
        Niveau.LIMITE -> limiteM
        Niveau.DEPASSEMENT -> limiteM + depassementMargeM
    }

    /**
     * A appeler periodiquement (~1-3 Hz) avec l'altitude AGL courante (m).
     * NaN -> ignore (pas de fix). nowMs fourni par l'appelant.
     */
    fun observer(altitudeAglM: Double, nowMs: Long) {
        runCatching {
            if (!actif()) { return }
            if (altitudeAglM.isNaN()) return
            val niveau = calculerNiveauAvecHysteresis(altitudeAglM)
            if (niveau == niveauPrecedent) return
            val montait = niveau.ordinal > niveauPrecedent.ordinal
            niveauPrecedent = niveau
            val aInt = Math.round(altitudeAglM).toInt()
            when (niveau) {
                Niveau.NORMAL -> pub(VoiceEvents.altitudeRecovered(nowMs), nowMs)
                Niveau.APPROCHE -> if (montait) pub(VoiceEvents.altitudeApproaching(nowMs, aInt), nowMs)
                                   else pub(VoiceEvents.altitudeRecovered(nowMs), nowMs)
                Niveau.LIMITE -> if (montait) pub(VoiceEvents.altitudeLimitReached(nowMs, aInt), nowMs)
                                 else pub(VoiceEvents.altitudeRecovered(nowMs), nowMs)
                Niveau.DEPASSEMENT -> pub(VoiceEvents.altitudeExceeded(nowMs, aInt), nowMs)
            }
        }
    }

    private fun calculerNiveauAvecHysteresis(a: Double): Niveau {
        val brut = niveauBrut(a)
        val prec = niveauPrecedent
        if (brut.ordinal >= prec.ordinal) return brut
        val seuilSortie = seuilEntreeM(prec) - reArmMargeM
        return if (a < seuilSortie) brut else prec
    }

    /** Reinitialise l'etat a chaque nouvelle session de vol. */
    fun nouvelleSessionVol() {
        runCatching { niveauPrecedent = Niveau.NORMAL }
    }
}
