package ca.cineflight.stage.sport.soccer

/**
 * SoccerDetectionFrame — entree NORMALISEE de l'estimateur d'action soccer.
 *
 * C'est un format d'adaptation : YOLO n'est PAS modifie. Un petit adaptateur
 * (cote appelant) convertit les detections brutes YOLO vers ce format neutre,
 * ce qui garde [SoccerActionEstimator] entierement pur et testable sur la JVM.
 *
 * CONVENTION : toutes les coordonnees et tailles sont NORMALISEES dans [0f, 1f]
 * (fraction de la largeur/hauteur de l'image). L'estimateur re-borne par prudence,
 * donc une valeur hors plage ne provoque jamais de resultat aberrant.
 */
data class SoccerDetectionFrame(
    /** Horodatage de la frame (ms, horloge monotone de preference). */
    val timestampMs: Long,
    /** Objets detectes dans cette frame (peut etre vide). */
    val objects: List<SoccerDetectedObject>,
)

/**
 * SoccerDetectedObject — une detection YOLO neutre.
 *
 * @param label libelle de classe YOLO (ex. "sports ball", "person").
 * @param confidence confiance de la detection dans [0f, 1f].
 * @param centerXNormalized centre horizontal dans [0f, 1f] (0 = gauche, 1 = droite).
 * @param centerYNormalized centre vertical dans [0f, 1f] (0 = haut, 1 = bas).
 * @param widthNormalized largeur de la boite dans [0f, 1f].
 * @param heightNormalized hauteur de la boite dans [0f, 1f].
 */
data class SoccerDetectedObject(
    val label: String,
    val confidence: Float,
    val centerXNormalized: Float,
    val centerYNormalized: Float,
    val widthNormalized: Float,
    val heightNormalized: Float,
)
