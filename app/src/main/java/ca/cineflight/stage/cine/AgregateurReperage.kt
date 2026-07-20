package ca.cineflight.stage.cine

import kotlin.math.abs
import kotlin.math.sqrt
import android.content.Context
import ca.cineflight.stage.R

/**
 * AgregateurReperage : transforme une SUITE de frames YOLO en FAITS mesures stables.
 *
 * Principe (cf. document "Rapport de reperage") : on ne decide JAMAIS sur une frame.
 * On accumule pendant une fenetre, puis on calcule des statistiques robustes :
 *   - sujets        : mediane du compte de PERSONNES par frame
 *   - disposition   : ecart-type spatial des centres (groupes/disperses/isoles)
 *   - position      : barycentre moyen (gauche/centre/droite, haut/bas)
 *   - mouvement     : derive du barycentre entre 1er et dernier tiers
 *   - energie       : amplitude moyenne du deplacement inter-frames
 *   - espaceLibre   : proportion de la grille SANS detection sur la duree
 *   - confiance     : coherence du compte sur la fenetre (0..1)
 *
 * Chaque detection recue : [classe, cx, cy, fw, fh] normalises 0..1 (option B : toutes classes).
 *
 * Usage :
 *   val agg = AgregateurReperage()
 *   agg.demarrer()
 *   // a chaque frame (depuis YoloSuivi.onDetections) :
 *   agg.ajouterFrame(detections)
 *   // apres ~8-10 s :
 *   val rapport = agg.produire()
 */
class AgregateurReperage {

    private val CLASSE_PERSONNE = 0
    // classes considerees comme "sujets vivants" pour le compte principal
    private val CLASSES_SUJET = setOf(0, 15, 16, 17, 18, 19) // personne + animaux courants

    // une frame agregee : on garde l'essentiel pour les stats
    private data class FrameStat(
        val nbSujets: Int,
        val baryX: Float, val baryY: Float,   // barycentre des sujets (NaN si aucun)
        val etalement: Float,                  // ecart-type spatial des centres
        val cellulesOccupees: Set<Int>         // cellules de la grille 4x4 touchees
    )

    private val frames = ArrayList<FrameStat>()
    private var actif = false
    private val GRILLE = 4  // grille 4x4 pour l'espace libre

    fun demarrer() { frames.clear(); actif = true }
    fun arreter() { actif = false }
    val nbFramesCapturees: Int get() = frames.size

    /** Ajoute une frame de detections brutes [classe, cx, cy, fw, fh]. */
    fun ajouterFrame(detections: List<FloatArray>) {
        if (!actif) return
        // sujets = detections dont la classe est un sujet vivant
        val sujets = detections.filter { it[0].toInt() in CLASSES_SUJET }
        val nb = sujets.size

        var bx = Float.NaN; var by = Float.NaN; var etal = 0f
        if (nb > 0) {
            bx = sujets.map { it[1] }.average().toFloat()
            by = sujets.map { it[2] }.average().toFloat()
            if (nb > 1) {
                val varX = sujets.map { val d = it[1] - bx; d * d }.average()
                val varY = sujets.map { val d = it[2] - by; d * d }.average()
                etal = sqrt((varX + varY).toFloat())
            }
        }
        // cellules occupees par TOUTES les detections (pour l'espace libre)
        val cells = HashSet<Int>()
        for (d in detections) {
            val gx = (d[1] * GRILLE).toInt().coerceIn(0, GRILLE - 1)
            val gy = (d[2] * GRILLE).toInt().coerceIn(0, GRILLE - 1)
            cells.add(gy * GRILLE + gx)
        }
        frames.add(FrameStat(nb, bx, by, etal, cells))
    }

    /** Produit le rapport a partir des frames accumulees. */
    fun produire(): RapportReperage {
        if (frames.isEmpty()) {
            return RapportReperage(
                sujets = 0, disposition = Disposition.ISOLEE,
                position = Position.CENTRE, mouvement = MouvementScene.STATIQUE,
                energie = EnergieScene.FAIBLE, espaceLibre = Espace.MOYEN,
                confiance = 0f
            )
        }
        // --- sujets : mediane du compte ---
        val comptes = frames.map { it.nbSujets }.sorted()
        val sujetsMed = comptes[comptes.size / 2]

        // --- confiance : proportion de frames dont le compte == mediane (coherence) ---
        val coherentes = frames.count { it.nbSujets == sujetsMed }
        val confiance = coherentes.toFloat() / frames.size

        // --- disposition : etalement moyen (sur frames avec >=2 sujets) ---
        val etalements = frames.filter { it.nbSujets >= 2 }.map { it.etalement }
        val etalMoyen = if (etalements.isEmpty()) 0f else etalements.average().toFloat()
        val disposition = when {
            sujetsMed <= 1 -> Disposition.ISOLEE
            etalMoyen < 0.12f -> Disposition.GROUPEE
            etalMoyen > 0.28f -> Disposition.DISPERSEE
            else -> Disposition.GROUPEE
        }

        // --- position : barycentre moyen (frames avec sujets) ---
        val avecSujet = frames.filter { it.nbSujets > 0 && !it.baryX.isNaN() }
        val position = if (avecSujet.isEmpty()) Position.CENTRE else {
            val mx = avecSujet.map { it.baryX }.average().toFloat()
            when {
                mx < 0.33f -> Position.GAUCHE
                mx > 0.66f -> Position.DROITE
                else -> Position.CENTRE
            }
        }

        // --- mouvement : derive du barycentre entre 1er et dernier tiers ---
        val mouvement = calculerMouvement(avecSujet)

        // --- energie : amplitude moyenne du deplacement inter-frames du barycentre ---
        val energie = calculerEnergie(avecSujet)

        // --- espace libre : proportion de cellules JAMAIS occupees ---
        val occupeesGlobal = HashSet<Int>()
        frames.forEach { occupeesGlobal.addAll(it.cellulesOccupees) }
        val tauxOccup = occupeesGlobal.size.toFloat() / (GRILLE * GRILLE)
        val espace = when {
            tauxOccup < 0.20f -> Espace.LARGE      // peu de cellules touchees = beaucoup de vide
            tauxOccup < 0.45f -> Espace.MOYEN
            else -> Espace.RESTREINT
        }

        return RapportReperage(
            sujets = sujetsMed, disposition = disposition, position = position,
            mouvement = mouvement, energie = energie, espaceLibre = espace,
            confiance = confiance
        )
    }

