package ca.cineflight.stage.control

import ca.cineflight.stage.R

import android.content.Context

/**
 * Reglages - parametres des mouvements, reglables par l'utilisateur dans des bornes sures.
 *
 * Chaque parametre a : valeur par defaut, minimum, maximum (garde-fous securite).
 * Stockage SharedPreferences -> persiste entre les sessions.
 */
class Reglages(context: Context) {

    private val prefs = context.getSharedPreferences("cineflight_reglages", Context.MODE_PRIVATE)

    // --- definition d'un parametre reglable ---
    data class Param(val cle: String, val nomRes: Int, val unite: String,
                     val defaut: Float, val min: Float, val max: Float, val pas: Float,
                     val aideRes: Int = 0)

    // lire / ecrire
    fun get(p: Param): Float = prefs.getFloat(p.cle, p.defaut).coerceIn(p.min, p.max)
    fun set(p: Param, v: Float) = prefs.edit().putFloat(p.cle, v.coerceIn(p.min, p.max)).apply()
    fun reset(p: Param) = prefs.edit().remove(p.cle).apply()

    // --- Sujet a suivre en mode AUTO (le toucher reste universel) ---
    // 0 = Personne | 1 = Personne + Animal | 2 = Personne + Vehicule | 3 = Tout

    // --- Mode personnalise (4) : ensemble de classes COCO cochees a la main ---
    // Classes proposees a l'utilisateur (id COCO -> nom FR), voir CLASSES_PERSO ci-dessous.
    fun getClassesPerso(): Set<Int> {
        val brut = prefs.getStringSet("classes_perso", null) ?: return setOf(0)
        return brut.mapNotNull { it.toIntOrNull() }.toSet().ifEmpty { setOf(0) }
    }
    fun setClassesPerso(ids: Set<Int>) =
        prefs.edit().putStringSet("classes_perso", ids.map { it.toString() }.toSet()).apply()
    fun toggleClassePerso(id: Int) {
        val cur = getClassesPerso().toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        if (cur.isEmpty()) cur.add(0)   // jamais vide : au minimum personne
        setClassesPerso(cur)
    }

    /** Convertit le mode en ensemble de classes COCO. */
    // --- Camera : resolution + fps (applique sur drone reel, best effort) ---
    // Resolution : 0=FHD(1080p), 1=2.7K, 2=4K | fps : 24, 30, 60
    fun getResolution(): Int = prefs.getInt("cam_res", 0)
    fun setResolution(i: Int) = prefs.edit().putInt("cam_res", i.coerceIn(0, 2)).apply()
    fun getFps(): Int = prefs.getInt("cam_fps", 30)
    fun setFps(f: Int) = prefs.edit().putInt("cam_fps", f).apply()
    fun resolutionNom(): String = when (getResolution()) { 1 -> "2.7K"; 2 -> "4K"; else -> "FHD" }

    /**
     * Qualité photo pour les panoramas et captures 3D.
     * 0 = JPEG seul · 1 = DNG seul · 2 = DNG + JPEG (défaut).
     *
     * POURQUOI « DNG + JPEG » PAR DÉFAUT. Le flux efficace est en deux temps : le JPEG sert
     * à l'assemblage rapide — celui de notre serveur — pour vérifier l'horizon, les raccords
     * et la composition ; le DNG reste sur la carte et ne sert QUE pour reprendre les
     * meilleurs panoramas sur ordinateur, avec une bien plus grande latitude sur le ciel et
     * les ombres.
     *
     * ⚠ NOTRE CHAÎNE N'UTILISE PAS LE DNG. Le serveur assemble à partir des JPEG réduits.
     * Choisir « DNG seul » revient donc à se priver de l'assemblage automatique : les
     * fichiers seront là, mais rien ne les assemblera avant un traitement sur PC.
     * ⚠ Et le DNG pèse trois à quatre fois plus lourd : 61 photos en DNG+JPEG approchent
     * les 2,5 Go de carte.
     */
    fun getFormatPhoto(): Int = prefs.getInt("cam_format_photo", 2)
    fun setFormatPhoto(i: Int) = prefs.edit().putInt("cam_format_photo", i.coerceIn(0, 2)).apply()
    fun formatPhotoNom(): String = when (getFormatPhoto()) {
        0 -> "JPEG"; 1 -> "DNG"; else -> "DNG+JPEG"
    }

    // --- Cable-Cam : distance du rail "vers le sujet" (appui long sur Rail A), en metres ---
    fun getRailSujetDist(): Int = prefs.getInt("rail_sujet_dist", 8)
    fun setRailSujetDist(d: Int) = prefs.edit().putInt("rail_sujet_dist", d.coerceIn(3, 30)).apply()

