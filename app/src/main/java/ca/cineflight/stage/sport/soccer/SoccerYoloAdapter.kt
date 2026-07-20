package ca.cineflight.stage.sport.soccer

/**
 * SoccerYoloAdapter — convertit les boites YOLO REELLES du projet (le format UDP de
 * RecepteurBoxes) vers le format neutre [SoccerDetectionFrame] attendu par l'estimateur.
 *
 * Il n'y a AUCUNE modification de YOLO : on adapte seulement, cote consommateur.
 *
 * FORMAT SOURCE (RecepteurBoxes.Box) :
 *   x, y = coin HAUT-GAUCHE normalise (0..1) ; w, h = largeur/hauteur normalisees ;
 *   conf = confiance ; sel = personne selectionnee (mise en evidence).
 *   >>> Il n'y a PAS de label de classe dans ce flux. <<<
 *
 * CONSEQUENCE (V1) : faute de label, on traite CHAQUE boite comme un JOUEUR ("person").
 * L'estimateur utilisera donc la mediane des joueurs (le fallback prevu par la spec
 * quand aucun ballon fiable n'est disponible). Le jour ou le flux YOLO portera une
 * classe "ballon", il suffira d'etendre ce mapping — l'estimateur, lui, ne bouge pas.
 *
 * Ce type volontairement DECOUPLE : il prend des primitives (coin, taille, conf),
 * pas le type RecepteurBoxes.Box, pour rester pur et testable sans dependance control.
 * L'appelant (MainActivity) fait la petite conversion Box -> ces primitives.
 */
object SoccerYoloAdapter {

    /** Label neutre attribue a toutes les detections en l'absence de classe YOLO. */
    private const val LABEL_JOUEUR = "person"

    /**
     * Une detection source minimale (coin haut-gauche + taille, normalises 0..1).
     * Copie neutre d'une RecepteurBoxes.Box, sans dependre de son type.
     */
    data class BoxSource(
        val x: Float, val y: Float,
        val w: Float, val h: Float,
        val conf: Float,
    )

    /**
     * Construit une [SoccerDetectionFrame] a partir des boites source.
     * @param boxes detections normalisees (coin haut-gauche + taille).
     * @param timestampMs horodatage de la frame.
     */
    fun toFrame(boxes: List<BoxSource>, timestampMs: Long): SoccerDetectionFrame {
        val objets = boxes.map { b ->
            SoccerDetectedObject(
                label = LABEL_JOUEUR,
                confidence = b.conf,
                // coin haut-gauche -> centre ; borne par prudence.
                centerXNormalized = (b.x + b.w / 2f).coerceIn(0f, 1f),
                centerYNormalized = (b.y + b.h / 2f).coerceIn(0f, 1f),
                widthNormalized = b.w.coerceIn(0f, 1f),
                heightNormalized = b.h.coerceIn(0f, 1f),
            )
        }
        return SoccerDetectionFrame(timestampMs = timestampMs, objects = objets)
    }
}
