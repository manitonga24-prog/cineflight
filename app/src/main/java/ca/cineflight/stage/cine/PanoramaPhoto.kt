package ca.cineflight.stage.cine

import kotlin.math.abs

/**
 * PanoramaPhoto : module de PRISE DE VUE panoramique (niveau 1, sans assemblage).
 *
 * Logique SEQUENTIELLE (machine a etats), distincte du suivi video continu :
 *   aller a l'angle -> stabiliser -> photo -> angle suivant -> ... -> termine.
 *
 * Deux modes :
 *   - PAYSAGE  : le drone reste sur place et pivote sur lui-meme (centre = drone).
 *   - SOUVENIR : le drone orbite autour d'un sujet verrouille (centre = sujet).
 *
 * v1 : prend la grille de photos proprement. L'ASSEMBLAGE est externe.
 * Aucune IA, aucun reseau. Pur calcul + sequencement.
 */

enum class PanoramaMode(val nomFr: String) {
    PAYSAGE("Panorama paysage"),       // centre = position du drone
    SOUVENIR("Panorama souvenir")      // centre = sujet verrouille
}

/**
 * Un preset = une grille (colonnes x rangees) + nadir + delais.
 * - colonnes : nombre de directions horizontales (yaw) sur 360 deg.
 * - rangees  : nombre d'inclinaisons gimbal (pitch).
 * - pitchHautDeg / pitchBasDeg : bornes d'inclinaison de la grille (gimbal).
 * - nadir    : ajoute une photo droit vers le bas (-90 deg) pour boucher le dessous.
 * - delaiStabMs : attente APRES s'etre positionne, AVANT la photo (anti-flou).
 * - delaiApresMs: attente APRES la photo, avant de bouger (laisse ecrire le fichier).
 */
enum class PanoramaPreset(
    val nomFr: String,
    val accroche: String,
    val colonnes: Int,
    val rangees: Int,
    val pitchHautDeg: Float,
    val pitchBasDeg: Float,
    val nadir: Boolean,
    val delaiStabMs: Long,
    val delaiApresMs: Long
) {
    RAPIDE("Rapide", "Moins de photos, plus vite",
        colonnes = 6, rangees = 2, pitchHautDeg = 0f, pitchBasDeg = -40f,
        nadir = false, delaiStabMs = 1500, delaiApresMs = 800),
    SIMPLE("Simple", "Bon equilibre qualite / duree",
        colonnes = 8, rangees = 3, pitchHautDeg = 30f, pitchBasDeg = -50f,
        nadir = true, delaiStabMs = 2000, delaiApresMs = 1000),
    HAUTE_QUALITE("Haute qualite", "Couverture maximale",
        colonnes = 12, rangees = 4, pitchHautDeg = 30f, pitchBasDeg = -60f,
        nadir = true, delaiStabMs = 2500, delaiApresMs = 1200);

    /** Nombre total de photos de ce preset (grille + nadir eventuel). */
    fun nbPhotos(): Int = colonnes * rangees + (if (nadir) 1 else 0)
}

/**
 * Un pas du panorama : une cible (yaw absolu + pitch gimbal) ou la photo sera prise.
 * yawDeg : cap absolu vise (0..360). pitchDeg : inclinaison gimbal (-90..+30).
 */
data class PanoramaStep(
    val index: Int,            // 0-based
    val total: Int,
    val yawDeg: Float,         // cap absolu a atteindre
    val pitchDeg: Float,       // inclinaison gimbal
    val estNadir: Boolean = false
)

/** Construit la liste ordonnee des pas (la grille) pour un preset donne. */
object PanoramaGrille {
    fun construire(preset: PanoramaPreset, capDepartDeg: Float = 0f): List<PanoramaStep> {
        val pas = ArrayList<PanoramaStep>()
        val total = preset.nbPhotos()
        val pasYaw = 360f / preset.colonnes
        // rangees : du haut vers le bas, reparties entre pitchHaut et pitchBas
        val pitchs = if (preset.rangees == 1) {
            listOf((preset.pitchHautDeg + preset.pitchBasDeg) / 2f)
        } else {
            (0 until preset.rangees).map { r ->
                preset.pitchHautDeg + (preset.pitchBasDeg - preset.pitchHautDeg) *
                    (r.toFloat() / (preset.rangees - 1))
            }
        }
        // Capture "row by row" : pour chaque rangee (pitch), faire le tour complet (colonnes).
        var idx = 0
        for (pitch in pitchs) {
            for (col in 0 until preset.colonnes) {
                val yaw = (capDepartDeg + col * pasYaw) % 360f
                pas.add(PanoramaStep(idx, total, yaw, pitch))
                idx++
            }
        }
        // Nadir : une photo droit vers le bas, cap inchange.
        if (preset.nadir) {
            pas.add(PanoramaStep(idx, total, capDepartDeg % 360f, -90f, estNadir = true))
        }
        return pas
    }
}