    // --- Enregistrement auto au suivi : demarre la video au verrouillage de cible.
    //     ON par defaut pour ne pas oublier de filmer. Si OFF, un bandeau "REC OFF" rappelle
    //     quand le drone suit sans enregistrer.
    fun getEnregAuto(): Boolean = prefs.getBoolean("enreg_auto", true)
    fun setEnregAuto(v: Boolean) = prefs.edit().putBoolean("enreg_auto", v).apply()

    // Deplacement du drone en mode camera (suivi vision sans boitier). Defaut OFF (securite).
    fun getDeplacementCamera(): Boolean = prefs.getBoolean("deplacement_camera", false)
    fun setDeplacementCamera(v: Boolean) = prefs.edit().putBoolean("deplacement_camera", v).apply()

    // --- Evitement capteurs (mode developpeur, 3 etats) ---
    //   0 = OFF  : evitement desactive (test du suivi seul)
    //   1 = ON   : evitement force actif (test evitement / suivi+evitement)
    //   2 = AUTO : actif seulement si le drone fournit des donnees capteurs (production)
    // DEFAUT = 0 (OFF) pendant la phase de test. Passer a 2 (AUTO) apres validation.
    fun getModeEvitement(): Int = prefs.getInt("mode_evitement", 0)
    fun setModeEvitement(m: Int) = prefs.edit().putInt("mode_evitement", m.coerceIn(0, 2)).apply()

    // --- Qualite YOLO (modele + resolution + GPU) ---
    //   0 = Rapide : nano / 320 / CPU   -> loadModel(0,0,0). Fluide, portee ~4-12 m.
    //   1 = Precis : nano / 480 / GPU   -> loadModel(0,3,1). +portee (~4-15 m), GPU Vulkan.
    // DEFAUT = 0 (Rapide). Le mode Precis demande de redemarrer la detection (reload modele).
    fun getQualiteYolo(): Int = prefs.getInt("qualite_yolo", 0)
    fun setQualiteYolo(q: Int) = prefs.edit().putInt("qualite_yolo", q.coerceIn(0, 1)).apply()

    // Le suivi auto utilise directement les classes cochees par l'utilisateur.
    // (Les anciens presets Personne/Animal/Vehicule/Tout ont ete retires de l'UI.)
    fun classesPourSujet(): Set<Int> = getClassesPerso()

    // Config "valeurs sures" : prudente mais toujours cinematique (pas le minimum brut)
    fun appliquerValeursSures() {
        set(ORBITE_HAUTEUR, 4f); set(ORBITE_RAYON, 8f); set(ORBITE_VITESSE, 0.4f)
        set(TRAVEL_DISTANCE, 8f); set(TRAVEL_HAUTEUR, 4f); set(TRAVEL_REACTIV, 0.7f)
        set(REVEL_RECUL, 0.3f); set(REVEL_MONTEE, 0.3f)
        set(APP_VITESSE, 0.4f); set(APP_DISTANCE, 4f)
        set(SUIVI_DOUCEUR, 0.8f)
    }

