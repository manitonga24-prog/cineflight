package ca.cineflight.stage.sport.soccer

/**
 * CapaciteZoom — CAPACITE DE ZOOM de la camera (pur, aucun SDK/Android).
 *
 * Distinction VOLONTAIRE et importante (doctrine CineFlight) : un "zoom" n'est pas l'autre.
 * Le realisateur ne pilote un axe zoom QUE pour un vrai zoom OPTIQUE CONTINU, jamais pour
 * du zoom numerique (qui degrade l'image) ni pour un simple changement de camera tele
 * (discret, pas un objectif a focale variable).
 *
 *   ZOOM_NONE               : focale fixe, aucun zoom.
 *   ZOOM_DIGITAL_ONLY       : zoom numerique seulement (recadrage/upscale) -> NE PAS piloter.
 *                             Cas du DJI Mini 4 Pro (24 mm fixe + zoom numerique 1-3x/4x).
 *   ZOOM_OPTICAL_CONTINUOUS : vrai zoom optique a focale variable -> SEUL cas ou l'axe zoom
 *                             du Director est actif (ajustement fin).
 *   CAMERA_SWITCH_TELE      : plusieurs cameras dont une teleobjectif -> changement de camera,
 *                             PAS un zoom continu -> ne pas piloter comme un axe continu.
 */
enum class CapaciteZoom {
    ZOOM_NONE,
    ZOOM_DIGITAL_ONLY,
    ZOOM_OPTICAL_CONTINUOUS,
    CAMERA_SWITCH_TELE;

    /** true UNIQUEMENT si un axe zoom continu peut etre pilote par le realisateur. */
    fun pilotableEnContinu(): Boolean = this == ZOOM_OPTICAL_CONTINUOUS

    companion object {
        /**
         * Detection dynamique (V1) a partir des drapeaux de capacite rapportes par le SDK.
         * On distingue explicitement optique continu / numerique / switch tele. Prudent :
         * en cas de doute, on NE declare PAS de zoom optique (fail-safe : pas de pilotage).
         *
         * @param optiqueContinu la camera declare un zoom optique a focale variable continu.
         * @param numerique      la camera declare un zoom numerique.
         * @param cameraTele     un module/camera teleobjectif est disponible (switch).
         */
        fun detecter(optiqueContinu: Boolean, numerique: Boolean, cameraTele: Boolean): CapaciteZoom = when {
            optiqueContinu -> ZOOM_OPTICAL_CONTINUOUS
            cameraTele -> CAMERA_SWITCH_TELE
            numerique -> ZOOM_DIGITAL_ONLY
            else -> ZOOM_NONE
        }

        /** Capacite du DJI Mini 4 Pro : 24 mm fixe + zoom numerique -> DIGITAL_ONLY. */
        val MINI_4_PRO: CapaciteZoom = ZOOM_DIGITAL_ONLY
    }
}
