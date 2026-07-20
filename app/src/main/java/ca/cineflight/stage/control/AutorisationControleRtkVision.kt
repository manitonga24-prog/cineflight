package ca.cineflight.stage.control

import ca.cineflight.stage.cine.ClientRtkSujet

/**
 * Politique de controle V4.2 pour le suivi d'un sujet mobile.
 *
 * - FIX: le suivi complet peut conserver les translations existantes, uniquement
 *   si le moteur de prediction a valide le controle.
 * - FLOAT + YOLO stable: assistance reelle limitee a la nacelle. Le yaw et les axes
 *   de translation et d'altitude sont forces a zero.
 * - GPS/LOST/STALE/DISCONNECTED/vision incertaine: HOLD fail-closed.
 *
 * Cette classe est pure Kotlin afin que les invariants puissent etre testes sans
 * SDK DJI ni Android.
 */
class AutorisationControleRtkVision {

    enum class Mode {
        BLOQUE,
        FIX_COMPLET,
        FLOAT_VISION
    }

    data class Verdict(
        val mode: Mode,
        val raison: String,
        val translationAutorisee: Boolean,
        val altitudeAutorisee: Boolean,
        val yawAutorise: Boolean,
        val gimbalAutorise: Boolean
    ) {
        val actif: Boolean get() = mode != Mode.BLOQUE
    }

    data class Axes(
        val vx: Float,
        val vy: Float,
        val vz: Float,
        val yawRate: Float,
        val gimbalPitch: Float,
        val gimbalYaw: Float
    )

    fun evaluer(
        position: ClientRtkSujet.PositionSujet?,
        predictionControlReady: Boolean,
        cibleVerrouillee: Boolean,
        visionRequise: Boolean,
        yoloTrouve: Boolean,
        confianceYolo: Float,
        nbCibles: Int,
        hauteurBoite: Float
    ): Verdict {
        if (position == null) return bloque("AUCUNE_POSITION_RTK")
        if (!position.networkConnected) return bloque("WS_DECONNECTE")
        if (position.streamStatus != "LIVE") return bloque("STREAM_${position.streamStatus}")
        if (!position.present || !position.valid) return bloque("POSITION_INVALIDE")
        val ageMesure = position.ageS ?: Double.POSITIVE_INFINITY
        if (!ageMesure.isFinite() || ageMesure > AGE_MAX_S) return bloque("MESURE_TROP_ANCIENNE")
        val cadence = position.measuredRateHz ?: 0.0
        if (!cadence.isFinite() || cadence < CADENCE_MIN_HZ) return bloque("CADENCE_INSUFFISANTE")
        if (position.sourceSequence == null) return bloque("SEQUENCE_ABSENTE")
        if ((position.rtcmAgeMs ?: Long.MAX_VALUE) > RTCM_AGE_MAX_MS) return bloque("RTCM_TROP_ANCIEN")

        if (visionRequise) {
            if (!cibleVerrouillee) return bloque("CIBLE_NON_VERROUILLEE")
            if (!yoloTrouve) return bloque("YOLO_SUJET_PERDU")
            if (nbCibles != 1) return bloque("YOLO_CIBLES_AMBIGUES")
            if (confianceYolo < CONFIANCE_YOLO_MIN) return bloque("YOLO_CONFIANCE_INSUFFISANTE")
            if (hauteurBoite !in HAUTEUR_BOITE_MIN..HAUTEUR_BOITE_MAX) {
                return bloque("YOLO_DISTANCE_VISUELLE_HORS_PLAGE")
            }
        }

        return when (position.rtk) {
            ClientRtkSujet.StatutRtk.FIX -> {
                if (!position.controleFiable) bloque("FIX_NON_FIABLE")
                else if (!predictionControlReady) bloque("MODELE_NON_STABILISE")
                else Verdict(
                    mode = Mode.FIX_COMPLET,
                    raison = "FIX_CONTROLE_COMPLET",
                    translationAutorisee = true,
                    altitudeAutorisee = true,
                    yawAutorise = false,
                    gimbalAutorise = true
                )
            }

            ClientRtkSujet.StatutRtk.FLOAT -> {
                if (!visionRequise) return bloque("FLOAT_EXIGE_YOLO")
                val hacc = position.haccM ?: Double.POSITIVE_INFINITY
                if (hacc > FLOAT_HACC_MAX_M) bloque("FLOAT_HACC_TROP_ELEVEE")
                else Verdict(
                    mode = Mode.FLOAT_VISION,
                    raison = "FLOAT_YOLO_GIMBAL_ONLY",
                    translationAutorisee = false,
                    altitudeAutorisee = false,
                    yawAutorise = false,
                    gimbalAutorise = true
                )
            }

            ClientRtkSujet.StatutRtk.GPS -> bloque("GPS_ONLY")
            ClientRtkSujet.StatutRtk.LOST -> bloque("RTK_LOST")
        }
    }

    /**
     * Barriere materielle logicielle du mode FLOAT+VISION.
     * Meme si l'appelant fournit une commande de mouvement, les trois axes de
     * translation sont annules avant l'envoi au pilote.
     */
    fun limiterFloatVision(axes: Axes): Axes = Axes(
        vx = 0f,
        vy = 0f,
        vz = 0f,
        yawRate = 0f,
        gimbalPitch = axes.gimbalPitch.coerceIn(-GIMBAL_FLOAT_MAX_DEG, GIMBAL_FLOAT_MAX_DEG),
        gimbalYaw = axes.gimbalYaw.coerceIn(-GIMBAL_FLOAT_MAX_DEG, GIMBAL_FLOAT_MAX_DEG)
    )

    private fun bloque(raison: String) = Verdict(
        mode = Mode.BLOQUE,
        raison = raison,
        translationAutorisee = false,
        altitudeAutorisee = false,
        yawAutorise = false,
        gimbalAutorise = false
    )

    companion object {
        const val AGE_MAX_S = 0.30
        const val CADENCE_MIN_HZ = 5.0
        const val RTCM_AGE_MAX_MS = 2_000L
        const val FLOAT_HACC_MAX_M = 0.50
        const val CONFIANCE_YOLO_MIN = 0.65f
        const val HAUTEUR_BOITE_MIN = 0.06f
        const val HAUTEUR_BOITE_MAX = 0.70f
                const val GIMBAL_FLOAT_MAX_DEG = 15f
    }
}