    companion object {
        // ORBITE
        val ORBITE_HAUTEUR = Param("orb_h", R.string.rg_p_hauteur, "m", 3f, 2f, 30f, 0.5f, R.string.rg_a_orb_h)
        val ORBITE_RAYON   = Param("orb_r", R.string.rg_p_rayon, "m", 6f, 3f, 15f, 0.5f, R.string.rg_a_orb_r)
        val ORBITE_VITESSE = Param("orb_v", R.string.rg_p_vit_rotation, "m/s", 0.6f, 0.2f, 1.5f, 0.1f, R.string.rg_a_orb_v)
        // TRAVELLING
        val TRAVEL_DISTANCE = Param("trv_d", R.string.rg_p_dist_suivi, "m", 6f, 3f, 15f, 0.5f, R.string.rg_a_trv_d)
        val TRAVEL_HAUTEUR  = Param("trv_h", R.string.rg_p_hauteur, "m", 3f, 2f, 30f, 0.5f, R.string.rg_a_trv_h)
        val TRAVEL_REACTIV  = Param("trv_x", R.string.rg_p_react_laterale, "", 1.0f, 0.3f, 2.0f, 0.1f, R.string.rg_a_trv_x)
        // REVELATION
        val REVEL_RECUL  = Param("rev_r", R.string.rg_p_vit_recul, "m/s", 0.4f, 0.2f, 1.2f, 0.1f, R.string.rg_a_rev_r)
        val REVEL_MONTEE = Param("rev_m", R.string.rg_p_vit_montee, "m/s", 0.3f, 0.1f, 1.0f, 0.1f, R.string.rg_a_rev_m)
        // APPROCHE
        val APP_VITESSE  = Param("app_v", R.string.rg_p_vit_approche, "m/s", 0.6f, 0.2f, 1.2f, 0.1f, R.string.rg_a_app_v)
        val APP_DISTANCE = Param("app_d", R.string.rg_p_dist_arret, "m", 3f, 2f, 10f, 0.5f, R.string.rg_a_app_d)
        // SUIVI general
        val SUIVI_DOUCEUR = Param("sui_g", R.string.rg_p_douceur, "", 1.0f, 0.5f, 2.0f, 0.1f, R.string.rg_a_sui_g)
        // RECUL (le drone vous precede et recule pendant que vous avancez)
        val RECUL_HAUTEUR = Param("rec_h", R.string.rg_p_hauteur, "m", 3f, 2f, 30f, 0.5f, R.string.rg_a_rec_h)
        val RECUL_COTE = Param("rec_c", R.string.rg_p_angle_cote, "\u00b0", 0f, -45f, 45f, 5f, R.string.rg_a_rec_c)
        // SUIVI (le drone vous suit par-derriere pendant que vous avancez)
        val SUIVI_HAUTEUR = Param("sui_h", R.string.rg_p_hauteur, "m", 3f, 2f, 30f, 0.5f, R.string.rg_a_sui_h)
        val SUIVI_COTE = Param("sui_c", R.string.rg_p_angle_cote, "\u00b0", 0f, -45f, 45f, 5f, R.string.rg_a_sui_c)
        val SUIVI_REACTIV = Param("sui_r", R.string.rg_p_react_poursuite, "", 1.4f, 0.8f, 2.2f, 0.1f, R.string.rg_a_sui_r)

        // Classes COCO proposees dans le mode "Personnalise" (id -> nom FR)
        // Classes COCO proposees pour le suivi auto, ORGANISEES PAR CATEGORIE.
        // On ne garde que les sujets PERTINENTS pour un drone (bonne taille, suivables).
        // Exclus volontairement : mobilier, cuisine, electronique, objets portes, et les
        // petits objets trop rapides (frisbee, ballon, batte) -> mauvais suivi.
        val CLASSES_GROUPES = listOf(
            R.string.rg_cat_pv to listOf(
                0 to R.string.rg_cls_personne, 1 to R.string.rg_cls_velo, 2 to R.string.rg_cls_voiture, 3 to R.string.rg_cls_moto,
                5 to R.string.rg_cls_bus, 6 to R.string.rg_cls_train, 7 to R.string.rg_cls_camion, 8 to R.string.rg_cls_bateau
            ),
            R.string.rg_cat_animaux to listOf(
                15 to R.string.rg_cls_chat, 16 to R.string.rg_cls_chien, 17 to R.string.rg_cls_cheval,
                18 to R.string.rg_cls_mouton, 19 to R.string.rg_cls_vache, 20 to R.string.rg_cls_elephant, 21 to R.string.rg_cls_ours,
                22 to R.string.rg_cls_zebre, 23 to R.string.rg_cls_girafe
            ),
            R.string.rg_cat_sport to listOf(
                30 to R.string.rg_cls_skis, 31 to R.string.rg_cls_snowboard, 33 to R.string.rg_cls_cerf_volant,
                36 to R.string.rg_cls_skateboard, 37 to R.string.rg_cls_surf, 38 to R.string.rg_cls_raquette
            )
        )
        // Liste plate derivee des groupes (compatibilite : utilisee ailleurs pour iterer toutes les classes).
        val CLASSES_PERSO: List<Pair<Int, Int>> = CLASSES_GROUPES.flatMap { it.second }

        // groupes pour l'affichage de la page
        val GROUPES = listOf(
            R.string.rg_grp_orbite to listOf(ORBITE_HAUTEUR, ORBITE_RAYON, ORBITE_VITESSE),
            R.string.rg_grp_travelling to listOf(TRAVEL_DISTANCE, TRAVEL_HAUTEUR, TRAVEL_REACTIV),
            R.string.rg_grp_revelation to listOf(REVEL_RECUL, REVEL_MONTEE),
            R.string.rg_grp_approche to listOf(APP_VITESSE, APP_DISTANCE),
            R.string.rg_grp_recul to listOf(RECUL_HAUTEUR, RECUL_COTE),
            R.string.rg_grp_suivi to listOf(SUIVI_HAUTEUR, SUIVI_COTE, SUIVI_REACTIV),
            R.string.rg_grp_suivi_gen to listOf(SUIVI_DOUCEUR)
        )
    }
}
