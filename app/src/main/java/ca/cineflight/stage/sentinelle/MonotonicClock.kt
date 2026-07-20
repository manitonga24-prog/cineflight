package ca.cineflight.stage.sentinelle

/**
 * Horloge MONOTONE (ms) injectable — ObstacleSafetyGate.
 *
 * Contrat : renvoie des valeurs croissantes issues d'une base MONOTONE (en production,
 * SystemClock.elapsedRealtime()). Sert UNIQUEMENT aux timestamps des mesures de perception
 * et au calcul des ages/fraicheur consommes par le gate.
 *
 * REGLE : une duree/fraicheur se calcule TOUJOURS avec deux valeurs de la MEME horloge
 * monotone. On ne melange JAMAIS avec l'horloge MURALE (System.currentTimeMillis), reservee
 * aux dates de diagnostic/journaux/affichage. Si ces diagnostics doivent devenir
 * deterministes plus tard, creer une abstraction distincte (ex. WallClock), sans melanger.
 *
 * En test : injecter une horloge deterministe, ex. MonotonicClock { 10_000L }, ou pilotee
 * par une variable capturee pour faire avancer le temps a volonte.
 */
fun interface MonotonicClock {
    fun nowMs(): Long
}