/** Etat courant de la machine. */
enum class PanoramaPhase { POSITIONNEMENT, STABILISATION, PHOTO, ATTENTE_APRES, TERMINE, ANNULE }

/**
 * Machine a etats du panorama. Pure logique : ne pilote rien directement.
 * L'appelant (cote app) lui fournit le cap reel et appelle avancer() regulierement ;
 * elle renvoie l'ACTION a effectuer (tourner vers, regler gimbal, prendre photo, attendre).
 */
class PanoramaStateMachine(
    private val pas: List<PanoramaStep>,
    private val preset: PanoramaPreset,
    private val toleranceYawDeg: Float = 3f,  // marge pour considerer le cap "atteint"
    // Delai MAX pour atteindre un cap. Passe ce temps sans converger (vent, derive,
    // cap fige/NaN...), on prend la photo au cap le plus proche atteint au lieu de
    // rester bloque indefiniment (garde anti-boucle-infinie).
    private val timeoutPositionnementMs: Long = 12000L
) {
    var phase: PanoramaPhase = PanoramaPhase.POSITIONNEMENT; private set
    var indexCourant: Int = 0; private set
    private var tDebutPhaseMs: Long = 0L
    // Horodatage d'entree en POSITIONNEMENT (pour le timeout anti-blocage). -1 = pas encore marque.
    private var tDebutPositionnementMs: Long = -1L

    /** Action a executer, renvoyee par avancer(). */
    sealed class Action {
        /** Tourner le drone vers ce cap absolu + regler le gimbal a ce pitch. */
        data class AllerVers(val yawDeg: Float, val pitchDeg: Float) : Action()
        /** Ne rien faire, juste attendre (stabilisation ou ecriture fichier). */
        object Patienter : Action()
        /** Declencher la photo maintenant. */
        object PrendrePhoto : Action()
        /** Panorama termine. */
        data class Termine(val nbPhotos: Int) : Action()
        /** Panorama annule. */
        object Annule : Action()
    }

    val pasCourant: PanoramaStep? get() = pas.getOrNull(indexCourant)
    val total: Int get() = pas.size

    fun annuler() { phase = PanoramaPhase.ANNULE }

    /**
     * Fait avancer la machine. A appeler regulierement (ex. ~5 Hz).
     * @param capActuelDeg cap reel du drone (depuis la telemetrie)
     * @param maintenantMs horodatage courant (System.currentTimeMillis())
     */
    fun avancer(capActuelDeg: Float, maintenantMs: Long): Action {
        if (phase == PanoramaPhase.ANNULE) return Action.Annule
        if (phase == PanoramaPhase.TERMINE) return Action.Termine(pas.size)
        val step = pas.getOrNull(indexCourant) ?: run {
            phase = PanoramaPhase.TERMINE
            return Action.Termine(pas.size)
        }

        return when (phase) {
            PanoramaPhase.POSITIONNEMENT -> {
                if (tDebutPositionnementMs < 0L) tDebutPositionnementMs = maintenantMs
                val ecart = ecartAngulaire(capActuelDeg, step.yawDeg)
                val timeout = maintenantMs - tDebutPositionnementMs >= timeoutPositionnementMs
                if (abs(ecart) <= toleranceYawDeg || timeout) {
                    // cap atteint (ou timeout : on ne reste JAMAIS bloque) -> stabilisation
                    phase = PanoramaPhase.STABILISATION
                    tDebutPhaseMs = maintenantMs
                    tDebutPositionnementMs = -1L   // reset pour le pas suivant
                    Action.Patienter
                } else {
                    // continuer a se tourner vers la cible
                    Action.AllerVers(step.yawDeg, step.pitchDeg)
                }
            }
            PanoramaPhase.STABILISATION -> {
                if (maintenantMs - tDebutPhaseMs >= preset.delaiStabMs) {
                    phase = PanoramaPhase.PHOTO
                    Action.PrendrePhoto
                } else Action.Patienter
            }
            PanoramaPhase.PHOTO -> {
                // la photo vient d'etre demandee : passer a l'attente apres-photo
                phase = PanoramaPhase.ATTENTE_APRES
                tDebutPhaseMs = maintenantMs
                Action.Patienter
            }
            PanoramaPhase.ATTENTE_APRES -> {
                if (maintenantMs - tDebutPhaseMs >= preset.delaiApresMs) {
                    // pas suivant
                    indexCourant++
                    if (indexCourant >= pas.size) {
                        phase = PanoramaPhase.TERMINE
                        Action.Termine(pas.size)
                    } else {
                        phase = PanoramaPhase.POSITIONNEMENT
                        tDebutPositionnementMs = -1L   // repart a zero pour le nouveau cap
                        Action.AllerVers(pas[indexCourant].yawDeg, pas[indexCourant].pitchDeg)
                    }
                } else Action.Patienter
            }
            else -> Action.Patienter
        }
    }

    companion object {
        /** Ecart angulaire signe le plus court entre deux caps (-180..+180). */
        fun ecartAngulaire(de: Float, vers: Float): Float {
            var d = (vers - de) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}

