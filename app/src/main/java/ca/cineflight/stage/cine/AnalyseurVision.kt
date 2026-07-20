package ca.cineflight.stage.cine

/**
 * AnalyseurVision : couche PREMIUM optionnelle. Un modele multimodal "regarde" une
 * image de la scene et suggere des recettes. Il NE pilote jamais : il alimente le
 * catalogue, exactement comme l'analyse YOLO ou un choix manuel.
 *
 * DISCIPLINE :
 *   - Le LLM renvoie des SUGGESTIONS (noms de recettes) + une description honnete.
 *   - Ces suggestions passent ensuite par Catalogue -> Grammaire -> Validateur.
 *   - Aucune trajectoire, aucune coordonnee ne vient du LLM.
 *
 * DEPENDANCE RESEAU :
 *   - Un vrai analyseur cloud exige Internet. Sur un sommet sans reseau, l'app DOIT
 *     rester utilisable : l'appelant bascule alors sur l'analyse YOLO / le choix manuel.
 *   - disponible() permet de savoir a l'avance si l'analyse vision est possible.
 *
 * REMPLACEMENT :
 *   - L'app fournit l'implementation concrete (cloud, local...). Le reste du moteur
 *     ne connait que cette interface. Meme pattern que PontDji isolant le SDK.
 */
interface AnalyseurVision {
    /** true si l'analyse est possible maintenant (reseau ok, cle configuree, etc.). */
    fun disponible(): Boolean

    /**
     * Analyse une ou plusieurs images JPEG (base64) et renvoie un rapport.
     * Suspend : l'appel reseau se fait hors du thread UI.
     * En cas d'echec (reseau, quota...), renvoie null -> l'appelant fait le fallback.
     */
    suspend fun analyser(imagesJpegBase64: List<String>): RapportVision?
}

/**
 * RapportVision : ce que le LLM vision a "vu" et suggere.
 * Les champs descriptifs sont des FAITS percus (honnetes, affichables tels quels).
 * recettes = noms internes de recettes (cles), traduits par Catalogue.parRecetteCle.
 */
data class RapportVision(
    val typeLieu: String,            // ex "paysage", "interieur", "scene urbaine"
    val ambiance: String,            // ex "grandiose", "intime", "festive"
    val elements: List<String>,      // ex ["lac", "montagne", "lumiere douce"]
    val recettesCles: List<String>,  // ex ["PANORAMA_EPIQUE", "IMMERSION_NATURE"]
    val confiance: Float = 0.8f      // 0..1, indicatif
) {
    /** Phrase "Je vois ..." honnete, construite a partir des elements percus. */
    fun resume(): String {
        val els = if (elements.isEmpty()) ambiance else elements.joinToString(", ")
        return "Je vois : $els."
    }
}

/**
 * Implementation FACTICE : aucun reseau, renvoie un resultat plausible.
 * Permet de tester tout le flux (capture -> analyse -> suggestions -> vol) sans cle API.
 * A REMPLACER par l'implementation reelle quand le fournisseur sera choisi.
 */
class AnalyseurVisionFactice : AnalyseurVision {
    override fun disponible(): Boolean = true
    override suspend fun analyser(imagesJpegBase64: List<String>): RapportVision? {
        // resultat fixe plausible (paysage) pour valider le flux de bout en bout
        return RapportVision(
            typeLieu = "paysage",
            ambiance = "grandiose",
            elements = listOf("grand espace ouvert", "relief", "lumiere douce"),
            recettesCles = listOf("PANORAMA_EPIQUE", "REVELATION_MAGIQUE", "IMMERSION_NATURE"),
            confiance = 0.5f   // factice : confiance volontairement basse
        )
    }
}