    private fun calculerMouvement(avecSujet: List<FrameStat>): MouvementScene {
        if (avecSujet.size < 6) return MouvementScene.STATIQUE
        val t = avecSujet.size / 3
        val debut = avecSujet.take(t)
        val fin = avecSujet.takeLast(t)
        val x0 = debut.map { it.baryX }.average(); val y0 = debut.map { it.baryY }.average()
        val x1 = fin.map { it.baryX }.average();   val y1 = fin.map { it.baryY }.average()
        val dx = (x1 - x0).toFloat(); val dy = (y1 - y0).toFloat()
        val ampl = sqrt(dx * dx + dy * dy)
        if (ampl < 0.08f) return MouvementScene.STATIQUE
        return if (abs(dx) >= abs(dy)) MouvementScene.LATERAL else MouvementScene.VERTICAL
    }

    private fun calculerEnergie(avecSujet: List<FrameStat>): EnergieScene {
        if (avecSujet.size < 3) return EnergieScene.FAIBLE
        var somme = 0f; var cpt = 0
        for (i in 1 until avecSujet.size) {
            val a = avecSujet[i - 1]; val b = avecSujet[i]
            if (a.baryX.isNaN() || b.baryX.isNaN()) continue
            val dx = b.baryX - a.baryX; val dy = b.baryY - a.baryY
            somme += sqrt(dx * dx + dy * dy); cpt++
        }
        val moy = if (cpt == 0) 0f else somme / cpt
        return when {
            moy < 0.01f -> EnergieScene.FAIBLE
            moy < 0.03f -> EnergieScene.MODEREE
            else -> EnergieScene.ELEVEE
        }
    }
}

// --- Enums descriptifs du rapport (FAITS mesures, jamais interpretes) ---
enum class Disposition { GROUPEE, DISPERSEE, ISOLEE }
enum class Position { GAUCHE, CENTRE, DROITE }
enum class MouvementScene { STATIQUE, LATERAL, VERTICAL }
enum class EnergieScene { FAIBLE, MODEREE, ELEVEE }

/**
 * RapportReperage : les FAITS mesures par YOLO sur la passe. Aucune interpretation
 * du type de scene (ca reste a l'utilisateur). Alimente Surprends-moi et l'analyse auto.
 */
data class RapportReperage(
    val sujets: Int,
    val disposition: Disposition,
    val position: Position,
    val mouvement: MouvementScene,
    val energie: EnergieScene,
    val espaceLibre: Espace,
    val confiance: Float
) {
    val beaucoupDeMouvement: Boolean get() = energie == EnergieScene.ELEVEE

    /** Phrase honnete "Je vois ..." : uniquement des faits mesures. */
    fun resumeHonnete(ctx: Context): String {
        val s = when (sujets) {
            0 -> ctx.getString(R.string.rr_aucun_sujet)
            1 -> ctx.getString(R.string.rr_un_sujet)
            else -> ctx.getString(R.string.rr_n_sujets, sujets)
        }
        // FAITS FINS ajoutes au sujet : disposition (>=2 sujets) + position (>=1 sujet).
        val disp = if (sujets >= 2) when (disposition) {
            Disposition.GROUPEE   -> ctx.getString(R.string.rr_groupes)
            Disposition.DISPERSEE -> ctx.getString(R.string.rr_disperses)
            Disposition.ISOLEE    -> ""
        } else ""
        val pos = if (sujets >= 1) when (position) {
            Position.GAUCHE -> ctx.getString(R.string.rr_a_gauche)
            Position.DROITE -> ctx.getString(R.string.rr_a_droite)
            Position.CENTRE -> ""
        } else ""
        val mvtBase = when {
            energie == EnergieScene.ELEVEE -> ctx.getString(R.string.rr_bcp_mouvement)
            energie == EnergieScene.MODEREE -> ctx.getString(R.string.rr_peu_mouvement)
            else -> ctx.getString(R.string.rr_tres_peu_mouvement)
        }
        // direction seulement s'il y a vraiment du mouvement (evite "peu de mouvement (latéral)")
        val mvtDir = if (energie != EnergieScene.FAIBLE) when (mouvement) {
            MouvementScene.LATERAL  -> ctx.getString(R.string.rr_lateral)
            MouvementScene.VERTICAL -> ctx.getString(R.string.rr_vertical)
            MouvementScene.STATIQUE -> ""
        } else ""
        val esp = when (espaceLibre) {
            Espace.LARGE -> ctx.getString(R.string.rr_espace_degage)
            Espace.MOYEN -> ctx.getString(R.string.rr_espace_moyen)
            Espace.RESTREINT -> ctx.getString(R.string.rr_espace_restreint)
        }
        return ctx.getString(R.string.rr_je_vois, s, disp, pos, mvtBase, mvtDir, esp)
    }
}

