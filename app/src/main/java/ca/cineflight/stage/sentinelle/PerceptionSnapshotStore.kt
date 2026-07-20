package ca.cineflight.stage.sentinelle

import java.util.concurrent.atomic.AtomicReference
import ca.cineflight.stage.control.ObstacleSafetyGate

/**
 * PerceptionSnapshotStore — magasin PUR des snapshots de perception (ObstacleSafetyGate).
 *
 * ROLE : porte TOUTE la logique de publication/lecture des snapshots que LecteurPerception
 * utilisait auparavant en interne : ecriture ATOMIQUE des valeurs + timestamp MONOTONE a la
 * publication, calcul de l'AGE (now - ts) a la LECTURE. Extrait ici pour etre 100% testable
 * en JVM, SANS aucune dependance DJI/Android (le callback DJI ne fait que passer des valeurs
 * brutes a publier...). La logique est donc IDENTIQUE en production et en test.
 *
 * DISCIPLINE ATOMIQUE (inchangee) : chaque publication est UNE seule ecriture d'une reference
 * immuable via AtomicReference ; le consommateur (autre thread) ne lit jamais un melange
 * valeurs/timestamp.
 *
 * REGLE HORLOGE : l'age se calcule TOUJOURS avec deux valeurs de la MEME horloge monotone
 * injectee ([clock]). Jamais de melange avec l'horloge murale.
 */
class PerceptionSnapshotStore(
    private val clock: MonotonicClock
) {

    // Etat interne immuable : valeurs + timestamps MONOTONES par canal.
    private data class StockVertical(
        val upwardMm: Int?, val upwardTsMs: Long,
        val downwardMm: Int?, val downwardTsMs: Long
    )
    private data class StockHorizontal(val distancesMm: List<Int>?, val tsMs: Long)

    private val stockVertical = AtomicReference<StockVertical?>(null)
    private val stockHorizontal = AtomicReference<StockHorizontal?>(null)

    /**
     * Publie un snapshot VERTICAL immuable a partir des valeurs brutes. Timestamp MONOTONE
     * unique pour les deux canaux (comme le callback DJI d'origine). Une seule ecriture atomique.
     */
    fun publierVertical(upwardMm: Int?, downwardMm: Int?) {
        val ts = clock.nowMs()
        stockVertical.set(StockVertical(upwardMm, ts, downwardMm, ts))
    }

    /**
     * Publie un snapshot HORIZONTAL immuable (liste brute des secteurs). Timestamp MONOTONE.
     */
    fun publierHorizontal(distancesMm: List<Int>?) {
        stockHorizontal.set(StockHorizontal(distancesMm, clock.nowMs()))
    }

    /** Efface les deux snapshots (perception perdue -> INDISPONIBLE cote gate). */
    fun effacer() {
        stockVertical.set(null)
        stockHorizontal.set(null)
    }

    // --- Calcul d'age a partir d'un `now` DONNE (une seule source de verite, zero duplication).
    //     Prives : les 3 lectures publiques passent par ici avec leur propre `now`.

    private fun verticalAvec(now: Long, s: StockVertical): ObstacleSafetyGate.VerticalSnapshot {
        val upAge = if (s.upwardMm != null) now - s.upwardTsMs else Long.MAX_VALUE
        val downAge = if (s.downwardMm != null) now - s.downwardTsMs else Long.MAX_VALUE
        return ObstacleSafetyGate.VerticalSnapshot(
            upwardMm = s.upwardMm, upwardAgeMs = upAge,
            downwardMm = s.downwardMm, downwardAgeMs = downAge
        )
    }

    private fun horizontalAvec(now: Long, s: StockHorizontal): ObstacleSafetyGate.HorizontalSnapshot =
        ObstacleSafetyGate.HorizontalSnapshot(distancesMm = s.distancesMm, ageMs = now - s.tsMs)

    /**
     * Lecture ATOMIQUE du snapshot vertical, ages FRAIS calcules a l'instant de l'appel
     * (clock.nowMs() - ts du canal). null si aucun snapshot publie. Age = Long.MAX_VALUE pour
     * un canal jamais recu (mm null) -> INDISPONIBLE cote gate.
     */
    fun snapshotVertical(): ObstacleSafetyGate.VerticalSnapshot? {
        val s = stockVertical.get() ?: return null
        return verticalAvec(clock.nowMs(), s)
    }

    /**
     * Lecture ATOMIQUE du snapshot horizontal, age FRAIS calcule a l'appel. null si aucun
     * snapshot publie.
     */
    fun snapshotHorizontal(): ObstacleSafetyGate.HorizontalSnapshot? {
        val s = stockHorizontal.get() ?: return null
        return horizontalAvec(clock.nowMs(), s)
    }

    /**
     * Lecture COMBINEE vertical + horizontal pour le gate. Appelle clock.nowMs() UNE SEULE FOIS
     * et calcule TOUS les ages avec ce meme `now` : les ages verticaux et horizontaux sont donc
     * parfaitement coherents entre eux (aucune derive entre deux appels). C'est cet objet, et
     * ces ages exacts, qui doivent etre a la fois journalises (PERCEPTION_SAMPLE) et transmis au
     * gate, pour que le log reflete precisement ce que le gate consomme ce tick-la.
     * Chaque volet est null si son canal n'a jamais ete publie.
     */
    fun snapshotPourGate(): GatePerceptionSnapshot {
        val now = clock.nowMs()
        val v = stockVertical.get()?.let { verticalAvec(now, it) }
        val h = stockHorizontal.get()?.let { horizontalAvec(now, it) }
        return GatePerceptionSnapshot(nowMs = now, vertical = v, horizontal = h)
    }

    /**
     * Instantane COMBINE et immuable transmis au gate : vertical + horizontal lus au MEME `now`.
     * @property nowMs l'instant monotone unique utilise pour tous les ages de ce snapshot.
     */
    data class GatePerceptionSnapshot(
        val nowMs: Long,
        val vertical: ObstacleSafetyGate.VerticalSnapshot?,
        val horizontal: ObstacleSafetyGate.HorizontalSnapshot?
    )
}
