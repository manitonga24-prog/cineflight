package ca.cineflight.stage.sport.soccer

/**
 * SoccerRailProfile — profil de mission SPORT EXTERIEUR — RAIL CAMERA, exporte par
 * CineFlight Web et lu cote Android (Phase 3).
 *
 * Modele PUR (aucun SDK, aucun JSON ici) : le parsing/validation vit dans
 * [SoccerRailProfileParser]. Ce type ne porte que des donnees validees.
 *
 * Exemple de source (spec) :
 * {
 *   "mode": "SOCCER_RAIL",
 *   "rail_start": { "lat": 45.0, "lon": -73.0 },
 *   "rail_end":   { "lat": 45.0, "lon": -72.999 },
 *   "altitude_agl_m": 25,
 *   "max_speed_mps": 2,
 *   "safe_position": 0.5,
 *   "spectator_zones": [],
 *   "takeoff_zone": {},
 *   "safety_verdict": { "approved": true, "reason": "" }
 * }
 */
data class SoccerRailProfile(
    /** Mode de mission ; attendu = "SOCCER_RAIL". */
    val mode: String,
    /** Rail autorise (extremites), directement utilisable par SoccerRailPlanner. */
    val rail: DroneRail,
    /** Altitude AGL fixe pour la V1 (metres). */
    val altitudeAglM: Double,
    /** Vitesse horizontale maximale autorisee (m/s). */
    val maxSpeedMps: Double,
    /** Position de securite sur le rail [0f,1f] (0.5 = centre). */
    val safePosition: Float,
    /** Zones spectateurs (polygones de points) — exclusion, pas obstacles mobiles. */
    val spectatorZones: List<List<RailPoint>>,
    /** Zone de decollage/atterrissage (polygone), possiblement vide en V1. */
    val takeoffZone: List<RailPoint>,
    /** Contour du TERRAIN (polygone de sommets). Zone dans laquelle le drone est
     *  autorise a aller (georeperage). Vide = pas de terrain defini -> le drone reste
     *  limite au rail. */
    val terrain: List<RailPoint> = emptyList(),
    /** Verdict de securite produit par le Web (obligatoire). */
    val safetyVerdict: SafetyVerdict,
) {
    /**
     * Verdict de securite calcule cote Web. Une mission NON approuvee ne doit jamais
     * autoriser le moindre mouvement reel (le respect est a la charge de l'appelant).
     */
    data class SafetyVerdict(
        val approved: Boolean,
        val reason: String,
    )
}
