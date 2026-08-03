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
    // ⚠ RECOUVREMENT (correctif 2026-07-26) : 6 colonnes = 60° entre deux prises, or
    // l'objectif du Mini 4 Pro couvre ~82° -> il ne restait que ~20° de recouvrement.
    // L'assembleur ne trouvait pas assez de points communs et COLLAIT les images bout à
    // bout : coupure franche visible sur le panorama 12 photos du 2026-07-26.
    // 8 colonnes = 45° d'écart, soit ~37° de recouvrement (≈45 %) : marge confortable.
    RAPIDE("Rapide", "Moins de photos, plus vite",
        colonnes = 8, rangees = 2, pitchHautDeg = 0f, pitchBasDeg = -40f,
        nadir = false, delaiStabMs = 1500, delaiApresMs = 800),
    SIMPLE("Simple", "Bon equilibre qualite / duree",
        colonnes = 8, rangees = 3, pitchHautDeg = 30f, pitchBasDeg = -50f,
        nadir = true, delaiStabMs = 2000, delaiApresMs = 1000),
    // ⚠ ORDRE DE DECLARATION = ordre d'affichage. Les presets sont ranges par NOMBRE DE
    // PHOTOS croissant (16, 25, 41, 49, 61) : c'est ce nombre qui decide de la duree de
    // vol et de la batterie, donc c'est la grandeur que l'utilisateur compare.
    // CIEL COMPLET (2026-07-27). Les presets ci-dessus s'arretent a +30 d'elevation : au
    // dela, rien n'est photographie et le serveur COMBLE. Mesure sur un vol reel :
    // 53 a 59 % de la sphere etait du remplissage, avec des trainees verticales bien
    // visibles des qu'on leve les yeux en casque.
    // La nacelle du Mini 4 Pro monte a +60 : une rangee la-haut, avec ~60 de champ
    // vertical, couvre jusqu'au zenith. 5 rangees de +60 a -60 plus le nadir.
    CIEL_COMPLET("Ciel complet", "Jusqu'au zenith — rien d'invente en haut",
        colonnes = 8, rangees = 5, pitchHautDeg = 60f, pitchBasDeg = -60f,
        nadir = true, delaiStabMs = 2000, delaiApresMs = 1000),
    HAUTE_QUALITE("Haute qualite", "Beaucoup de recouvrement, mais s'arrete a +30",
        colonnes = 12, rangees = 4, pitchHautDeg = 30f, pitchBasDeg = -60f,
        nadir = true, delaiStabMs = 2500, delaiApresMs = 1200),
    // CIEL COMPLET + HAUTE QUALITE : meme couverture verticale, mais 12 colonnes au lieu
    // de 8. Le pas passe de 45 a 30 degres, donc le recouvrement horizontal de 45 % a
    // 70 % : moins de risque de coupure franche, et plus de matiere pour l'assembleur.
    // 61 photos, environ 7 minutes de vol. C'est le preset le plus complet — et le plus
    // exigeant en batterie comme en carte.
    CIEL_COMPLET_HQ("Ciel complet — haute qualite", "Couverture totale, recouvrement maximal",
        colonnes = 12, rangees = 5, pitchHautDeg = 60f, pitchBasDeg = -60f,
        nadir = true, delaiStabMs = 2500, delaiApresMs = 1200);

    /** Nombre total de photos de ce preset (grille + nadir eventuel). */
    fun nbPhotos(): Int = colonnes * rangees + (if (nadir) 1 else 0)

    /**
     * Durée de vol estimée, en secondes.
     *
     * ⚠ CALIBRÉE SUR UN VOL RÉEL, pas devinée : relief du 2026-07-27, preset Simple,
     * 25 photos entre 13:09:50 et 13:13:28, soit 218 s — donc 8,7 s par cliché avec
     * 3 s de délais configurés. Le reste (5,7 s) est la rotation, la stabilisation
     * mécanique et l'écriture du fichier, qui ne dépendent pas du preset.
     * C'est une ESTIMATION pour choisir en connaissance de cause, pas une promesse :
     * le vent et la batterie allongent le vol.
     */
    fun dureeEstimeeS(): Int =
        (nbPhotos() * ((delaiStabMs + delaiApresMs) / 1000.0 + 5.7)).toInt()

    /** « 3 min 37 s » — pour l'affichage. */
    fun dureeTexte(): String {
        val s = dureeEstimeeS()
        return if (s < 60) "$s s" else "${s / 60} min ${"%02d".format(s % 60)} s"
    }
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

