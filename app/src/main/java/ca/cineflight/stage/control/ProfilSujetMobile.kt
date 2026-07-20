package ca.cineflight.stage.control

import android.content.Context

/**
 * Profils de mouvement de CineFlight RTK Fusion V4.
 *
 * Ces profils configurent le diagnostic et la prediction passive. Ils ne
 * permettent jamais, a eux seuls, d'envoyer une commande au drone.
 */
enum class ProfilSujetMobile(
    val code: String,
    val libelle: String,
    val vitesseMaxMps: Double,
    val distanceCibleM: Double,
    val limiteMinM: Double,
    val prudenceM: Double,
    val horizonMaxS: Double,
    val delaiReactionS: Double,
    val classeVision: String
) {
    MARCHE("MARCHE", "Marche", 4.0, 8.0, 5.0, 8.0, 1.20, 0.45, "PERSON"),
    COURSE("COURSE", "Course a pied", 10.0, 12.0, 7.0, 11.0, 1.35, 0.40, "PERSON"),
    VELO("VELO", "Velo", 22.0, 18.0, 10.0, 16.0, 1.50, 0.35, "BICYCLE"),
    SKI("SKI", "Ski", 36.0, 22.0, 12.0, 20.0, 1.60, 0.35, "PERSON"),
    BATEAU("BATEAU", "Bateau", 32.0, 25.0, 15.0, 23.0, 1.80, 0.40, "BOAT"),
    MOTO("MOTO", "Moto", 55.0, 30.0, 18.0, 28.0, 1.80, 0.30, "MOTORCYCLE"),
    AUTO("AUTO", "Auto", 60.0, 35.0, 22.0, 32.0, 1.90, 0.30, "CAR"),
    AUTO_COURSE("AUTO_COURSE", "Auto de course", 95.0, 50.0, 30.0, 45.0, 2.00, 0.25, "CAR"),
    PERSONNALISE("PERSONNALISE", "Personnalise", 45.0, 25.0, 15.0, 22.0, 1.60, 0.35, "OBJECT");

    fun configurationPrediction(): DiagnosticPredictionRtk.Configuration {
        val accelerationMax = when (this) {
            MARCHE -> 5.0
            COURSE -> 9.0
            VELO -> 12.0
            SKI -> 14.0
            BATEAU -> 8.0
            MOTO -> 18.0
            AUTO -> 16.0
            AUTO_COURSE -> 25.0
            PERSONNALISE -> 15.0
        }
        val accelerationModele = when (this) {
            MARCHE -> 0.30
            COURSE -> 0.55
            VELO -> 0.70
            SKI -> 0.80
            BATEAU -> 0.45
            MOTO -> 1.00
            AUTO -> 0.90
            AUTO_COURSE -> 1.20
            PERSONNALISE -> 0.70
        }
        val seuilEntreeMouvement = when (this) {
            MARCHE -> 0.30
            COURSE -> 0.45
            VELO -> 0.60
            SKI -> 0.70
            BATEAU -> 0.45
            MOTO -> 0.80
            AUTO -> 0.80
            AUTO_COURSE -> 1.00
            PERSONNALISE -> 0.50
        }
        val seuilEntreeImmobile = when (this) {
            MARCHE -> 0.15
            COURSE -> 0.20
            VELO -> 0.25
            SKI -> 0.30
            BATEAU -> 0.35
            MOTO -> 0.35
            AUTO -> 0.40
            AUTO_COURSE -> 0.50
            PERSONNALISE -> 0.25
        }
        val confirmationsImmobile = when (this) {
            MARCHE -> 4
            COURSE -> 5
            VELO -> 6
            SKI -> 6
            BATEAU -> 10
            MOTO -> 8
            AUTO -> 10
            AUTO_COURSE -> 12
            PERSONNALISE -> 6
        }
        val confirmationsMouvement = when (this) {
            MARCHE -> 3
            COURSE -> 3
            VELO -> 3
            SKI -> 3
            BATEAU -> 4
            MOTO -> 3
            AUTO -> 3
            AUTO_COURSE -> 3
            PERSONNALISE -> 3
        }

        return DiagnosticPredictionRtk.Configuration(
            ageMaxS = 0.50,
            agePurgeS = 3.0,
            interruptionMaxS = 1.25,
            vitesseMaxMps = vitesseMaxMps,
            accelerationMaxMps2 = accelerationMax,
            accelerationModeleMinMps2 = accelerationModele,
            distancePredictionMaxM = (vitesseMaxMps * horizonMaxS * 1.35).coerceAtLeast(20.0),
            delaiPipelineS = 0.12,
            delaiReactionDroneS = delaiReactionS,
            horizonMinS = 0.20,
            horizonMaxS = horizonMaxS,
            ageControleMaxS = 0.30,
            ageControleVirageMaxS = 0.25,
            stabiliteFixControleMinS = 2.0,
            echantillonsFixControleMin = 10,
            vitesseEntreeMouvementMps = seuilEntreeMouvement,
            vitesseEntreeImmobileMps = seuilEntreeImmobile,
            confirmationsMouvement = confirmationsMouvement,
            confirmationsImmobile = confirmationsImmobile
        )
    }

    fun configurationSuivi(): SuiviSujetRtk.Config = SuiviSujetRtk.Config(
        distanceCibleM = distanceCibleM,
        limiteMinM = limiteMinM,
        prudenceM = prudenceM,
        ageMaxS = 0.30
    )

    companion object {
        private const val PREFS = "cineflight"
        private const val KEY = "rtk_v4_subject_profile"

        fun depuisCode(code: String?): ProfilSujetMobile = values().firstOrNull {
            it.code.equals(code, ignoreCase = true)
        } ?: MARCHE

        fun charger(context: Context): ProfilSujetMobile {
            val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            return depuisCode(code)
        }

        fun estConfigure(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

        fun sauvegarder(context: Context, profil: ProfilSujetMobile) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, profil.code)
                .apply()
        }
    }
}
