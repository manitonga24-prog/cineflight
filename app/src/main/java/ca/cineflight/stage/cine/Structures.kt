package ca.cineflight.stage.cine

/**
 * Socle du moteur "assistant realisateur aerien".
 * AUCUNE dependance au SDK DJI ni a l'app : pur Kotlin, testable hors drone.
 *
 * Pipeline : Recette -> Grammaire -> Sequence brute -> Validateur -> Sequence executable + Pastille
 *
 * Ce fichier : les structures de base (enums + data classes).
 */

// --- Les mouvements reels de l'app (codes alignes sur mouvementActuel dans MainActivity) ---
enum class Mouvement(val code: Int, val nomFr: String) {
    STATIQUE(0, "Statique"),
    ORBITE(1, "Orbite"),
    TRAVELLING(2, "Travelling"),
    REVELATION(3, "Revelation"),
    APPROCHE(4, "Approche"),
    SPOTLIGHT(5, "Spotlight"),
    RECUL(6, "Recul"),
    SUIVI(7, "Suivi");

    /** Mouvements qui exigent un point GPS ancre (orbite tourne autour d'une position). */
    val exigeGps: Boolean get() = this == ORBITE
    /** Mouvement a risque arriere : le drone se deplace vers l'arriere sans voir. */
    val estReculAveugle: Boolean get() = this == RECUL
    /** Mouvement SUR PLACE : reste dans une zone bornee (orbite = rayon fixe, statique,
     *  spotlight). Prolonger sa duree est sur ; une translation, elle, avancerait plus loin. */
    val enPlace: Boolean get() = this == ORBITE || this == STATIQUE || this == SPOTLIGHT
}

// --- Les plans reels (valeur cibleHPlan dans l'app) ---
enum class Plan(val cibleHPlan: Float, val nomFr: String) {
    GROS(0.75f, "Gros"),
    AMERICAIN(0.55f, "Americain"),
    PIED(0.40f, "Pied"),
    ENSEMBLE(0.25f, "Ensemble")
}

// --- Echelle de vitesse normalisee de la grammaire ---
enum class Vitesse(val nomFr: String) {
    LENTE("Lente"),
    MODEREE("Moderee"),
    RAPIDE("Rapide")
}

// --- Les types de scene (lignes de la grammaire) ---
enum class Scene(val nomFr: String) {
    DANSEUR_SOLO("Danseur solo"),
    GROUPE_DANSE("Groupe de danse"),
    CHANTEUR("Chanteur"),
    GROUPE_MUSIQUE("Groupe de musique"),
    MARIAGE("Mariage"),
    ATHLETE("Athlete"),
    VEHICULE("Vehicule"),
    PAYSAGE("Paysage"),
    DISCOURS("Discours"),
    FAMILLE("Famille / Groupe")
}

// --- Les effets / intentions (colonnes de la grammaire) ---
enum class Effet(val nomFr: String) {
    ENERGIE("Energie"),
    EMOTION("Emotion"),
    GRANDEUR("Grandeur"),
    INTIMITE("Intimite"),
    MYSTERE("Mystere"),
    LIBERTE("Liberte"),
    PUISSANCE("Puissance"),
    CELEBRATION("Celebration")
}

// --- Signature cinema : petite variation appliquee a la sequence brute pour
//     differencier des recettes qui partageraient sinon la meme choregraphie.
//     N'ajoute AUCUN pas (donc ne touche pas la repartition des durees) :
//     elle ne fait que remplacer le mouvement d'un pas existant. ---
enum class Signature {
    AUCUNE,          // sequence brute de la grammaire (reference)
    ORBITE_FINALE,   // dernier pas -> ORBITE (tour final du sujet / du bati)
    ORBITE_TETE,     // premier pas -> ORBITE (on tourne d'abord, puis on enchaine)
    FINAL_STATIQUE   // dernier pas -> STATIQUE (plan tenu, ex. horizon)
}

// --- Style d'allongement de la duree, choisi PAR PLAN (a cote du curseur de duree). ---
enum class StyleDuree {
    NORMAL,     // le facteur n'allonge que les temps sur place (orbite/statique/spotlight)
    PLAN_TENU,  // le temps ajoute devient un plan fixe tenu a la fin (sur, aucun deplacement en plus)
    RALENTI     // tout ralenti : meme trajet, plus lent (vitesse divisee par le facteur)
}

// --- Espace mesure (du rapport de reperage, ou estime par l'utilisateur) ---
enum class Espace(val rang: Int) {
    RESTREINT(0), MOYEN(1), LARGE(2);
    fun auMoins(autre: Espace) = this.rang >= autre.rang
}

// --- Capacite d'evitement d'obstacles, deduite du modele (cf. CapacitesDrone existant) ---
enum class Evitement { AUCUN, PARTIEL, COMPLET }

/**
 * Profil drone : ce que le moteur doit connaitre des capacites materielles.
 * Le plafond de recul depend directement de l'evitement.
 */
data class ProfilDrone(
    val modele: String,
    val evitement: Evitement
) {
    val plafondReculMps: Float get() = when (evitement) {
        Evitement.COMPLET -> 2.0f
        Evitement.PARTIEL -> 1.2f
        Evitement.AUCUN   -> 0.8f
    }
    /** Sans evitement, le recul a l'aveugle doit etre evite si l'arriere est inconnu. */
    val reculAveugleRisque: Boolean get() = evitement == Evitement.AUCUN
}

/**
 * Un pas de la sequence : un mouvement, sa vitesse, son plan, sa duree.
 * rayonM / distanceM servent aux mouvements amples (orbite, travelling) pour le controle d'espace.
 */
data class Pas(
    val mouvement: Mouvement,
    val vitesse: Vitesse,
    val plan: Plan,
    val dureeS: Int,
    val rayonM: Float = 0f,      // pour orbite : rayon ; 0 si non applicable
    val distanceM: Float = 0f,   // pour travelling/recul/approche : amplitude ; 0 si non applicable
    val facteurVitesse: Float = 1f  // RALENTI : multiplie la vitesse REELLE du pas (<=1 = plus lent)
)

/** Une sequence = la suite ordonnee de pas produite par la grammaire. */
data class Sequence(val pas: List<Pas>) {
    val dureeTotaleS: Int get() = pas.sumOf { it.dureeS }
    fun exigeGps(): Boolean = pas.any { it.mouvement.exigeGps }
}

/**
 * Contexte de validation : tout ce que le validateur doit connaitre pour decider.
 * Aucun capteur lu ici : on lui PASSE l'etat, il rend un verdict (fonction pure).
 */
data class ContexteValidation(
    val drone: ProfilDrone,
    val espace: Espace,
    val espaceArriereConnu: Boolean,  // false => recul aveugle a eviter sur drone sans evitement
    val gpsValide: Boolean,
    val batteriePct: Int,
    val hauteurMaxLegaleM: Float = 122f  // 400 pi au Canada
)

