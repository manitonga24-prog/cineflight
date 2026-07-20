package ca.cineflight.stage.control

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.content.Context
import ca.cineflight.stage.R

/**
 * Simulateur des commandes du Clicker.
 *
 * Il maintient uniquement un état virtuel : distance au sujet, angle autour
 * du sujet et pause. Il n'a aucune référence au pilote, au SDK DJI, au pont
 * cockpit ou aux moteurs.
 */
class SimulateurClicker(private val ctx: Context) {

    data class Etat(
        val distanceSujetM: Double = 8.0,
        val angleAutourSujetDeg: Double = 0.0,
        val enPause: Boolean = false,
        val sequence: Long = 0L
    )

    data class Resultat(
        val execute: Boolean,
        val status: String,
        val message: String,
        val etat: Etat
    )

    private var etat = Etat()

    @Synchronized
    fun executer(mouvement: String?): Resultat {
        val courant = etat

        if (
            courant.enPause &&
            mouvement !in setOf("PAUSE_HOVER", "STOP_HOVER", "REPRENDRE_SUIVI")
        ) {
            return Resultat(
                execute = false,
                status = "SIMULATION_BLOCKED_PAUSED",
                message = ctx.getString(R.string.sc_pause_bloque),
                etat = courant
            )
        }

        return when (mouvement) {
            "PAUSE_HOVER",
            "STOP_HOVER" -> {
                etat = courant.copy(
                    enPause = true,
                    sequence = courant.sequence + 1
                )
                resultat(
                    status = "SIMULATION_PAUSED",
                    message = ctx.getString(R.string.sc_stabilise)
                )
            }

            "REPRENDRE_SUIVI" -> {
                etat = courant.copy(
                    enPause = false,
                    sequence = courant.sequence + 1
                )
                resultat(
                    status = "SIMULATION_RESUMED",
                    message = ctx.getString(R.string.sc_reprend)
                )
            }

            "RAPPROCHE_SUJET",
            "RAPPROCHE_SUJET_PRONONCE" -> {
                val nouvelleDistance = max(DISTANCE_MIN_M, courant.distanceSujetM - PAS_RAPPROCHE_M)

                if (nouvelleDistance >= courant.distanceSujetM) {
                    Resultat(
                        execute = false,
                        status = "SIMULATION_LIMIT_REACHED",
                        message = ctx.getString(R.string.sc_dist_min),
                        etat = courant
                    )
                } else {
                    etat = courant.copy(
                        distanceSujetM = nouvelleDistance,
                        sequence = courant.sequence + 1
                    )
                    resultat(
                        status = "SIMULATION_EXECUTED",
                        message = ctx.getString(R.string.sc_rapproche)
                    )
                }
            }

            "ELOIGNEMENT_SUJET" -> {
                val nouvelleDistance = min(DISTANCE_MAX_M, courant.distanceSujetM + PAS_ELOIGNE_M)

                if (nouvelleDistance <= courant.distanceSujetM) {
                    Resultat(
                        execute = false,
                        status = "SIMULATION_LIMIT_REACHED",
                        message = ctx.getString(R.string.sc_dist_max),
                        etat = courant
                    )
                } else {
                    etat = courant.copy(
                        distanceSujetM = nouvelleDistance,
                        sequence = courant.sequence + 1
                    )
                    resultat(
                        status = "SIMULATION_EXECUTED",
                        message = ctx.getString(R.string.sc_eloigne)
                    )
                }
            }

            "TRAVELLING_ARRIERE" -> {
                etat = courant.copy(
                    distanceSujetM = min(DISTANCE_MAX_M, courant.distanceSujetM + PAS_REVEAL_M),
                    sequence = courant.sequence + 1
                )
                resultat(
                    status = "SIMULATION_EXECUTED",
                    message = ctx.getString(R.string.sc_travelling)
                )
            }

            "CHANGER_COTE" -> {
                etat = courant.copy(
                    angleAutourSujetDeg = normaliserAngle(courant.angleAutourSujetDeg + 180.0),
                    sequence = courant.sequence + 1
                )
                resultat(
                    status = "SIMULATION_EXECUTED",
                    message = ctx.getString(R.string.sc_cote)
                )
            }

            "ORBITE_LARGE" -> {
                etat = courant.copy(
                    angleAutourSujetDeg = normaliserAngle(courant.angleAutourSujetDeg + PAS_ORBITE_DEG),
                    sequence = courant.sequence + 1
                )
                resultat(
                    status = "SIMULATION_EXECUTED",
                    message = ctx.getString(R.string.sc_orbite)
                )
            }

            else -> Resultat(
                execute = false,
                status = "SIMULATION_UNKNOWN_MOVEMENT",
                message = ctx.getString(R.string.sc_inconnu),
                etat = courant
            )
        }
    }

    @Synchronized
    fun etatCourant(): Etat = etat

    private fun resultat(status: String, message: String) =
        Resultat(
            execute = true,
            status = status,
            message = message,
            etat = etat
        )

    companion object {
        const val DISTANCE_MIN_M = 4.0
        const val DISTANCE_MAX_M = 30.0
        const val PAS_RAPPROCHE_M = 1.0
        const val PAS_ELOIGNE_M = 1.5
        const val PAS_REVEAL_M = 2.0
        const val PAS_ORBITE_DEG = 30.0

        fun descriptionEtat(ctx: Context, etat: Etat): String {
            val distance = (etat.distanceSujetM * 10.0).roundToInt() / 10.0
            val angle = etat.angleAutourSujetDeg.roundToInt()
            val pause = ctx.getString(if (etat.enPause) R.string.sc_mode_pause else R.string.sc_mode_actif)
            return ctx.getString(R.string.sc_desc_etat, distance, angle, pause)
        }

        private fun normaliserAngle(angle: Double): Double {
            val resultat = angle % 360.0
            return if (resultat < 0.0) resultat + 360.0 else resultat
        }
    }
}