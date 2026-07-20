package ca.cineflight.stage.control

import ca.cineflight.stage.cine.ClientRtkSujet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Couche Android V4: ingestion 10 Hz et prediction cinematique passive a 50 Hz.
 *
 * Le moteur ne commande jamais le drone. Il fournit un etat mesurable qui sera
 * valide sur le terrain avant tout branchement a une boucle Virtual Stick.
 */
class MoteurFusionRtkV4(
    profilInitial: ProfilSujetMobile = ProfilSujetMobile.MARCHE
) {
    data class PredictionTempsReel(
        val version: String = VERSION,
        val profil: ProfilSujetMobile,
        val tickTimestampNs: Long,
        val sourceSequence: Long?,
        val streamStatus: String,
        val sourceRateHz: Double?,
        val fusionRateHz: Double?,
        val ageMs: Double?,
        val serverAgeMs: Double?,
        val downlinkAgeMs: Double?,
        val endToEndAgeMs: Double?,
        val displayReady: Boolean,
        val controlReady: Boolean,
        val reason: String,
        val lat: Double?,
        val lon: Double?,
        val headingDeg: Double?,
        val speedMps: Double?,
        val accelerationMps2: Double?,
        val turnRateDegS: Double?,
        val horizonS: Double,
        val uncertaintyM: Double?,
        val diagnostic: DiagnosticPredictionRtk.EtatDiagnostic?
    ) {
        fun journal(): String = buildString {
            append("v4_prediction")
            append(" version=").append(version)
            append(" profile=").append(profil.code)
            append(" display_ready=").append(displayReady)
            append(" control_ready=").append(controlReady)
            append(" reason=").append(reason)
            append(" stream=").append(streamStatus)
            append(" source_seq=").append(sourceSequence ?: "--")
            append(" source_hz=").append(format(sourceRateHz, 3))
            append(" fusion_hz=").append(format(fusionRateHz, 2))
            append(" age_ms=").append(format(ageMs, 1))
            append(" server_age_ms=").append(format(serverAgeMs, 1))
            append(" downlink_ms=").append(format(downlinkAgeMs, 1))
            append(" e2e_ms=").append(format(endToEndAgeMs, 1))
            append(" speed_mps=").append(format(speedMps, 2))
            append(" heading_deg=").append(format(headingDeg, 1))
            append(" accel_mps2=").append(format(accelerationMps2, 2))
            append(" turn_deg_s=").append(format(turnRateDegS, 1))
            append(" horizon_s=").append(format(horizonS, 2))
            append(" uncertainty_m=").append(format(uncertaintyM, 2))
            append(" lat=").append(format(lat, 7))
            append(" lon=").append(format(lon, 7))
        }

        private fun format(value: Double?, decimals: Int): String =
            if (value == null || !value.isFinite()) "--" else "%1$.${decimals}f".format(java.util.Locale.US, value)
    }

    private var profil: ProfilSujetMobile = profilInitial
    private var moteur = DiagnosticPredictionRtk(profil.configurationPrediction())
    private var dernierePosition: ClientRtkSujet.PositionSujet? = null
    private var dernierDiagnostic: DiagnosticPredictionRtk.EtatDiagnostic? = null
    private var derniereIngestionMonoNs: Long? = null
    private var derniereSequence: Long? = null
    private val ticks = java.util.ArrayDeque<Long>()

    @Synchronized
    fun reconfigurer(nouveauProfil: ProfilSujetMobile) {
        profil = nouveauProfil
        moteur = DiagnosticPredictionRtk(nouveauProfil.configurationPrediction())
        dernierePosition = null
        dernierDiagnostic = null
        derniereIngestionMonoNs = null
        derniereSequence = null
        ticks.clear()
    }

    @Synchronized
    fun profilActuel(): ProfilSujetMobile = profil

    /** Ingestion unique par source_sequence, normalement a 10 Hz. */
    @Synchronized
    fun ingerer(position: ClientRtkSujet.PositionSujet): DiagnosticPredictionRtk.EtatDiagnostic {
        val sequence = position.sourceSequence

        dernierePosition = position
        derniereIngestionMonoNs = android.os.SystemClock.elapsedRealtimeNanos()

        // Un evenement de statut doit bloquer immediatement, meme s'il reprend
        // la derniere source_sequence connue.
        if (!position.networkConnected || position.streamStatus != "LIVE" || !position.valid) {
            val diagnostic = moteur.signalerIndisponible(
                position.reason ?: "STREAM_${position.streamStatus}",
                position.rtk.name
            )
            dernierDiagnostic = diagnostic
            return diagnostic
        }

        if (sequence != null && derniereSequence != null && sequence <= derniereSequence!!) {
            return dernierDiagnostic ?: moteur.signalerIndisponible("SEQUENCE_NON_MONOTONE", position.rtk.name)
        }
        if (sequence != null) derniereSequence = sequence

        val diagnostic = if (!position.present || !position.lat.isFinite() || !position.lon.isFinite()) {
            moteur.signalerIndisponible(position.reason ?: position.streamStatus, position.rtk.name)
        } else {
            val timestampMs = position.sourceTimestampNs?.div(1_000_000L)
                ?: (System.currentTimeMillis() - ((position.ageS ?: 0.0) * 1000.0).toLong())
            moteur.mettreAJour(
                lat = position.lat,
                lon = position.lon,
                ageS = position.ageS ?: Double.POSITIVE_INFINITY,
                fiable = position.fiable,
                qualiteRtk = position.rtk.name,
                timestampMs = timestampMs
            )
        }
        dernierDiagnostic = diagnostic
        return diagnostic
    }

    @Synchronized
    fun predireTempsReel(nowNs: Long = android.os.SystemClock.elapsedRealtimeNanos()): PredictionTempsReel {
        ticks.addLast(nowNs)
        val cutoff = nowNs - 2_000_000_000L
        while (ticks.isNotEmpty() && ticks.first() < cutoff) ticks.removeFirst()
        val fusionHz = if (ticks.size >= 2) {
            val dt = (ticks.last() - ticks.first()) / 1_000_000_000.0
            if (dt > 0.0) (ticks.size - 1) / dt else null
        } else null

        val p = dernierePosition
        val d = dernierDiagnostic
        val ingestionNs = derniereIngestionMonoNs
        if (p == null || d == null || ingestionNs == null) {
            return PredictionTempsReel(
                profil = profil,
                tickTimestampNs = nowNs,
                sourceSequence = null,
                streamStatus = "NO_DATA",
                sourceRateHz = null,
                fusionRateHz = fusionHz,
                ageMs = null,
                serverAgeMs = null,
                downlinkAgeMs = null,
                endToEndAgeMs = null,
                displayReady = false,
                controlReady = false,
                reason = "AUCUNE_MESURE",
                lat = null,
                lon = null,
                headingDeg = null,
                speedMps = null,
                accelerationMps2 = null,
                turnRateDegS = null,
                horizonS = 0.0,
                uncertaintyM = null,
                diagnostic = null
            )
        }

        val elapsedS = ((nowNs - ingestionNs).coerceAtLeast(0L)) / 1_000_000_000.0
        val ageS = (p.ageS ?: Double.POSITIVE_INFINITY) + elapsedS
        val streamLive = p.networkConnected && p.streamStatus == "LIVE"
        val sourceRateOk = (p.measuredRateHz ?: 0.0) >= 5.0
        val ageOk = ageS <= 0.50
        val displayReady = d.pret && p.fiable && streamLive && ageOk
        val controlReady = d.controlePret && p.controleFiable && streamLive && sourceRateOk && ageS <= 0.30

        val baseLat = d.latActuelle ?: p.lat.takeIf { it.isFinite() }
        val baseLon = d.lonActuelle ?: p.lon.takeIf { it.isFinite() }
        val speed0 = (d.vitesseMps ?: p.groundSpeedMps)?.coerceAtLeast(0.0)
        val acceleration = d.accelerationMps2 ?: 0.0
        val heading = d.capDeg ?: p.capDeg
        val turnRate = d.virageDegS ?: 0.0
        val horizon = (ageS + profil.delaiReactionS).coerceIn(0.0, profil.horizonMaxS)

        val predicted = if (
            displayReady && baseLat != null && baseLon != null && speed0 != null && heading != null
        ) {
            extrapoler(baseLat, baseLon, speed0, acceleration, heading, turnRate, horizon)
        } else null

        val uncertainty = d.incertitudeM?.let {
            it + ageS * when (profil) {
                ProfilSujetMobile.MARCHE -> 0.30
                ProfilSujetMobile.COURSE -> 0.55
                ProfilSujetMobile.VELO -> 0.80
                ProfilSujetMobile.SKI -> 1.00
                ProfilSujetMobile.BATEAU -> 0.75
                ProfilSujetMobile.MOTO -> 1.20
                ProfilSujetMobile.AUTO -> 1.20
                ProfilSujetMobile.AUTO_COURSE -> 1.60
                ProfilSujetMobile.PERSONNALISE -> 1.00
            }
        }

        val reason = when {
            !p.networkConnected -> "WS_DECONNECTE"
            p.streamStatus != "LIVE" -> "STREAM_${p.streamStatus}"
            !sourceRateOk -> "CADENCE_INSUFFISANTE"
            !ageOk -> "MESURE_TROP_ANCIENNE"
            !d.pret -> d.raison
            !controlReady -> d.raisonControle
            else -> "OK"
        }

        return PredictionTempsReel(
            profil = profil,
            tickTimestampNs = nowNs,
            sourceSequence = p.sourceSequence,
            streamStatus = p.streamStatus,
            sourceRateHz = p.measuredRateHz,
            fusionRateHz = fusionHz,
            ageMs = if (ageS.isFinite()) ageS * 1000.0 else null,
            serverAgeMs = p.serverAgeMs,
            downlinkAgeMs = p.downlinkAgeMs,
            endToEndAgeMs = p.endToEndAgeMs,
            displayReady = displayReady,
            controlReady = controlReady,
            reason = reason,
            lat = predicted?.first,
            lon = predicted?.second,
            headingDeg = heading,
            speedMps = speed0,
            accelerationMps2 = d.accelerationMps2,
            turnRateDegS = d.virageDegS,
            horizonS = horizon,
            uncertaintyM = uncertainty,
            diagnostic = d
        )
    }

    private fun extrapoler(
        lat: Double,
        lon: Double,
        speedMps: Double,
        accelerationMps2: Double,
        headingDeg: Double,
        turnRateDegS: Double,
        dtS: Double
    ): Pair<Double, Double> {
        val averageSpeed = (speedMps + 0.5 * accelerationMps2 * dtS).coerceAtLeast(0.0)
        val theta = Math.toRadians(headingDeg)
        val omega = Math.toRadians(turnRateDegS)
        val eastM: Double
        val northM: Double
        if (abs(omega) < 1e-4) {
            val distance = averageSpeed * dtS
            eastM = distance * sin(theta)
            northM = distance * cos(theta)
        } else {
            val theta2 = theta + omega * dtS
            eastM = averageSpeed / omega * (cos(theta) - cos(theta2))
            northM = averageSpeed / omega * (sin(theta2) - sin(theta))
        }
        return deplacer(lat, lon, northM, eastM)
    }

    private fun deplacer(lat: Double, lon: Double, northM: Double, eastM: Double): Pair<Double, Double> {
        val earthRadiusM = 6_371_000.0
        val dLat = northM / earthRadiusM
        val cosLat = abs(cos(Math.toRadians(lat))).coerceAtLeast(1e-8)
        val dLon = eastM / (earthRadiusM * cosLat)
        return (lat + Math.toDegrees(dLat)) to (lon + Math.toDegrees(dLon))
    }

    companion object {
        const val VERSION = "4.2.0-android-float-vision"
        const val FUSION_PERIOD_MS = 20L
        const val FUSION_PERIOD_NS = 20_000_000L
    }
}
