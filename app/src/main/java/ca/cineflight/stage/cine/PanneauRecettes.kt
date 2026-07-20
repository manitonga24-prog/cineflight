package ca.cineflight.stage.cine

import ca.cineflight.stage.R

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * PanneauRecettes : overlay a DEUX ETAPES, affiche par-dessus le cockpit.
 *
 *   Etape 1 : grille des recettes (Surprends-moi + Premier vol mis en avant, puis les vedettes).
 *   Etape 2 : si recette-effet -> "Que filmez-vous ?" ; si recette-complete -> direct.
 *   Etape 3 : pastille 🟢🟡🔴 + bouton Lancer (appelle l'assistant deja branche).
 *
 * Il ne pilote pas lui-meme : il delegue a AssistantRealisateur (fourni par MainActivity),
 * qui pilote les mouvements REELS via CommandesVol. La couche existante reste intacte.
 *
 * Usage depuis MainActivity :
 *   private val panneau by lazy { PanneauRecettes(this, assistantCine) { contexteCineReel() } }
 *   ...  panneau.ouvrir()    // par ex. sur un bouton "Recettes"
 */
class PanneauRecettes(
    private val activity: Activity,
    private val assistant: AssistantRealisateur,
    private val fournirContexte: () -> ContexteValidation,
    /** Etat de vol OK pour lancer une AMBIANCE (pilotage VirtualStick direct) ?
     *  Retourne null si OK, sinon un message (drone deconnecte / au sol / batterie). */
    private val etatVolOk: (() -> String?)? = null,
    /** Vrai si le drone est POSE mais pret a decoller (connecte + batterie OK, pas
     *  en vol). Permet de proposer un decollage au sol au lieu de bloquer. */
    private val estPoseEtPret: (() -> Boolean)? = null,
    /** Declenche un decollage automatique (DJI startTakeoff -> vol stationnaire bas).
     *  onFini(true) si le drone est en l'air. null = pas de decollage propose. */
    private val decollerAuSol: ((onFini: (Boolean) -> Unit) -> Unit)? = null,
    /** Declencheur d'analyse de scene. null = bouton "Analyse auto" masque.
     *  pivoter=false : analyse fixe (A) ; pivoter=true : analyse panoramique (B).
     *  Appelle onRapport sur le thread UI quand la passe est finie. */
    private val lancerAnalyse: ((pivoter: Boolean, onRapport: (RapportReperage) -> Unit) -> Unit)? = null,
    /** Reglages ajustables (duree, vitesse, pivot). null = bouton Reglages masque. */
    private val reglages: ReglagesCine? = null,
    /** Analyseur vision (couche premium). null = bouton "Analyser le lieu" masque. */
    private val analyseurVision: AnalyseurVision? = null,
    /** Capture d'images du flux camera (base64 JPEG). null/liste vide = pas d'image. */
    private val capturerImages: (suspend () -> List<String>)? = null,
    /** Lance une coroutine sur le scope de l'activite (pour l'analyse vision suspendue). */
    private val lancerCoroutine: ((suspend () -> Unit) -> Unit)? = null,
    /** Analyseur de lieu (techno Explorer via serveur). null = bouton "Preparer le lieu" masque. */
    private val analyseurLieu: AnalyseurLieu? = null,
    /** Fournit la position actuelle (lat, lon) pour l'analyse de lieu. null si indispo. */
    private val fournirPosition: (() -> Pair<Double, Double>?)? = null,
    /** Demande une position FRAICHE de facon asynchrone (attend un vrai fix GPS). */
    private val demanderPositionFraiche: (((Pair<Double, Double>?) -> Unit) -> Unit)? = null,
    /** Geocode une adresse via le serveur. (adresse, onResultat(lat,lon,label) ou null). */
    private val geocoderAdresse: ((String, (Triple<Double, Double, String>?) -> Unit) -> Unit)? = null,
    /** Lance un panorama paysage. null = bouton "Panorama photo" masque.
     *  (preset, onProgres(i,total), onFini(nbPhotos ; -2 = pas en vol)). */
    private val lancerPanoramaPaysage: ((PanoramaPreset, (Int, Int) -> Unit, (Int) -> Unit) -> Unit)? = null,
    /** Annule un panorama en cours. */
    private val annulerPanorama: (() -> Unit)? = null,
    /** Assemble un panorama 360 cote serveur (telecharge les photos du drone,
     *  reduit, envoie, suit le job, renvoie l'image). null = bouton masque.
     *  (onProgres(message, pct 0..100), onFini(fichier image ou null)). */
    private val assemblerPano360: (((String, Int) -> Unit, (java.io.File?) -> Unit) -> Unit)? = null,
    /** Lance la mission KMZ en SIMULATION (barriere simulateur dans MainActivity).
     *  (fichierKmz, onProgres(indice,total,etat), onFini). null = bouton masque. */
    private val lancerMissionSimulee: ((java.io.File, (Int, Int, String) -> Unit, () -> Unit) -> Unit)? = null,
    /** Arrete la mission KMZ en cours. */
    private val arreterMissionSimulee: (() -> Unit)? = null,
    /** Appele apres 5 tapes sur le titre "Mission generee" pour debloquer la simulation. */
    private val debloquerSimulation: (() -> Unit)? = null,
    /** Actions du menu Preparer un tournage (null = carte masquee). */
    private val onDefinirSujet: (() -> Unit)? = null,
    private val onVerifierMission: (() -> Unit)? = null,
    private val onAnalyserPhotos: (() -> Unit)? = null,
    private val onMissionsPreparees: (() -> Unit)? = null,
    /** Fournit la liste des missions preparees (lue par la page stylee). */
    private val fournirMissionsPreparees: (() -> List<MissionsPrepareesStore.MissionPreparee>)? = null,
    /** Lance une mission preparee par son id (confirmation + verification drone cote MainActivity). */
    private val onLancerMissionPreparee: ((String) -> Unit)? = null,
    /** Reglages camera (null = carte masquee). Pilotent le vrai SDK via MainActivity. */
    private val onReglerEv: ((Float) -> Unit)? = null,
    private val onReglerModeExpo: ((String) -> Unit)? = null,
    private val onReglerIso: ((Int) -> Unit)? = null,
    private val onReglerShutter: ((String) -> Unit)? = null,
    private val onReglerWb: ((String) -> Unit)? = null,
    private val onReglerResFps: ((String, Int) -> Unit)? = null,
    private val fournirEtatCamera: (() -> Array<String?>)? = null,
    /** Lance une mission KMZ en VOL REEL dans l'app (executerVol de MainActivity :
     *  confirmation + upload + arret RTH). null = bouton "Lancer le vol" masque. */
    private val lancerMissionReelle: ((java.io.File) -> Unit)? = null,
    /** Auto-cadrage solo : demarre la sequence (decollage + montee + cadrage) du plan courant. */
    private val demarrerAutoCadrageCb: (() -> Unit)? = null,
    /** Commande la sequence auto-cadrage : "arret" | "annuler" | "lancer". */
    private val commandeAutoCadrage: ((String) -> Unit)? = null,
    /** Ramene le drone au point de depart (RTH). null = bouton "Ramener" masque. */
    private val ramenerDrone: (() -> Unit)? = null
) {
    // Palette alignee sur les Activities existantes (TagsActivity)
    private val FOND = 0xF2101418.toInt()      // sombre semi-opaque (overlay sur cockpit)
    private val CARTE = 0xFF1C2126.toInt()
    private val ACCENT = 0xFF007AFF.toInt()
    private val TEXTE = 0xFFECEFF1.toInt()
    private val TEXTE_DOUX = 0xFF90A4AE.toInt()

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private var racine: FrameLayout? = null
    // --- Auto-cadrage solo : refs de l'ecran, mises a jour par MainActivity via majEtatAuto ---
    private var autoEtatTexte: TextView? = null
    private var autoBtnLancer: Button? = null
    private var autoRecette: Recette? = null
    private var autoVerdict: Verdict? = null
    private var niveauIntermediaireOuvert = false
    private var niveauAvanceOuvert = false
    private var reperagePhotoOuvert = false
    private var profilChoisi: String? = null   // profil de reperage (eau/relief/...)
    private val plansPrepares = HashMap<Int, PlanExecutable>()   // ordre -> plan prepare
    private var dernierLieuSurPlace = false   // true si le lieu vient du GPS (sur place)
    private var decollageEcartConfirme = false  // true apres confirmation "drone pose a l'ecart"
    // Valeurs actives des reglages camera (memorisees ; lecture drone viendra plus tard)
    private var evActif = 0f
    private var modeExpoActif = "AUTO"
    private var isoActif = "AUTO"
    private var shutterActif = "1/60"
    private var wbActif = "AUTO"
    private var resFpsActif = "4K30"

    val estOuvert: Boolean get() = racine != null

    /** Ouvre le panneau (etape 1). Idempotent. */
    fun ouvrir() {
        if (racine != null) return
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(FOND)
            isClickable = true   // capte les clics, n'atteint pas le cockpit dessous
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        racine = overlay
        activity.addContentView(overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        afficherEtape1()
    }

    /** Ferme le panneau et retire l'overlay. */
    fun fermer() {
        racine?.let { (it.parent as? ViewGroup)?.removeView(it) }
        racine = null
    }

    // ----- En-tete commun (titre + bouton fermer/retour) -----
    private fun enTete(titre: String, sousTitre: String, onRetour: (() -> Unit)?): LinearLayout {
        val barre = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
        }
        val titres = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titres.addView(TextView(activity).apply {
            text = titre; textSize = 22f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
        })
        titres.addView(TextView(activity).apply {
            text = sousTitre; textSize = 13f; setTextColor(TEXTE_DOUX)
        })
        barre.addView(titres)
        // Bouton fermer/retour : pastille ronde stylee (X pour fermer, < pour retour)
        val taille = dp(36).toInt()
        val rond = TextView(activity).apply {
            text = "\u2039"   // fleche retour standard (<)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(TEXTE)
            setTypeface(typeface, Typeface.BOLD)
            val fond = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(CARTE)
                setStroke(dp(1).toInt(), 0x33FFFFFF.toInt())
            }
            background = fond
            layoutParams = LinearLayout.LayoutParams(taille, taille)
            isClickable = true
            setOnClickListener { if (onRetour != null) onRetour() else fermer() }
        }
        barre.addView(rond)
        return barre
    }

    private fun conteneurScroll(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(col)
        return scroll to col
    }

    // ========================= ETAPE 1 : la grille =========================
    /** Fin trait horizontal discret, pour separer les sections. */
    private fun ligneSeparatrice(couleur: Int = 0xFF2A2E33.toInt(), epaisseurDp: Int = 1): View {
        return View(activity).apply {
            setBackgroundColor(couleur)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(epaisseurDp))
                .apply { setMargins(0, dp(12), 0, dp(2)) }
        }
    }

    /** Titre de section (niveau de pilote), non repliable. */
    private fun enTeteNiveau(titre: String, sous: String): View {
        val l = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(14), 0, dp(6))
        }
        l.addView(TextView(activity).apply {
            text = titre; textSize = 17f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
        })
        l.addView(TextView(activity).apply {
            text = sous; textSize = 11f; setTextColor(TEXTE_DOUX)
        })
        return l
    }

    /** En-tete de section REPLIABLE : titre + "Afficher les options / Masquer" ; tap pour basculer. */
    private fun boutonNiveau(titre: String, sous: String, ouvert: Boolean, onToggle: () -> Unit): View {
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val ligne = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val g = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            g.addView(TextView(activity).apply {
                text = titre; textSize = 17f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            g.addView(TextView(activity).apply {
                text = sous; textSize = 11f; setTextColor(TEXTE_DOUX)
            })
            ligne.addView(g)
            ligne.addView(TextView(activity).apply {
                text = if (ouvert) activity.getString(ca.cineflight.stage.R.string.pr_masquer) else activity.getString(ca.cineflight.stage.R.string.pr_afficher_options)
                textSize = 13f; setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(ligne)
            l.setOnClickListener { onToggle() }
            l
        }
    }

    private fun afficherEtape1() {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_e1_titre), activity.getString(ca.cineflight.stage.R.string.pr_e1_sous), null))

        // ======================= 🟢 NOUVEAU PILOTE (toujours visible) =======================
        col.addView(ligneSeparatrice(0xFF2E7D32.toInt(), 3))
        col.addView(enTeteNiveau(activity.getString(ca.cineflight.stage.R.string.pr_niv_nouveau), activity.getString(ca.cineflight.stage.R.string.pr_niv_nouveau_sous)))
        col.addView(espace(8))
        col.addView(carteAction("🚁", activity.getString(ca.cineflight.stage.R.string.pr_premier_vol_titre),
            activity.getString(ca.cineflight.stage.R.string.pr_premier_vol_desc)) {
            activity.startActivity(android.content.Intent(activity,
                ca.cineflight.stage.PremierVolActivity::class.java))
            fermer()
        })
        col.addView(espace(8))
        col.addView(carteSpeciale(Catalogue.PREMIER_VOL, activity.getString(ca.cineflight.stage.R.string.pr_pour_bien_debuter)))
        col.addView(espace(8))
        col.addView(carteSurprends())
        // Les 4 ambiances les plus rassurantes, en acces direct
        val grilleNouveau = GridLayout(activity).apply { columnCount = 2 }
        for (nom in listOf("Souvenir inoubliable", "Reunion de famille", "Fete a celebrer", "Moment precieux")) {
            Catalogue.VEDETTES.firstOrNull { it.nom == nom }?.let { grilleNouveau.addView(carteRecette(it)) }
        }
        col.addView(grilleNouveau, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (lancerPanoramaPaysage != null) {
            col.addView(espace(8)); col.addView(cartePanorama())
        }
        col.addView(espace(8)); col.addView(carteLumieres())

        // ======================= 🟡 PILOTE INTERMEDIAIRE (repliable) =======================
        col.addView(ligneSeparatrice(0xFFF9A825.toInt(), 3))
        col.addView(boutonNiveau(activity.getString(ca.cineflight.stage.R.string.pr_niv_inter), activity.getString(ca.cineflight.stage.R.string.pr_niv_inter_sous), niveauIntermediaireOuvert) {
            niveauIntermediaireOuvert = !niveauIntermediaireOuvert; afficherEtape1()
        })
        if (niveauIntermediaireOuvert) {
            if (lancerAnalyse != null) { col.addView(espace(8)); col.addView(carteAnalyse()) }
            if (onDefinirSujet != null || onVerifierMission != null || onMissionsPreparees != null || onAnalyserPhotos != null) {
                col.addView(espace(8))
                col.addView(carteAction("🎥", activity.getString(ca.cineflight.stage.R.string.pr_preparer_tournage), activity.getString(ca.cineflight.stage.R.string.pr_preparer_tournage_desc)) { afficherMenuReconnaissance() })
            }
            col.addView(espace(8))
            col.addView(carteAction("🚶", activity.getString(ca.cineflight.stage.R.string.pr_pers_vision_titre),
                activity.getString(ca.cineflight.stage.R.string.pr_pers_vision_desc)) {
                activity.startActivity(android.content.Intent(activity,
                    ca.cineflight.stage.Phase3Activity::class.java)
                    .putExtra("PROFIL_SUJET", "MARCHE").putExtra("MODE_VISION", true))
                fermer()
            })
            if (analyseurLieu != null && fournirPosition != null) {
                col.addView(espace(8))
                col.addView(carteAction("🎬", activity.getString(ca.cineflight.stage.R.string.pr_filmer_groupe),
                    activity.getString(ca.cineflight.stage.R.string.pr_filmer_groupe_desc)) { afficherFilmerGroupe() })
                col.addView(espace(8))
                col.addView(carteAction("🔽", activity.getString(ca.cineflight.stage.R.string.pr_plan_haut_td),
                    activity.getString(ca.cineflight.stage.R.string.pr_plan_haut_td_desc)) { ouvrirTopDown() })
            }
            if (analyseurLieu != null && lancerCoroutine != null) {
                col.addView(espace(8)); col.addView(carteLieu())
            }
            col.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_toutes_ambiances); textSize = 14f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), dp(10), 0, dp(2))
            })
            col.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_toutes_ambiances_desc); textSize = 11f
                setTextColor(TEXTE_DOUX)
                setPadding(dp(2), 0, dp(2), dp(8))
            })
            val grille = GridLayout(activity).apply { columnCount = 2 }
            for (r in Catalogue.VEDETTES) grille.addView(carteRecette(r))
            col.addView(grille, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // ======================= 🔴 PILOTE AVANCE (repliable) =======================
        col.addView(ligneSeparatrice(0xFFC62828.toInt(), 3))
        col.addView(boutonNiveau(activity.getString(ca.cineflight.stage.R.string.pr_niv_avance), activity.getString(ca.cineflight.stage.R.string.pr_niv_avance_sous), niveauAvanceOuvert) {
            niveauAvanceOuvert = !niveauAvanceOuvert; afficherEtape1()
        })
        if (niveauAvanceOuvert) {
            col.addView(espace(8))
            col.addView(carteAction("🚗", activity.getString(ca.cineflight.stage.R.string.pr_suivre_vehicule),
                activity.getString(ca.cineflight.stage.R.string.pr_suivre_vehicule_desc)) {
                activity.startActivity(android.content.Intent(activity,
                    ca.cineflight.stage.Phase3Activity::class.java))
                fermer()
            })
            col.addView(espace(8))
            col.addView(carteAction("⚽", activity.getString(ca.cineflight.stage.R.string.pr_soccer),
                activity.getString(ca.cineflight.stage.R.string.pr_soccer_desc)) {
                activity.startActivity(android.content.Intent(activity,
                    ca.cineflight.stage.Phase3Activity::class.java).putExtra("MODE_SOCCER", true))
                fermer()
            })
            col.addView(espace(8))
            col.addView(carteAction("📡", activity.getString(ca.cineflight.stage.R.string.pr_pers_rtk_titre),
                activity.getString(ca.cineflight.stage.R.string.pr_pers_rtk_desc)) {
                activity.startActivity(android.content.Intent(activity,
                    ca.cineflight.stage.Phase3Activity::class.java).putExtra("PROFIL_SUJET", "MARCHE"))
                fermer()
            })
            if (analyseurVision != null && lancerCoroutine != null) {
                col.addView(espace(8)); col.addView(carteVision())
            }
        }

        col.addView(ligneSeparatrice())
        // ======================= Bas de page (hors niveaux) =======================
        if (reglages != null) {
            col.addView(espace(12))
            col.addView(Button(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_btn_reglages_assistant); isAllCaps = false; textSize = 14f
                setTextColor(TEXTE_DOUX)
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1C2126.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
                setOnClickListener { afficherReglages() }
            })
        }
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_test_drone); isAllCaps = false; textSize = 14f
            setTextColor(TEXTE_DOUX)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF263238.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            setOnClickListener {
                activity.startActivity(android.content.Intent(activity,
                    ca.cineflight.stage.TelemetrieActivity::class.java))
            }
        })
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_diagnostic); isAllCaps = false; textSize = 14f
            setTextColor(TEXTE_DOUX)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2E33.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            setOnClickListener {
                (activity as? ca.cineflight.stage.MainActivity)?.basculerDiagnostic()
                fermer()
            }
        })
        overlay.addView(scroll)
    }

    // ===================== Page : Filmer un groupe (souvenir) =====================
    /** Type de groupe choisi pour l'ecran souvenir (solo|couple|amis|famille). */
    private var groupeChoisi: String = "couple"
    /** Plan/mouvement choisi pour le souvenir (orbite|travelling). */
    private var souvenirPlanChoisi: String = "orbite"
    /** Orientation du plan : "auto" | "pilote" | "cap". Defaut auto (CineFlight decide). */
    private var souvenirOrientation: String = "auto"
    /** Cap manuel en degres (si orientation = "cap"). */
    private var souvenirCapManuel: Int = 0
    /** Proximite/cadrage : "proche" | "equilibre" | "large". Defaut equilibre.
     *  S'applique a TOUS les plans (regle rayon + hauteur cote serveur). */
    private var souvenirProximite: String = "equilibre"
    /** Le panneau "Options avancees" est-il deplie ? */
    private var optionsAvanceesOuvertes: Boolean = false

    private fun afficherFilmerGroupe() {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_grp_titre),
            activity.getString(ca.cineflight.stage.R.string.pr_grp_sous)) { afficherEtape1() })

        // 1) Choix du type de groupe
        col.addView(espace(8))
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_grp_qui); textSize = 11f
            setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(4), 0, dp(6))
        })
        val groupes = listOf(
            "solo" to activity.getString(ca.cineflight.stage.R.string.pr_grp_solo),
            "couple" to activity.getString(ca.cineflight.stage.R.string.pr_grp_couple),
            "amis" to activity.getString(ca.cineflight.stage.R.string.pr_grp_amis),
            "famille" to activity.getString(ca.cineflight.stage.R.string.pr_grp_famille))
        for ((cle, libelle) in groupes) {
            col.addView(espace(6))
            col.addView(carteChoixGroupe(cle, libelle))
        }

        // 1b) Choix du PLAN (mouvement). Le top-down n'apparait que pour le solo
        // (sujet isole consentant) : jamais au-dessus d'un groupe.
        col.addView(espace(12))
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_grp_quel_plan); textSize = 11f
            setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(4), 0, dp(6))
        })
        val plans = mutableListOf(
            Triple("orbite", activity.getString(ca.cineflight.stage.R.string.pr_plan_orbite_t),
                activity.getString(ca.cineflight.stage.R.string.pr_plan_orbite_d)),
            Triple("orbite_360", activity.getString(ca.cineflight.stage.R.string.pr_plan_orbite360_t),
                activity.getString(ca.cineflight.stage.R.string.pr_plan_orbite360_d)),
            Triple("travelling", activity.getString(ca.cineflight.stage.R.string.pr_plan_travelling_t),
                activity.getString(ca.cineflight.stage.R.string.pr_plan_travelling_d)),
            Triple("reveal", activity.getString(ca.cineflight.stage.R.string.pr_plan_reveal_t),
                activity.getString(ca.cineflight.stage.R.string.pr_plan_reveal_d)),
            Triple("establishing", activity.getString(ca.cineflight.stage.R.string.pr_plan_estab_t),
                activity.getString(ca.cineflight.stage.R.string.pr_plan_estab_d)),
            Triple("ascension", activity.getString(ca.cineflight.stage.R.string.pr_plan_ascension_t),
                activity.getString(ca.cineflight.stage.R.string.pr_plan_ascension_d)),
            Triple("push_in", activity.getString(ca.cineflight.stage.R.string.pr_plan_pushin_t),
                activity.getString(ca.cineflight.stage.R.string.pr_plan_pushin_d)))
        // si plus tard d'autres plans serveur sont ajoutes, ils s'inserent ici.
        for ((cle, titre, desc) in plans) {
            col.addView(espace(6))
            col.addView(carteChoixPlanSouvenir(cle, titre, desc))
        }
        // Le plan de haut (top-down) est propose SEPAREMENT pour le solo,
        // via l'ecran dedie, car il exige le consentement explicite.
        if (groupeChoisi == "solo") {
            col.addView(espace(6))
            col.addView(carteAction("\u2B07\uFE0F", activity.getString(ca.cineflight.stage.R.string.pr_vue_ciel),
                activity.getString(ca.cineflight.stage.R.string.pr_vue_ciel_desc)) {
                ouvrirTopDown()
            })
        }

        // 1b-bis) CADRAGE : proximite (Proche / Equilibre / Large). S'applique a
        // TOUS les plans -> regle rayon + hauteur pour bien voir les gens.
        col.addView(espace(12))
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_cadrage_titre); textSize = 11f
            setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(4), 0, dp(6))
        })
        val proximites = listOf(
            Triple("proche", activity.getString(ca.cineflight.stage.R.string.pr_prox_proche_t),
                activity.getString(ca.cineflight.stage.R.string.pr_prox_proche_d)),
            Triple("equilibre", activity.getString(ca.cineflight.stage.R.string.pr_prox_equilibre_t),
                activity.getString(ca.cineflight.stage.R.string.pr_prox_equilibre_d)),
            Triple("large", activity.getString(ca.cineflight.stage.R.string.pr_prox_large_t),
                activity.getString(ca.cineflight.stage.R.string.pr_prox_large_d)))
        for ((cle, titre, desc) in proximites) {
            col.addView(espace(6))
            col.addView(carteChoixProximite(cle, titre, desc))
        }

        // 1c) OPTIONS AVANCEES (orientation du plan). Repliees par defaut :
        // l'utilisateur normal ne voit qu'un bouton. CineFlight choisit l'angle.
        col.addView(espace(10))
        col.addView(TextView(activity).apply {
            text = if (optionsAvanceesOuvertes) activity.getString(ca.cineflight.stage.R.string.pr_options_av_open)
                   else activity.getString(ca.cineflight.stage.R.string.pr_options_av_closed)
            textSize = 13f; setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener {
                optionsAvanceesOuvertes = !optionsAvanceesOuvertes
                afficherFilmerGroupe()
            }
        })
        if (optionsAvanceesOuvertes) {
            col.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_orientation_plan)
                textSize = 11f; setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), dp(2), 0, dp(4))
            })
            val orientations = listOf(
                Triple("auto", activity.getString(ca.cineflight.stage.R.string.pr_ori_auto_t), activity.getString(ca.cineflight.stage.R.string.pr_ori_auto_d)),
                Triple("pilote", activity.getString(ca.cineflight.stage.R.string.pr_ori_pilote_t), activity.getString(ca.cineflight.stage.R.string.pr_ori_pilote_d)),
                Triple("cap", activity.getString(ca.cineflight.stage.R.string.pr_ori_cap_t), activity.getString(ca.cineflight.stage.R.string.pr_ori_cap_d)))
            for ((cle, titre, desc) in orientations) {
                col.addView(espace(4))
                col.addView(carteChoixOrientation(cle, titre, desc))
            }
            // Reglage du cap manuel (visible seulement si "cap" choisi)
            if (souvenirOrientation == "cap") {
                col.addView(espace(6))
                col.addView(reglageCapManuel())
            }
        }

        // 2) COMMENT VOUS PLACER (consigne dynamique selon l'orientation)
        col.addView(espace(12))
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_comment_placer)
            textSize = 13f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(2), 0, dp(4))
        })
        col.addView(cadre(pleineLargeur = true) {
            val bloc = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            // consigne CLE, mise en avant : ne pas marcher pendant le plan
            bloc.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_restez_place)
                textSize = 13f; setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD)
            })
            // ligne de base + precision selon l'orientation
            bloc.addView(TextView(activity).apply {
                val base = activity.getString(ca.cineflight.stage.R.string.pr_placer_base)
                val precision = when (souvenirOrientation) {
                    "auto" -> activity.getString(ca.cineflight.stage.R.string.pr_placer_auto)
                    "pilote" -> activity.getString(ca.cineflight.stage.R.string.pr_placer_pilote)
                    "cap" -> activity.getString(ca.cineflight.stage.R.string.pr_placer_cap_fmt, souvenirCapManuel)
                    else -> ""
                }
                text = base + precision
                textSize = 13f; setTextColor(TEXTE_DOUX)
            })
            bloc
        })

        // 3) Rappel de securite (decollage a l'ecart)
        col.addView(espace(12))
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_ecart_groupe)
                textSize = 13f; setTextColor(0xFFE57373.toInt())
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        })

        // 3) Bouton de generation (placeholder cran 1 ; branche au cran 3)
        col.addView(espace(12))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_gen_souvenir); isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener {
                lancerSouvenir()
            }
        })

        overlay.addView(scroll)
    }

    /** CRAN 3 : capture la position GPS (= point de decollage, a l'ecart),
     *  appelle /api/souvenir, affiche la checklist de securite renvoyee par le
     *  serveur, puis telecharge le KMZ et l'exporte vers DJI Fly.
     *
     *  v1 SANS carte : la position GPS du pilote sert de POINT DE DECOLLAGE
     *  (le pilote se tient pres du drone, a l'ecart) ; le GROUPE filme est un
     *  point decale de quelques metres (jamais a l'aplomb du decollage). */
    private fun lancerSouvenir() {
        val analyseur = analyseurLieu ?: return
        val lancer = lancerCoroutine ?: return
        val overlay = racine ?: return

        // ecran d'attente
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_souvenir_titre), activity.getString(ca.cineflight.stage.R.string.pr_recherche_gps)) { afficherFilmerGroupe() })
        val txtEtat = TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_obtention_gps_souvenir)
            textSize = 14f; setTextColor(TEXTE_DOUX); setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        col.addView(cadre(pleineLargeur = true) { txtEtat })
        overlay.addView(scroll)

        val groupe = groupeChoisi
        val plan = souvenirPlanChoisi
        val proximite = souvenirProximite
        val orientation = souvenirOrientation
        val capManuel: Double? = if (souvenirOrientation == "cap") souvenirCapManuel.toDouble() else null
        val traiter: (Pair<Double, Double>?) -> Unit = { position ->
            activity.runOnUiThread {
                if (!estOuvert) return@runOnUiThread
                if (position == null) {
                    afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_gps_impossible_souvenir))
                    return@runOnUiThread
                }
                if (!analyseur.disponible()) {
                    afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_souvenir_internet))
                    return@runOnUiThread
                }
                // Le TELEPHONE est AVEC le groupe -> sa position = le SUJET filme (centre
                // d'orbite). Le drone tourne autour de la ou est le telephone : le groupe
                // est donc toujours dans le cadre (fini le point vide "8 m au nord").
                val (grpLat, grpLon) = position
                val sujetLat = grpLat
                val sujetLon = grpLon
                // Point de DECOLLAGE : ~8 m a l'ecart du groupe. Le drone est pose la, monte
                // a la verticale de SON point (jamais au-dessus des gens), puis rejoint l'orbite.
                // La direction (nord) est cosmetique : elle ne change pas le cadrage (l'orbite
                // est centree sur le groupe, en coordonnees absolues).
                val DECALAGE_DECOLLAGE_M = 8.0
                val depLat = grpLat + (DECALAGE_DECOLLAGE_M / 111_320.0)
                val depLon = grpLon
                txtEtat.text = activity.getString(ca.cineflight.stage.R.string.pr_pos_trouvee_souvenir)
                // Generation du plan + affichage, extraite pour la lancer APRES la
                // verification de degagement (obstacles connus OSM).
                val genererMaintenant: () -> Unit = {
                    lancer {
                        val res = try {
                            analyseur.genererSouvenir(sujetLat, sujetLon, groupe, depLat, depLon, plan, orientation, capManuel, proximite)
                        } catch (_: Exception) { null }
                        activity.runOnUiThread {
                            if (!estOuvert) return@runOnUiThread
                            if (res == null) {
                                val detail = analyseur.derniereErreur?.let { activity.getString(ca.cineflight.stage.R.string.pr_detail_fmt, it) } ?: ""
                                afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_souvenir_echec_fmt, detail))
                            } else {
                                afficherSouvenirGenere(res)
                            }
                        }
                    }
                }
                // VERIFICATION DU DEGAGEMENT avant de generer : obstacles connus (OSM)
                // dans le rayon d'orbite (calibre sur la proximite). Obstacles ->
                // avertissement explicite ; zone non couverte -> rappel visuel.
                val rayonVerif = when (proximite) { "proche" -> 30; "large" -> 55; else -> 40 }
                txtEtat.text = activity.getString(ca.cineflight.stage.R.string.pr_verif_degagement)
                lancer {
                    val vp = try { analyseur.verifierPoint(sujetLat, sujetLon, rayonVerif) } catch (_: Exception) { null }
                    activity.runOnUiThread {
                        if (!estOuvert) return@runOnUiThread
                        val obst = vp?.takeIf { it.etat == "obstacles" }?.obstacles ?: emptyList()
                        if (obst.isNotEmpty()) {
                            val liste = obst.sortedBy { it.distanceM }.take(6)
                                .joinToString("\n") { "• ${it.type} — ~${it.distanceM} m" }
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, ca.cineflight.stage.R.style.DialogCineFlight)
                                .setTitle(activity.getString(ca.cineflight.stage.R.string.pr_zone_encombree))
                                .setMessage(activity.getString(ca.cineflight.stage.R.string.pr_obstacles_msg, rayonVerif, liste))
                                .setCancelable(false)
                                .setNegativeButton(activity.getString(ca.cineflight.stage.R.string.pr_annuler)) { d, _ -> d.dismiss(); afficherFilmerGroupe() }
                                .setPositiveButton(activity.getString(ca.cineflight.stage.R.string.pr_lancer_quand_meme)) { d, _ -> d.dismiss(); genererMaintenant() }
                                .show()
                        } else {
                            if (vp != null && vp.etat != "carte_ok") {
                                android.widget.Toast.makeText(activity,
                                    activity.getString(ca.cineflight.stage.R.string.pr_degagement_non_verif),
                                    android.widget.Toast.LENGTH_LONG).show()
                            }
                            genererMaintenant()
                        }
                    }
                }
            }
        }

        val obtenirPosition = demanderPositionFraiche
        if (obtenirPosition != null) obtenirPosition(traiter)
        else traiter(fournirPosition?.invoke())
    }

    /** Ecran de confirmation : checklist de securite (serveur) + export KMZ. */
    private fun afficherSouvenirGenere(res: SouvenirResultat) {
        val analyseur = analyseurLieu ?: return
        val lancer = lancerCoroutine ?: return
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_souvenir_pret),
            activity.getString(ca.cineflight.stage.R.string.pr_groupe_fmt, res.groupe)) { afficherFilmerGroupe() })

        // Distance de securite + second adulte
        col.addView(espace(8))
        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_distance_secu_fmt, res.distanceSecuriteM.toInt()) +
                       (if (res.enfantsPresents) activity.getString(ca.cineflight.stage.R.string.pr_renforcee_enfants) else "")
                textSize = 14f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            if (res.secondAdulteRecommande) l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_second_adulte)
                textSize = 13f; setTextColor(0xFFE57373.toInt()); setPadding(0, dp(4), 0, 0)
            })
            l
        })

        // Checklist de securite (renvoyee par le serveur)
        if (res.checklist.isNotEmpty()) {
            col.addView(espace(8))
            col.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_checklist_secu); textSize = 11f
                setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), dp(4), 0, dp(6))
            })
            for (item in res.checklist) {
                col.addView(cadre(pleineLargeur = true) {
                    TextView(activity).apply {
                        text = "\u2022  $item"; textSize = 13f
                        setTextColor(TEXTE); setPadding(dp(4), dp(4), dp(4), dp(4))
                    }
                })
                col.addView(espace(4))
            }
        }

        // Options : vol REEL dans l'app / simulation / export (meme schema que le plan de haut).
        val cbVol = lancerMissionReelle
        if (cbVol != null) {
            col.addView(espace(12))
            col.addView(Button(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_lancer_vol_app); isAllCaps = false; textSize = 16f
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                setOnClickListener { avecKmzParPlan(res.planId, "souvenir_cineflight.kmz", this) { fic -> cbVol(fic) } }
            })
        }
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_voir_carte_simuler); isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                avecKmzParPlan(res.planId, "souvenir_cineflight.kmz", this) { fic ->
                    val it = android.content.Intent(activity, ca.cineflight.stage.CarteMissionActivity::class.java)
                    it.putExtra("kmz_path", fic.absolutePath)
                    activity.startActivity(it)
                }
            }
        })

        overlay.addView(scroll)
    }

    /** Carte de selection d'un type de groupe (surbrillance du choix actif). */
    private fun carteChoixGroupe(cle: String, libelle: String): View {
        val actif = (cle == groupeChoisi)
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            l.addView(TextView(activity).apply {
                text = libelle; textSize = 17f
                setTextColor(if (actif) Color.WHITE else TEXTE)
                setTypeface(typeface, if (actif) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (actif) l.addView(TextView(activity).apply {
                text = "\u2713"; textSize = 18f; setTextColor(ACCENT)
                setTypeface(typeface, Typeface.BOLD)
            })
            l.setOnClickListener {
                groupeChoisi = cle
                afficherFilmerGroupe()   // redessine pour montrer la selection
            }
            l
        }
    }

    /** Carte de selection du PLAN souvenir : titre (grand public + cinema) +
     *  description courte. Surbrillance du choix actif. */
    private fun carteChoixPlanSouvenir(cle: String, titre: String, desc: String): View {
        val actif = (cle == souvenirPlanChoisi)
        return cadre(pleineLargeur = true) {
            val ligne = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            // bloc texte : titre en gras + description en dessous
            val bloc = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            bloc.addView(TextView(activity).apply {
                text = titre; textSize = 16f
                setTextColor(if (actif) Color.WHITE else TEXTE)
                setTypeface(typeface, if (actif) Typeface.BOLD else Typeface.NORMAL)
            })
            bloc.addView(TextView(activity).apply {
                text = desc; textSize = 12.5f
                setTextColor(if (actif) 0xFFB3D4FF.toInt() else TEXTE_DOUX)
                setPadding(0, dp(2), 0, 0)
            })
            ligne.addView(bloc)
            if (actif) ligne.addView(TextView(activity).apply {
                text = "\u2713"; textSize = 18f; setTextColor(ACCENT)
                setTypeface(typeface, Typeface.BOLD)
            })
            ligne.setOnClickListener {
                souvenirPlanChoisi = cle
                afficherFilmerGroupe()
            }
            ligne
        }
    }

    /** Carte de selection de l'ORIENTATION (auto|pilote|cap), titre + description. */
    private fun carteChoixOrientation(cle: String, titre: String, desc: String): View {
        val actif = (cle == souvenirOrientation)
        return cadre(pleineLargeur = true) {
            val ligne = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val bloc = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            bloc.addView(TextView(activity).apply {
                text = titre; textSize = 15f
                setTextColor(if (actif) Color.WHITE else TEXTE)
                setTypeface(typeface, if (actif) Typeface.BOLD else Typeface.NORMAL)
            })
            bloc.addView(TextView(activity).apply {
                text = desc; textSize = 12f
                setTextColor(if (actif) 0xFFB3D4FF.toInt() else TEXTE_DOUX)
                setPadding(0, dp(2), 0, 0)
            })
            ligne.addView(bloc)
            if (actif) ligne.addView(TextView(activity).apply {
                text = "\u2713"; textSize = 18f; setTextColor(ACCENT)
                setTypeface(typeface, Typeface.BOLD)
            })
            ligne.setOnClickListener {
                souvenirOrientation = cle
                afficherFilmerGroupe()
            }
            ligne
        }
    }

    /** Carte de selection de la PROXIMITE (proche|equilibre|large), titre + description. */
    private fun carteChoixProximite(cle: String, titre: String, desc: String): View {
        val actif = (cle == souvenirProximite)
        return cadre(pleineLargeur = true) {
            val ligne = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val bloc = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            bloc.addView(TextView(activity).apply {
                text = titre; textSize = 15f
                setTextColor(if (actif) Color.WHITE else TEXTE)
                setTypeface(typeface, if (actif) Typeface.BOLD else Typeface.NORMAL)
            })
            bloc.addView(TextView(activity).apply {
                text = desc; textSize = 12f
                setTextColor(if (actif) 0xFFB3D4FF.toInt() else TEXTE_DOUX)
                setPadding(0, dp(2), 0, 0)
            })
            ligne.addView(bloc)
            if (actif) ligne.addView(TextView(activity).apply {
                text = "\u2713"; textSize = 18f; setTextColor(ACCENT)
                setTypeface(typeface, Typeface.BOLD)
            })
            ligne.setOnClickListener {
                souvenirProximite = cle
                afficherFilmerGroupe()
            }
            ligne
        }
    }

    /** Reglage du cap manuel (0-359 deg) par boutons -/+ et 4 raccourcis N/E/S/O. */
    private fun reglageCapManuel(): View {
        return cadre(pleineLargeur = true) {
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            val valeur = TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_cap_fmt, souvenirCapManuel)
                textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            }
            col.addView(valeur)
            // ligne -/+ (pas de 15 deg)
            val ligneFine = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4))
            }
            val majValeur: () -> Unit = { valeur.text = activity.getString(ca.cineflight.stage.R.string.pr_cap_fmt, souvenirCapManuel) }
            ligneFine.addView(Button(activity).apply {
                text = "\u2212 15\u00B0"; isAllCaps = false
                setOnClickListener {
                    souvenirCapManuel = ((souvenirCapManuel - 15) % 360 + 360) % 360; majValeur()
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            ligneFine.addView(Button(activity).apply {
                text = "+ 15\u00B0"; isAllCaps = false
                setOnClickListener {
                    souvenirCapManuel = (souvenirCapManuel + 15) % 360; majValeur()
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            col.addView(ligneFine)
            // raccourcis cardinaux
            val ligneCard = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for ((nom, deg) in listOf(ca.cineflight.stage.R.string.pr_card_n to 0, ca.cineflight.stage.R.string.pr_card_e to 90, ca.cineflight.stage.R.string.pr_card_s to 180, ca.cineflight.stage.R.string.pr_card_o to 270)) {
                ligneCard.addView(Button(activity).apply {
                    text = activity.getString(nom); isAllCaps = false
                    setOnClickListener { souvenirCapManuel = deg; majValeur() }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            col.addView(ligneCard)
            col
        }
    }
    private var topVariante: String = "simple"       // simple | spirale | orbite_haute
    private var topContexte: String = "objet_lieu"   // objet_lieu | sujet_isole_consenti
    private var topDescendre: Boolean = false
    private var topConsentement: Boolean = false

    /** Point d'ENTREE du plan de haut : remet le consentement (et le contexte) a
     *  leur valeur sure a CHAQUE ouverture, pour que le consentement ne reste jamais
     *  "colle" d'une session/sujet a l'autre. Le redessin (afficherTopDown) ne touche
     *  pas a ces drapeaux, sinon il decocherait la case aussitot cochee. */
    private fun ouvrirTopDown() {
        topContexte = "objet_lieu"
        topConsentement = false
        afficherTopDown()
    }

    private fun afficherTopDown() {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_td_titre),
            activity.getString(ca.cineflight.stage.R.string.pr_td_sous)) { afficherEtape1() })

        // Note : le plan de haut vise un POINT FIXE (ne suit pas un sujet mobile).
        col.addView(espace(8))
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_td_fixe)
                textSize = 13f; setTextColor(0xFF9FD0FF.toInt())
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
        })

        // 1) Contexte (qui/quoi filme-t-on) — decide la securite
        col.addView(espace(8))
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_td_quoi); textSize = 11f
            setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(4), 0, dp(6))
        })
        val contextes = listOf(
            "objet_lieu" to activity.getString(ca.cineflight.stage.R.string.pr_td_objet),
            "sujet_isole_consenti" to activity.getString(ca.cineflight.stage.R.string.pr_td_solo))
        for ((cle, libelle) in contextes) {
            col.addView(espace(6))
            col.addView(carteChoixTop("ctx", cle, libelle))
        }

        // 2) Variante de mouvement
        col.addView(espace(12))
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_td_mouvement); textSize = 11f
            setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(4), 0, dp(6))
        })
        val variantes = listOf(
            "simple" to activity.getString(ca.cineflight.stage.R.string.pr_td_var_simple),
            "spirale" to activity.getString(ca.cineflight.stage.R.string.pr_td_var_spirale),
            "orbite_haute" to activity.getString(ca.cineflight.stage.R.string.pr_td_var_orbite))
        for ((cle, libelle) in variantes) {
            col.addView(espace(6))
            col.addView(carteChoixTop("var", cle, libelle))
        }

        // 3) Sens (monter = eloignement, descendre = rapprochement)
        col.addView(espace(12))
        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            l.addView(TextView(activity).apply {
                text = if (topDescendre) activity.getString(ca.cineflight.stage.R.string.pr_td_sens_desc) else activity.getString(ca.cineflight.stage.R.string.pr_td_sens_monte)
                textSize = 15f; setTextColor(TEXTE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            l.addView(TextView(activity).apply {
                text = "\u21C5"; textSize = 20f; setTextColor(ACCENT)
            })
            l.setOnClickListener { topDescendre = !topDescendre; afficherTopDown() }
            l
        })

        // 4) Consentement (obligatoire si sujet isole) + avertissement
        if (topContexte == "sujet_isole_consenti") {
            col.addView(espace(12))
            col.addView(cadre(pleineLargeur = true) {
                val l = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                }
                l.addView(TextView(activity).apply {
                    text = (if (topConsentement) "\u2611" else "\u2610") +
                           activity.getString(ca.cineflight.stage.R.string.pr_td_consent)
                    textSize = 14f
                    setTextColor(if (topConsentement) Color.WHITE else 0xFFE57373.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                })
                l.setOnClickListener { topConsentement = !topConsentement; afficherTopDown() }
                l
            })
        }
        col.addView(espace(8))
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_td_avert)
                textSize = 13f; setTextColor(0xFFE57373.toInt())
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        })

        // 5) Bouton de generation
        col.addView(espace(12))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_gen_td); isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { lancerTopDown() }
        })

        overlay.addView(scroll)
    }

    /** Carte de selection top-down (groupe "ctx" = contexte, "var" = variante). */
    private fun carteChoixTop(groupe: String, cle: String, libelle: String): View {
        val actif = if (groupe == "ctx") (cle == topContexte) else (cle == topVariante)
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            l.addView(TextView(activity).apply {
                text = libelle; textSize = 16f
                setTextColor(if (actif) Color.WHITE else TEXTE)
                setTypeface(typeface, if (actif) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (actif) l.addView(TextView(activity).apply {
                text = "\u2713"; textSize = 18f; setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD)
            })
            l.setOnClickListener {
                if (groupe == "ctx") {
                    topContexte = cle
                    if (cle != "sujet_isole_consenti") topConsentement = false
                } else topVariante = cle
                afficherTopDown()
            }
            l
        }
    }

    private fun lancerTopDown() {
        val analyseur = analyseurLieu ?: return
        val lancer = lancerCoroutine ?: return
        val overlay = racine ?: return

        // garde locale : sujet isole exige le consentement coche
        if (topContexte == "sujet_isole_consenti" && !topConsentement) {
            android.widget.Toast.makeText(activity,
                activity.getString(ca.cineflight.stage.R.string.pr_toast_consent),
                android.widget.Toast.LENGTH_LONG).show()
            return
        }

        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_td_titre), activity.getString(ca.cineflight.stage.R.string.pr_recherche_gps)) { afficherTopDown() })
        val txtEtat = TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_obtention_gps_td)
            textSize = 14f; setTextColor(TEXTE_DOUX); setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        col.addView(cadre(pleineLargeur = true) { txtEtat })
        overlay.addView(scroll)

        val ctx = topContexte; val varnt = topVariante
        val desc = topDescendre; val cons = topConsentement
        val traiter: (Pair<Double, Double>?) -> Unit = { position ->
            activity.runOnUiThread {
                if (!estOuvert) return@runOnUiThread
                if (position == null) {
                    afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_gps_impossible))
                    return@runOnUiThread
                }
                if (!analyseur.disponible()) {
                    afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_td_internet))
                    return@runOnUiThread
                }
                val (lat, lon) = position
                txtEtat.text = activity.getString(ca.cineflight.stage.R.string.pr_pos_trouvee_td)
                lancer {
                    val res = try {
                        analyseur.genererTopDown(lat, lon, ctx, varnt, desc, cons)
                    } catch (_: Exception) { null }
                    activity.runOnUiThread {
                        if (!estOuvert) return@runOnUiThread
                        if (res == null) {
                            val detail = analyseur.derniereErreur?.let { activity.getString(ca.cineflight.stage.R.string.pr_detail_fmt, it) } ?: ""
                            afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_td_echec_fmt, detail))
                        } else {
                            afficherTopDownGenere(res)
                        }
                    }
                }
            }
        }
        val obtenirPosition = demanderPositionFraiche
        if (obtenirPosition != null) obtenirPosition(traiter)
        else traiter(fournirPosition?.invoke())
    }

    /** Telecharge le KMZ du plan de haut puis execute une action dessus (vol reel,
     *  simulation, ou export). Evite de dupliquer la logique de telechargement. */
    private fun avecKmzParPlan(planId: String, nomFichier: String, bouton: Button, action: (java.io.File) -> Unit) {
        val analyseur = analyseurLieu ?: return
        val lancer = lancerCoroutine ?: return
        val dossier = activity.getExternalFilesDir(null) ?: activity.filesDir
        val fichier = java.io.File(dossier, nomFichier)
        val libelle = bouton.text
        bouton.text = activity.getString(ca.cineflight.stage.R.string.pr_gen_kmz); bouton.isEnabled = false
        lancer {
            val fic = try { analyseur.telechargerKmzParPlanId(planId, fichier) } catch (_: Exception) { null }
            activity.runOnUiThread {
                if (!estOuvert) return@runOnUiThread
                bouton.text = libelle; bouton.isEnabled = true
                if (fic == null) {
                    val raison = analyseur.derniereErreur
                    val msg = activity.getString(ca.cineflight.stage.R.string.pr_export_kmz_echec) +
                              (if (!raison.isNullOrBlank()) "\n\nCause exacte :\n$raison" else "")
                    // Boite de dialogue (pas un toast) pour que le message complet soit lisible.
                    androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle(activity.getString(ca.cineflight.stage.R.string.pr_dl_mission_impossible))
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else action(fic)
            }
        }
    }

    /** Ecran de confirmation top-down + export KMZ. */
    private fun afficherTopDownGenere(res: TopDownResultat) {
        val analyseur = analyseurLieu ?: return
        val lancer = lancerCoroutine ?: return
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_td_pret),
            activity.getString(ca.cineflight.stage.R.string.pr_variante_fmt, res.variante)) { afficherTopDown() })

        col.addView(espace(8))
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_td_pret_msg)
                textSize = 14f; setTextColor(TEXTE); setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        })

        // 1) PRINCIPAL : lancer le vol REEL dans l'app (si dispo).
        val cbVol = lancerMissionReelle
        if (cbVol != null) {
            col.addView(espace(12))
            col.addView(Button(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_lancer_vol_app); isAllCaps = false; textSize = 16f
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                setOnClickListener { avecKmzParPlan(res.planId, "topdown_cineflight.kmz", this) { fic -> cbVol(fic) } }
            })
        }

        // 2) SIMULER d'abord (le vrai drone ne bouge pas).
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_voir_carte_simuler); isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                avecKmzParPlan(res.planId, "topdown_cineflight.kmz", this) { fic ->
                    val it = android.content.Intent(activity, ca.cineflight.stage.CarteMissionActivity::class.java)
                    it.putExtra("kmz_path", fic.absolutePath)
                    activity.startActivity(it)
                }
            }
        })

        // 3) SECONDAIRE : exporter le fichier (partage), pour ceux qui le veulent.
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_exporter_kmz); isAllCaps = false; textSize = 14f
            setTextColor(TEXTE_DOUX)
            backgroundTintList = android.content.res.ColorStateList.valueOf(CARTE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            setOnClickListener { avecKmzParPlan(res.planId, "topdown_cineflight.kmz", this) { fic -> partagerKmzIntent(fic) } }
        })

        overlay.addView(scroll)
    }

    private fun espace(h: Int) = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h))
    }

    // Carte large (Premier vol)
    private fun carteSpeciale(r: Recette, badge: String): View {
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = "${r.emoji}  ${r.nom}"; textSize = 19f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = r.accroche; textSize = 13f; setTextColor(TEXTE_DOUX)
                setPadding(0, dp(2), 0, 0)
            })
            l.addView(TextView(activity).apply {
                text = "$badge · ⏱️ ~${r.dureeS} s"; textSize = 11f; setTextColor(ACCENT)
                setPadding(0, dp(4), 0, 0)
            })
            l.setOnClickListener { choisirRecette(r) }
            l
        }
    }

    // Carte "Surprends-moi" : demande des faits simples puis decide
    private fun carteSurprends(): View {
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_surprends); textSize = 19f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_surprends_desc)
                textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            l.setOnClickListener { afficherSurprends() }
            l
        }
    }

    // Carte "Analyse auto" (mode A ou B) : le drone regarde la scene et propose
    // ===================== MENU : Preparer un tournage =====================
    fun afficherMenuReconnaissance() {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_prep_titre), activity.getString(ca.cineflight.stage.R.string.pr_prep_sous)) { fermer() })

        // Etape 1 : preparer la scene (deux facons : sur la carte OU avec des photos)
        col.addView(enTeteNiveau(activity.getString(ca.cineflight.stage.R.string.pr_etape1), activity.getString(ca.cineflight.stage.R.string.pr_etape1_sous)))
        if (onDefinirSujet != null) {
            col.addView(espace(8))
            col.addView(carteAction("🎯", activity.getString(ca.cineflight.stage.R.string.pr_definir_sujet), activity.getString(ca.cineflight.stage.R.string.pr_definir_sujet_desc)) { onDefinirSujet.invoke(); fermer() })
        }
        if (onAnalyserPhotos != null) {
            col.addView(espace(8))
            col.addView(boutonNiveau(activity.getString(ca.cineflight.stage.R.string.pr_avance), activity.getString(ca.cineflight.stage.R.string.pr_avance_desc), reperagePhotoOuvert) {
                reperagePhotoOuvert = !reperagePhotoOuvert; afficherMenuReconnaissance()
            })
            if (reperagePhotoOuvert) {
                col.addView(espace(8))
                col.addView(carteAction("📸", activity.getString(ca.cineflight.stage.R.string.pr_analyser_photos), activity.getString(ca.cineflight.stage.R.string.pr_analyser_photos_desc)) { onAnalyserPhotos.invoke(); fermer() })
            }
        }

        // Etape 2 : verifier et lancer
        if (onVerifierMission != null) {
            col.addView(enTeteNiveau(activity.getString(ca.cineflight.stage.R.string.pr_etape2), activity.getString(ca.cineflight.stage.R.string.pr_etape2_sous)))
            col.addView(espace(8))
            col.addView(carteAction("✅", activity.getString(ca.cineflight.stage.R.string.pr_verifier), activity.getString(ca.cineflight.stage.R.string.pr_verifier_desc)) { onVerifierMission.invoke(); fermer() })
        }

        // Bibliotheques de missions deja pretes (a part du parcours de preparation)
        col.addView(enTeteNiveau(activity.getString(ca.cineflight.stage.R.string.pr_missions_pretes), activity.getString(ca.cineflight.stage.R.string.pr_missions_pretes_sous)))
        if (onMissionsPreparees != null) {
            col.addView(espace(8))
            col.addView(carteAction("📱", activity.getString(ca.cineflight.stage.R.string.pr_missions_locale), activity.getString(ca.cineflight.stage.R.string.pr_missions_locale_desc)) { afficherMissionsPreparees() })
        }
        col.addView(espace(8))
        col.addView(carteAction("☁️", activity.getString(ca.cineflight.stage.R.string.pr_missions_web), activity.getString(ca.cineflight.stage.R.string.pr_missions_web_desc)) {
            activity.startActivity(android.content.Intent(activity, ca.cineflight.stage.MesMissionsActivity::class.java))
            fermer()
        })
        overlay.addView(scroll)
    }

    /** Carte-action generique (emoji + titre + description), style maison. */
    private fun carteAction(emoji: String, titre: String, desc: String, onClic: () -> Unit): View {
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = "$emoji  $titre"; textSize = 19f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = desc; textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            l.setOnClickListener { onClic() }
            l
        }
    }

    // ===================== Page : Mes missions preparees =====================
    /** Ouvre le panneau directement sur la page stylee "Mes missions preparees".
     *  Point d'entree unique pour les appels hors panneau (menu reco, sentinelle) :
     *  UNE seule UI de missions preparees partout. */
    fun ouvrirSurMissionsPreparees() {
        ouvrir()                     // cree l'overlay si besoin (no-op si deja ouvert)
        afficherMissionsPreparees()  // remplace le contenu par la liste stylee
    }

    fun afficherMissionsPreparees() {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_missions_locale_titre), activity.getString(ca.cineflight.stage.R.string.pr_missions_locale_sous)) { afficherMenuReconnaissance() })
        val missions = fournirMissionsPreparees?.invoke() ?: emptyList()
        if (missions.isEmpty()) {
            col.addView(espace(12))
            col.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_aucune_mission)
                textSize = 15f; setTextColor(TEXTE_DOUX); setPadding(dp(8), 0, dp(8), 0)
            })
            overlay.addView(scroll)
            return
        }
        val fmtDate = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
        for (m in missions) {
            col.addView(espace(8))
            col.addView(carteMission(m, fmtDate))
        }
        overlay.addView(scroll)
    }

    /** Carte cliquable representant une mission preparee (nom + details + chevron). */
    private fun carteMission(m: MissionsPrepareesStore.MissionPreparee, fmtDate: java.text.SimpleDateFormat): View {
        return cadre(pleineLargeur = true) {
            val ligne = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val txt = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titre = m.nomMission.ifBlank { m.momentNom.ifBlank { activity.getString(ca.cineflight.stage.R.string.pr_mission_defaut) } }
            txt.addView(TextView(activity).apply {
                text = titre; textSize = 18f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            val plage = if (m.momentDebut.isNotEmpty()) "${m.momentDebut} \u2192 ${m.momentFin}" else ""
            val sousLigne = listOf(m.momentNom, plage).filter { it.isNotBlank() }.joinToString("  \u00b7  ")
            if (sousLigne.isNotBlank()) txt.addView(TextView(activity).apply {
                text = sousLigne; textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            val datePrep = try { activity.getString(ca.cineflight.stage.R.string.pr_preparee_le_fmt, fmtDate.format(java.util.Date(m.preparesLe))) } catch (_: Exception) { "" }
            if (datePrep.isNotBlank()) txt.addView(TextView(activity).apply {
                text = datePrep; textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            ligne.addView(txt)
            ligne.addView(TextView(activity).apply {
                text = "\u203A"; textSize = 28f; setTextColor(ACCENT); setPadding(dp(8), 0, 0, 0)
            })
            ligne.setOnClickListener { onLancerMissionPreparee?.invoke(m.id); fermer() }
            ligne
        }
    }

    // ===================== Page : Reglages camera =====================
    /** Lit l'etat reel du drone et met a jour les valeurs actives (decodage noms d'enum bruts). */
    private fun lireEtatReel() {
        val e = fournirEtatCamera?.invoke() ?: return
        // e = [ev, iso, modeExpo, shutter, wb] noms d'enum bruts (ou null)
        decoderEvBrut(e.getOrNull(0))?.let { evActif = it }
        decoderIsoBrut(e.getOrNull(1))?.let { isoActif = it }
        decoderModeBrut(e.getOrNull(2))?.let { modeExpoActif = it }
        decoderShutterBrut(e.getOrNull(3))?.let { shutterActif = it }
        decoderWbBrut(e.getOrNull(4))?.let { wbActif = it }
    }

    // EV : NEG_2P0EV -> -2.0 ; POS_1P0EV -> 1.0 ; NEG_0P3EV -> -0.3 ; NEG_0EV -> 0
    private fun decoderEvBrut(n: String?): Float? {
        if (n == null) return null
        val m = Regex("(NEG|POS)_(\\d+)(?:P(\\d+))?EV").find(n) ?: return null
        val signe = if (m.groupValues[1] == "NEG") -1f else 1f
        val ent = m.groupValues[2].toFloat()
        val dec = m.groupValues[3].toFloatOrNull()?.let { it / 10f } ?: 0f
        return signe * (ent + dec)
    }
    // ISO : ISO_AUTO -> "Auto" ; ISO_400 -> "400"
    private fun decoderIsoBrut(n: String?): String? = when {
        n == null -> null
        n.contains("AUTO") -> "AUTO"
        else -> Regex("ISO_(\\d+)").find(n)?.groupValues?.get(1)
    }
    // Mode : PROGRAM->Auto ; MANUAL->Manuel ; SHUTTER_PRIORITY->Priorite vitesse ; APERTURE_PRIORITY->Priorite ouverture
    private fun decoderModeBrut(n: String?): String? = when {
        n == null -> null
        n.contains("MANUAL") -> "MANUAL"
        n.contains("SHUTTER") -> "SHUTTER"
        n.contains("APERTURE") -> "APERTURE"
        n.contains("PROGRAM") -> "AUTO"
        else -> null
    }
    // Vitesse : SHUTTER_SPEED1_120 -> "1/120" ; SHUTTER_SPEED1_60 -> "1/60"
    private fun decoderShutterBrut(n: String?): String? {
        if (n == null) return null
        val m = Regex("SHUTTER_SPEED1_(\\d+)$").find(n) ?: return null
        return "1/" + m.groupValues[1]
    }
    // WB : WB_AUTO->Auto ; WB_SUNNY->Soleil ; etc.
    private fun decoderWbBrut(n: String?): String? = when {
        n == null -> null
        n.contains("SUNNY") -> "SUNNY"
        n.contains("CLOUDY") -> "CLOUDY"
        n.contains("INCANDESCENT") -> "INCANDESCENT"
        n.contains("FLUORESCENT") -> "FLUORESCENT"
        n.contains("AUTO") -> "AUTO"
        else -> null
    }

    fun afficherReglagesCamera() {
        val overlay = racine ?: return
        lireEtatReel()   // initialise les valeurs depuis l'etat reel du drone
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_cam_titre), activity.getString(ca.cineflight.stage.R.string.pr_cam_sous)) { fermer() })

        if (onReglerEv != null) { col.addView(espace(8)); col.addView(carteReglage("\u2600\uFE0F", activity.getString(ca.cineflight.stage.R.string.pr_cam_expo), evLabelPan(evActif), null) {
            val opts = listOf("+2","+1","+0.3","0","-0.3","-1","-2")
            choixReglage(activity.getString(ca.cineflight.stage.R.string.pr_cam_expo), opts, opts, evCleActive()) { v ->
                val ev = v.replace("+","").toFloatOrNull() ?: 0f; evActif = ev; onReglerEv.invoke(ev) } } ) }
        if (onReglerModeExpo != null) { col.addView(espace(8)); col.addView(carteReglage("\uD83D\uDCF7", activity.getString(ca.cineflight.stage.R.string.pr_cam_mode_expo), libMode(modeExpoActif), null) {
            val codes = listOf("AUTO","MANUAL","SHUTTER","APERTURE")
            choixReglage(activity.getString(ca.cineflight.stage.R.string.pr_cam_mode_expo), codes.map { libMode(it) }, codes, modeExpoActif) { v ->
                modeExpoActif = v; onReglerModeExpo.invoke(v) } } ) }
        if (onReglerIso != null) { col.addView(espace(8)); col.addView(carteReglage("\uD83C\uDF9E", activity.getString(ca.cineflight.stage.R.string.pr_cam_iso), libIso(isoActif), activity.getString(ca.cineflight.stage.R.string.pr_cam_mode_requis)) {
            val codes = listOf("AUTO","100","200","400","800","1600","3200")
            choixReglage(activity.getString(ca.cineflight.stage.R.string.pr_cam_iso), codes.map { libIso(it) }, codes, isoActif) { v ->
                isoActif = v; onReglerIso.invoke(if (v == "AUTO") 0 else v.toIntOrNull() ?: 0) } } ) }
        if (onReglerShutter != null) { col.addView(espace(8)); col.addView(carteReglage("\u23F1", activity.getString(ca.cineflight.stage.R.string.pr_cam_vitesse), shutterActif, activity.getString(ca.cineflight.stage.R.string.pr_cam_mode_requis)) {
            val opts = listOf("1/30","1/60","1/120","1/240","1/500","1/1000","1/2000","1/4000","1/8000")
            choixReglage(activity.getString(ca.cineflight.stage.R.string.pr_cam_vitesse_dlg), opts, opts, shutterActif) { v ->
                shutterActif = v; onReglerShutter.invoke(v) } } ) }
        if (onReglerWb != null) { col.addView(espace(8)); col.addView(carteReglage("\uD83C\uDF24", activity.getString(ca.cineflight.stage.R.string.pr_cam_wb), libWb(wbActif), null) {
            val codes = listOf("AUTO","SUNNY","CLOUDY","INCANDESCENT","FLUORESCENT")
            choixReglage(activity.getString(ca.cineflight.stage.R.string.pr_cam_wb), codes.map { libWb(it) }, codes, wbActif) { v ->
                wbActif = v; onReglerWb.invoke(v) } } ) }
        if (onReglerResFps != null) { col.addView(espace(8)); col.addView(carteReglage("\uD83C\uDFA5", activity.getString(ca.cineflight.stage.R.string.pr_cam_resfps), resFpsActif, null) {
            val opts = listOf("4K30","2.7K30","1080p60","1080p30")
            choixReglage(activity.getString(ca.cineflight.stage.R.string.pr_cam_resfps), opts, opts, resFpsActif) { v ->
                resFpsActif = v; val (res,fps) = decoderResFps(v); onReglerResFps.invoke(res, fps) } } ) }
        overlay.addView(scroll)
    }

    private fun evLabelPan(v: Float): String = when { kotlin.math.abs(v) < 0.05f -> "0"; v > 0 -> "+%.1f".format(v); else -> "%.1f".format(v) }

    /** Convertit evActif (Float) en la cle texte exacte des options EV (+1, 0, +0.3...). */
    private fun evCleActive(): String = when {
        kotlin.math.abs(evActif) < 0.05f -> "0"
        kotlin.math.abs(evActif - 0.3f) < 0.05f -> "+0.3"
        kotlin.math.abs(evActif + 0.3f) < 0.05f -> "-0.3"
        evActif > 0 -> "+" + evActif.toInt()
        else -> evActif.toInt().toString()
    }

    private fun decoderResFps(v: String): Pair<String, Int> {
        val res = when { v.startsWith("4K") -> "4K"; v.startsWith("2.7K") -> "2.7K"; else -> "1080" }
        val fps = Regex("(\\d+)$").find(v)?.groupValues?.get(1)?.toIntOrNull() ?: 30
        return res to fps
    }

    /** Carte d un reglage : emoji + nom + valeur active + note optionnelle. */
    private fun carteReglage(emoji: String, nom: String, valeur: String, note: String?, onClic: () -> Unit): View {
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply { text = "$emoji  $nom"; textSize = 17f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
            l.addView(TextView(activity).apply { text = valeur; textSize = 15f; setTextColor(ACCENT); setPadding(0, dp(2), 0, 0) })
            if (note != null) l.addView(TextView(activity).apply { text = note; textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(1), 0, 0) })
            l.setOnClickListener { onClic() }
            l
        }
    }

    /** Sous-choix : liste de valeurs ; au clic applique puis revient a la page reglages. */
    /** Sous-choix : liste de valeurs avec coche sur la valeur active. */
    private fun libMode(code: String) = when (code) {
        "MANUAL" -> activity.getString(ca.cineflight.stage.R.string.pr_cam_manuel)
        "SHUTTER" -> activity.getString(ca.cineflight.stage.R.string.pr_cam_prio_vitesse)
        "APERTURE" -> activity.getString(ca.cineflight.stage.R.string.pr_cam_prio_ouverture)
        else -> activity.getString(ca.cineflight.stage.R.string.pr_cam_auto)
    }
    private fun libWb(code: String) = when (code) {
        "SUNNY" -> activity.getString(ca.cineflight.stage.R.string.pr_cam_soleil)
        "CLOUDY" -> activity.getString(ca.cineflight.stage.R.string.pr_cam_nuageux)
        "INCANDESCENT" -> activity.getString(ca.cineflight.stage.R.string.pr_cam_incandescent)
        "FLUORESCENT" -> activity.getString(ca.cineflight.stage.R.string.pr_cam_fluorescent)
        else -> activity.getString(ca.cineflight.stage.R.string.pr_cam_auto)
    }
    private fun libIso(code: String) = if (code == "AUTO") activity.getString(ca.cineflight.stage.R.string.pr_cam_auto) else code

    private fun choixReglage(titre: String, labels: List<String>, codes: List<String>, actifCode: String, onChoix: (String) -> Unit) {
        val items = labels.mapIndexed { i, lab -> if (codes[i] == actifCode) "\u2713  $lab" else "      $lab" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, ca.cineflight.stage.R.style.DialogCineFlight)
            .setTitle(titre)
            .setItems(items) { _, i -> onChoix(codes[i]); afficherReglagesCamera() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun carteAnalyse(): View {
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_analyse_auto); textSize = 19f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_analyse_auto_desc)
                textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            l.setOnClickListener { afficherChoixAnalyse() }
            l
        }
    }

    // Assistant de tournage : quand filmer ce lieu aujourd'hui (lumieres du jour)
    private fun carteLumieres(): View {
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_lumieres); textSize = 19f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_lumieres_desc)
                textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            l.setOnClickListener {
                val it = android.content.Intent(activity, ca.cineflight.stage.MomentsJourneeActivity::class.java)
                fournirPosition?.invoke()?.let { (lat, lon) ->
                    it.putExtra("lat", lat); it.putExtra("lon", lon)
                }
                activity.startActivity(it)
            }
            l
        }
    }


    // Choix du type d'analyse : fixe (A) ou panoramique (B)
    private fun afficherChoixAnalyse() {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_analyse_auto_titre), activity.getString(ca.cineflight.stage.R.string.pr_comment_regarder)) { afficherEtape1() })

        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_vue_fixe); textSize = 17f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_vue_fixe_desc)
                textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            l.setOnClickListener { afficherAnalyseEnCours(pivoter = false) }
            l
        })
        col.addView(espace(8))
        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_vue_pano); textSize = 17f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_vue_pano_desc)
                textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_vue_pano_note)
                textSize = 11f; setTextColor(0xFFFFB74D.toInt()); setPadding(0, dp(4), 0, 0)
            })
            l.setOnClickListener { afficherAnalyseEnCours(pivoter = true) }
            l
        })
        overlay.addView(scroll)
    }

    // Ecran d'attente pendant l'observation
    private fun afficherAnalyseEnCours(pivoter: Boolean) {
        val declencheur = lancerAnalyse ?: return
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_analyse_auto_titre),
            if (pivoter) activity.getString(ca.cineflight.stage.R.string.pr_balayage_encours) else activity.getString(ca.cineflight.stage.R.string.pr_observation_scene)) { afficherEtape1() })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = if (pivoter)
                    activity.getString(ca.cineflight.stage.R.string.pr_pivote_balaye)
                else
                    activity.getString(ca.cineflight.stage.R.string.pr_regarde_scene)
                textSize = 15f; setTextColor(TEXTE_DOUX)
            }
        })
        overlay.addView(scroll)
        // lance la passe ; le resultat revient sur le thread UI
        declencheur(pivoter) { rapport -> if (estOuvert) afficherResultatAnalyse(rapport) }
    }

    // Ecran de resultat : "Je vois ..." + suggestions honnetes
    private fun afficherResultatAnalyse(rapport: RapportReperage) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_ce_que_vois), activity.getString(ca.cineflight.stage.R.string.pr_faits_mesures)) { afficherEtape1() })

        // Le "Je vois ..." honnete (faits uniquement)
        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = rapport.resumeHonnete(activity); textSize = 16f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            if (rapport.confiance < 0.6f) {
                l.addView(TextView(activity).apply {
                    text = activity.getString(ca.cineflight.stage.R.string.pr_scene_instable)
                    textSize = 12f; setTextColor(0xFFFFB74D.toInt()); setPadding(0, dp(4), 0, 0)
                })
            }
            l
        })
        col.addView(espace(10))
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_plans_suggeres); textSize = 11f
            setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), 0, 0, dp(6))
        })
        // suggestions issues des faits
        for (r in Catalogue.suggestions(rapport)) {
            col.addView(cadre(pleineLargeur = true) {
                val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                l.addView(TextView(activity).apply {
                    text = "${r.emoji}  ${r.nom}"; textSize = 17f
                    setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                })
                l.addView(TextView(activity).apply {
                    text = r.accroche; textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
                })
                l.setOnClickListener { choisirRecette(r) }
                l
            })
            col.addView(espace(8))
        }
        overlay.addView(scroll)
    }

    // ===================== VISION IA (couche premium) =====================
    private fun carteVision(): View {
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_analyser_lieu); textSize = 19f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_analyser_lieu_desc)
                textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            l.setOnClickListener { lancerVision() }
            l
        }
    }

    private fun lancerVision() {
        val analyseur = analyseurVision
        val capturer = capturerImages
        val lancer = lancerCoroutine
        if (analyseur == null || lancer == null) return

        // FALLBACK hors-ligne : si l'analyse vision n'est pas disponible, on bascule
        // sur l'analyse YOLO (si presente) ou le choix manuel, sans rupture.
        if (!analyseur.disponible()) {
            afficherVisionIndisponible()
            return
        }

        // ecran d'attente
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_analyse_lieu_titre), activity.getString(ca.cineflight.stage.R.string.pr_ia_observe)) { afficherEtape1() })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_analyse_encours)
                textSize = 15f; setTextColor(TEXTE_DOUX)
            }
        })
        overlay.addView(scroll)

        lancer {
            val images = try { capturer?.invoke() ?: emptyList() } catch (_: Exception) { emptyList() }
            val rapport = try { analyseur.analyser(images) } catch (_: Exception) { null }
            activity.runOnUiThread {
                if (!estOuvert) return@runOnUiThread
                if (rapport == null) afficherVisionIndisponible()
                else afficherResultatVision(rapport)
            }
        }
    }

    // Resultat vision : "Je vois ..." + suggestions issues des cles LLM
    private fun afficherResultatVision(rapport: RapportVision) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_ce_que_ia_voit), activity.getString(ca.cineflight.stage.R.string.pr_suggestions_conf)) { afficherEtape1() })

        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = rapport.resume(); textSize = 16f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_ambiance_fmt, rapport.ambiance); textSize = 13f
                setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, 0)
            })
            l
        })
        col.addView(espace(10))
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_plans_suggeres); textSize = 11f
            setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), 0, 0, dp(6))
        })
        // resout les cles LLM -> recettes reelles (cles inconnues ignorees)
        for (r in Catalogue.parRecettesCles(rapport.recettesCles)) {
            col.addView(cadre(pleineLargeur = true) {
                val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                l.addView(TextView(activity).apply {
                    text = "${r.emoji}  ${r.nom}"; textSize = 17f
                    setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                })
                l.addView(TextView(activity).apply {
                    text = r.accroche; textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
                })
                l.setOnClickListener { choisirRecette(r) }
                l
            })
            col.addView(espace(8))
        }
        overlay.addView(scroll)
    }

    // Fallback : vision indisponible -> on propose les voies hors-ligne
    private fun afficherVisionIndisponible() {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_ia_indispo), activity.getString(ca.cineflight.stage.R.string.pr_pas_reseau)) { afficherEtape1() })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_ia_besoin_internet)
                textSize = 14f; setTextColor(TEXTE_DOUX)
            }
        })
        col.addView(espace(10))
        if (lancerAnalyse != null) {
            col.addView(Button(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_utiliser_analyse_hl); isAllCaps = false; textSize = 15f
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                setOnClickListener { afficherChoixAnalyse() }
            })
            col.addView(espace(8))
        }
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_choisir_recette); isAllCaps = false; textSize = 15f
            setTextColor(TEXTE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF455A64.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { afficherEtape1() }
        })
        overlay.addView(scroll)
    }

    // ===================== MODE 3 : PREPARER LE LIEU (techno Explorer) =====================
    private fun carteLieu(): View {
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_preparer_lieu); textSize = 19f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_preparer_lieu_desc)
                textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            l.setOnClickListener { afficherChoixLieu() }
            l
        }
    }

    /** Ecran de choix : sur place (GPS) ou recherche par adresse (planification). */
    private fun afficherChoixLieu() {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_preparer_lieu_titre), activity.getString(ca.cineflight.stage.R.string.pr_sur_place_plan)) { afficherEtape1() })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_analysez_decor)
                textSize = 13f; setTextColor(TEXTE_DOUX)
            }
        })
        col.addView(espace(10))

        // ── Selecteur de profil de reperage (ce que l'utilisateur aime filmer) ──
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_je_cherche); textSize = 11f
            setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), 0, 0, dp(6))
        })
        val profilsCles = listOf("", "spectaculaire", "relief", "eau", "patrimoine",
                                  "urbain", "touristique", "calme")
        val profilsNoms = activity.resources.getStringArray(ca.cineflight.stage.R.array.pr_profils_noms).toList()
        val spinner = android.widget.Spinner(activity)
        val adapter = android.widget.ArrayAdapter(activity,
            android.R.layout.simple_spinner_dropdown_item, profilsNoms)
        spinner.adapter = adapter
        // position courante selon profilChoisi
        val idxCourant = profilsCles.indexOf(profilChoisi ?: "")
        if (idxCourant >= 0) spinner.setSelection(idxCourant)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                profilChoisi = profilsCles[pos].ifBlank { null }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        col.addView(cadre(pleineLargeur = true) { spinner })
        col.addView(espace(10))

        // Choix 1 : je suis sur place (GPS)
        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_sur_place); textSize = 17f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_sur_place_desc)
                textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            l.setOnClickListener { lancerLieu() }
            l
        })
        col.addView(espace(8))
        // Choix 2 : chercher une adresse (planification)
        if (geocoderAdresse != null) {
            col.addView(cadre(pleineLargeur = true) {
                val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                l.addView(TextView(activity).apply {
                    text = activity.getString(ca.cineflight.stage.R.string.pr_chercher_adresse); textSize = 17f
                    setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                })
                l.addView(TextView(activity).apply {
                    text = activity.getString(ca.cineflight.stage.R.string.pr_chercher_adresse_desc)
                    textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
                })
                l.setOnClickListener { afficherRechercheAdresse() }
                l
            })
        }
        overlay.addView(scroll)
    }

    /** Ecran de saisie d'adresse -> geocode -> analyse. */
    private fun afficherRechercheAdresse() {
        val geocoder = geocoderAdresse ?: return
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_chercher_adresse_titre), activity.getString(ca.cineflight.stage.R.string.pr_lieu_ville)) { afficherChoixLieu() })

        val champ = android.widget.EditText(activity).apply {
            hint = activity.getString(ca.cineflight.stage.R.string.pr_hint_adresse)
            textSize = 16f; setTextColor(TEXTE)
            setHintTextColor(TEXTE_DOUX)
            setSingleLine(true)
        }
        col.addView(cadre(pleineLargeur = true) { champ })
        col.addView(espace(10))

        val txtEtat = TextView(activity).apply {
            text = ""; textSize = 13f; setTextColor(TEXTE_DOUX)
        }

        val btnChercher = Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_chercher_analyser); isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            setOnClickListener {
                val adresse = champ.text.toString().trim()
                if (adresse.isEmpty()) {
                    txtEtat.text = activity.getString(ca.cineflight.stage.R.string.pr_entrez_lieu)
                    return@setOnClickListener
                }
                txtEtat.text = activity.getString(ca.cineflight.stage.R.string.pr_recherche_adresse_fmt, adresse)
                isEnabled = false
                geocoder(adresse) { res ->
                    activity.runOnUiThread {
                        if (!estOuvert) return@runOnUiThread
                        isEnabled = true
                        if (res == null) {
                            txtEtat.text = activity.getString(ca.cineflight.stage.R.string.pr_lieu_introuvable)
                        } else {
                            val (lat, lon, label) = res
                            // enchaine sur l'analyse a ces coordonnees
                            analyserPosition(lat, lon, label)
                        }
                    }
                }
            }
        }
        col.addView(btnChercher)
        col.addView(espace(8))
        col.addView(txtEtat)
        overlay.addView(scroll)
    }

    private fun lancerLieu() {
        val analyseur = analyseurLieu ?: return
        val lancer = lancerCoroutine ?: return

        // Afficher tout de suite l'ecran d'attente (recherche position + analyse)
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_analyse_lieu_pin), activity.getString(ca.cineflight.stage.R.string.pr_recherche_encours)) { afficherEtape1() })
        val txtEtat = TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_recherche_gps)
            textSize = 15f; setTextColor(TEXTE_DOUX)
        }
        col.addView(cadre(pleineLargeur = true) { txtEtat })
        overlay.addView(scroll)

        // 1) obtenir une position (fraiche si possible), SANS echouer tout de suite
        val obtenirPosition = demanderPositionFraiche
        val traiter: (Pair<Double, Double>?) -> Unit = { position ->
            activity.runOnUiThread {
                if (!estOuvert) return@runOnUiThread
                if (position == null) {
                    afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_gps_impossible_lieu))
                    return@runOnUiThread
                }
                if (!analyseur.disponible()) {
                    afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_analyse_internet))
                    return@runOnUiThread
                }
                txtEtat.text = activity.getString(ca.cineflight.stage.R.string.pr_pos_trouvee_lieu)
                val (lat, lon) = position
                dernierLieuSurPlace = true   // GPS reel = decollage possible d'ici
                lancer {
                    val rapport = try { analyseur.analyser(lat, lon, 1500, profilChoisi) } catch (_: Exception) { null }
                    activity.runOnUiThread {
                        if (!estOuvert) return@runOnUiThread
                        if (rapport == null || rapport.recettes.isEmpty()) {
                            val détail = analyseur.derniereErreur?.let { activity.getString(ca.cineflight.stage.R.string.pr_detail_fmt, it) } ?: ""
                            afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_analyse_echec_fmt, détail))
                        } else afficherResultatLieu(rapport)
                    }
                }
            }
        }

        if (obtenirPosition != null) {
            obtenirPosition(traiter)               // asynchrone : attend un vrai fix
        } else {
            traiter(fournirPosition?.invoke())     // repli : ancienne methode synchrone
        }
    }

    /** Analyse une position donnee (lat/lon) — utilise par le GPS ET la recherche d'adresse. */
    /** Ouvre la carte centree sur UN SEUL point (clic sur un lieu precis).
     *  Affiche la distance a vol d'oiseau dans le titre du marqueur. */
    private fun ouvrirCarteUnPoint(nom: String, label: String, lat: Double, lon: Double,
                                   centreLat: Double, centreLon: Double) {
        try {
            val arr = org.json.JSONArray()
            val o = org.json.JSONObject()
            o.put("type", label); o.put("label", label)
            o.put("nom", if (nom.isNotBlank() && !nom.contains("sans nom")) nom else label)
            o.put("lat", lat); o.put("lon", lon); o.put("score", 0)
            arr.put(o)
            val it = android.content.Intent(activity, ca.cineflight.stage.CartePointsActivity::class.java)
            it.putExtra("points_json", arr.toString())
            it.putExtra("centre_lat", centreLat)
            it.putExtra("centre_lon", centreLon)
            activity.startActivity(it)
        } catch (_: Exception) { }
    }

    private fun analyserPosition(lat: Double, lon: Double, label: String) {
        dernierLieuSurPlace = false   // lieu cherche par adresse = planification
        val analyseur = analyseurLieu ?: return
        val lancer = lancerCoroutine ?: return
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_analyse_lieu_pin), label) { afficherChoixLieu() })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_analyse_relief)
                textSize = 15f; setTextColor(TEXTE_DOUX)
            }
        })
        overlay.addView(scroll)
        if (!analyseur.disponible()) {
            afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_analyse_internet))
            return
        }
        lancer {
            val rapport = try { analyseur.analyser(lat, lon, 1500, profilChoisi) } catch (_: Exception) { null }
            activity.runOnUiThread {
                if (!estOuvert) return@runOnUiThread
                if (rapport == null || rapport.recettes.isEmpty()) {
                    val détail = analyseur.derniereErreur?.let { activity.getString(ca.cineflight.stage.R.string.pr_detail_fmt, it) } ?: ""
                    afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_analyse_echec_fmt, détail))
                } else afficherResultatLieu(rapport)
            }
        }
    }

    private fun afficherResultatLieu(rapport: RapportLieu) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_reco_ici), activity.getString(ca.cineflight.stage.R.string.pr_base_decor)) { afficherEtape1() })

        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = rapport.resume(); textSize = 16f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            if (rapport.ingredientsResume.isNotEmpty()) {
                l.addView(TextView(activity).apply {
                    text = activity.getString(ca.cineflight.stage.R.string.pr_detecte_fmt, rapport.ingredientsResume.joinToString(", ") { it.substringBefore(":") })
                    textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, 0)
                })
            }
            l
        })
        col.addView(espace(10))

        // ── Message honnete sur le niveau du lieu ──
        if (rapport.message.isNotBlank()) {
            val couleurNiveau = when (rapport.niveau) {
                "excellent" -> 0xFF4CAF50.toInt()
                "correct"   -> 0xFFFFA000.toInt()
                else         -> 0xFFE57373.toInt()
            }
            col.addView(cadre(pleineLargeur = true) {
                TextView(activity).apply {
                    text = rapport.message; textSize = 14f
                    setTextColor(couleurNiveau); setTypeface(typeface, Typeface.BOLD)
                }
            })
            col.addView(espace(8))
        }

        // ── SECTION 1 : TOUT PROCHE (filmable sur place) ──
        if (rapport.points.isNotEmpty()) {
            col.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_tout_proche); textSize = 11f
                setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), 0, 0, dp(6))
            })
            for (p in rapport.points.filter { it.score > 0 }.take(8)) {
                val nomAff = if (p.nom.isNotBlank() && !p.nom.contains("sans nom")) p.nom else p.label
                col.addView(cadre(pleineLargeur = true) {
                    val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    l.addView(TextView(activity).apply {
                        text = nomAff; textSize = 15f
                        setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                    })
                    l.addView(TextView(activity).apply {
                        text = if (p.distanceM > 0) "${p.label} · ${p.distanceM} m" else p.label
                        textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
                    })
                    // duree de vol estimee : total visible + detail en petit
                    if (p.volTotalMin > 0) {
                        val couleurVol = if (p.volDepasseAutonomie) 0xFFE57373.toInt() else 0xFF4CAF50.toInt()
                        l.addView(TextView(activity).apply {
                            text = activity.getString(ca.cineflight.stage.R.string.pr_min_vol_fmt, p.volTotalMin) +
                                   (if (p.volDepasseAutonomie) activity.getString(ca.cineflight.stage.R.string.pr_batterie) else "")
                            textSize = 12f; setTextColor(couleurVol)
                            setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(3), 0, 0)
                        })
                        l.addView(TextView(activity).apply {
                            text = activity.getString(ca.cineflight.stage.R.string.pr_vol_detail_fmt, p.volTransitAllerS, p.volFilmageS, p.volTransitRetourS)
                            textSize = 10f; setTextColor(TEXTE_DOUX); setPadding(0, dp(1), 0, 0)
                        })
                    }
                    // clic : carte centree sur CE point seul
                    l.setOnClickListener { ouvrirCarteUnPoint(p.nom, p.label, p.lat, p.lon, rapport.centreLat, rapport.centreLon) }
                    l
                })
                col.addView(espace(6))
            }
            col.addView(espace(4))
        }

        // ── Bouton : voir tous les lieux sur NOTRE carte ──
        if (rapport.points.isNotEmpty()) {
            val btnCarte = Button(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_voir_carte); isAllCaps = false; textSize = 15f
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                setOnClickListener {
                    try {
                        val arr = org.json.JSONArray()
                        for (p in rapport.points) {
                            val o = org.json.JSONObject()
                            o.put("type", p.type); o.put("label", p.label)
                            o.put("nom", p.nom); o.put("lat", p.lat)
                            o.put("lon", p.lon); o.put("score", p.score)
                            arr.put(o)
                        }
                        for (s in rapport.suggestions) {
                            val o = org.json.JSONObject()
                            o.put("type", s.type); o.put("label", s.label)
                            o.put("nom", s.nom); o.put("lat", s.lat)
                            o.put("lon", s.lon); o.put("score", 0)
                            arr.put(o)
                        }
                        val it = android.content.Intent(activity, ca.cineflight.stage.CartePointsActivity::class.java)
                        it.putExtra("points_json", arr.toString())
                        it.putExtra("centre_lat", rapport.centreLat)
                        it.putExtra("centre_lon", rapport.centreLon)
                        activity.startActivity(it)
                    } catch (_: Exception) { }
                }
            }
            col.addView(btnCarte)
            col.addView(espace(12))
        }

        // ── SECTION 2 : CA VAUT LE DETOUR (a portee de voiture) ──
        if (rapport.suggestions.isNotEmpty()) {
            col.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_vaut_detour); textSize = 11f
                setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), 0, 0, dp(6))
            })
            for (s in rapport.suggestions) {
                col.addView(cadre(pleineLargeur = true) {
                    val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    l.addView(TextView(activity).apply {
                        text = "${s.nom}"; textSize = 15f
                        setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                    })
                    l.addView(TextView(activity).apply {
                        text = "${s.label} · ${s.distanceKm} km"
                        textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
                    })
                    // duree : route voiture + vol sur place (detail en petit)
                    if (s.trajetVoitureMin > 0) {
                        l.addView(TextView(activity).apply {
                            text = activity.getString(ca.cineflight.stage.R.string.pr_min_route_fmt, s.trajetVoitureMin.toInt())
                            textSize = 12f; setTextColor(0xFFFFA000.toInt())
                            setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(3), 0, 0)
                        })
                        l.addView(TextView(activity).apply {
                            text = activity.getString(ca.cineflight.stage.R.string.pr_min_vol_place_fmt, s.volSurPlaceMin)
                            textSize = 10f; setTextColor(TEXTE_DOUX); setPadding(0, dp(1), 0, 0)
                        })
                    }
                    // clic : carte centree sur CE site seul
                    l.setOnClickListener { ouvrirCarteUnPoint(s.nom, s.label, s.lat, s.lon, rapport.centreLat, rapport.centreLon) }
                    l
                })
                col.addView(espace(6))
            }
            col.addView(espace(6))
        }

        // ── SEQUENCE FILMEE (storyboard) si >= 2 plans, sinon recettes generiques ──
        val sb = rapport.storyboard
        if (sb.size >= 2) {
            col.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_sequence_filmee); textSize = 13f
                setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), 0, 0, dp(6))
            })
            if (rapport.storyboardDureeS > 0) {
                col.addView(TextView(activity).apply {
                    text = activity.getString(ca.cineflight.stage.R.string.pr_sequence_complete_fmt, rapport.storyboardDureeS)
                    textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(dp(2), 0, 0, dp(6))
                })
            }
            for (p in sb) {
                col.addView(cadre(pleineLargeur = true) {
                    val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    l.addView(TextView(activity).apply {
                        text = "${p.ordre}.  ${p.role.uppercase()} — ${p.planLibelle}"
                        textSize = 15f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                    })
                    l.addView(TextView(activity).apply {
                        text = p.description; textSize = 13f
                        setTextColor(TEXTE_DOUX); setPadding(0, dp(3), 0, 0)
                    })
                    // ligne compacte : altitude + rayon/distance + duree
                    val ligneCompacte = buildString {
                        if (p.altitudeM > 0) append("Altitude ~${p.altitudeM} m")
                        if (p.rayonM > 0) append(activity.getString(ca.cineflight.stage.R.string.pr_rayon_fmt, p.rayonM))
                        else if (p.distanceM > 0) append(" · Distance ~${p.distanceM} m")
                        if (p.dureeMinS > 0) append(" · ${p.dureeMinS}–${p.dureeMaxS} s")
                    }
                    if (ligneCompacte.isNotBlank()) {
                        l.addView(TextView(activity).apply {
                            text = ligneCompacte; textSize = 12f
                            setTextColor(0xFF80CBC4.toInt()); setPadding(0, dp(4), 0, 0)
                        })
                    }
                    // fiche depliable (consigne + securite), cachee par defaut
                    val fiche = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                        setPadding(0, dp(6), 0, 0)
                    }
                    if (p.consigne.isNotBlank()) {
                        fiche.addView(TextView(activity).apply {
                            text = activity.getString(ca.cineflight.stage.R.string.pr_consigne_pilote); textSize = 12f
                            setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                        })
                        fiche.addView(TextView(activity).apply {
                            text = p.consigne; textSize = 12f; setTextColor(TEXTE_DOUX)
                            setPadding(0, dp(1), 0, dp(6))
                        })
                    }
                    if (p.securite.isNotBlank()) {
                        fiche.addView(TextView(activity).apply {
                            text = activity.getString(ca.cineflight.stage.R.string.pr_securite_label); textSize = 12f
                            setTextColor(0xFFFFA000.toInt()); setTypeface(typeface, Typeface.BOLD)
                        })
                        fiche.addView(TextView(activity).apply {
                            text = p.securite; textSize = 12f; setTextColor(TEXTE_DOUX)
                            setPadding(0, dp(1), 0, dp(6))
                        })
                    }
                    if (p.noteFiche.isNotBlank()) {
                        fiche.addView(TextView(activity).apply {
                            text = p.noteFiche; textSize = 10f
                            setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
                        })
                    }
                    // ligne de boutons : Voir la fiche | Preparer ce plan
                    val boutons = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
                    }
                    boutons.addView(TextView(activity).apply {
                        text = activity.getString(ca.cineflight.stage.R.string.pr_voir_fiche); textSize = 14f
                        setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD)
                        setOnClickListener {
                            fiche.visibility = if (fiche.visibility == View.GONE) View.VISIBLE else View.GONE
                        }
                    })
                    // statut du plan (affiche apres preparation)
                    val txtStatut = TextView(activity).apply {
                        textSize = 12f; setTextColor(0xFF4CAF50.toInt())
                        setPadding(0, dp(6), 0, 0)
                        visibility = if (plansPrepares.containsKey(p.ordre)) View.VISIBLE else View.GONE
                        text = activity.getString(ca.cineflight.stage.R.string.pr_plan_prepare)
                    }
                    val btnPreparer = TextView(activity).apply {
                        text = activity.getString(ca.cineflight.stage.R.string.pr_preparer_plan); textSize = 14f
                        setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD)
                        setOnClickListener {
                            // creer l'intention de vol structuree (PAS de vol reel)
                            val pe = p.toPlanExecutable()
                            plansPrepares[p.ordre] = pe
                            txtStatut.visibility = View.VISIBLE
                        }
                    }
                    boutons.addView(btnPreparer)
                    l.addView(boutons)
                    l.addView(txtStatut)
                    l.addView(fiche)
                    l
                })
                col.addView(espace(8))
            }
            // ── Mission brouillon (vue d'ensemble de la sequence, NON executable) ──
            rapport.missionBrouillon?.let { m ->
                col.addView(cadre(pleineLargeur = true) {
                    val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    l.addView(TextView(activity).apply {
                        text = activity.getString(ca.cineflight.stage.R.string.pr_mission_brouillon); textSize = 13f
                        setTextColor(0xFFFFA000.toInt()); setTypeface(typeface, Typeface.BOLD)
                    })
                    l.addView(TextView(activity).apply {
                        text = activity.getString(ca.cineflight.stage.R.string.pr_mission_resume_fmt, m.nbPlans, m.totalDureeMin, m.totalFilmageS, m.totalTransitS, m.totalTransitM)
                        textSize = 12f; setTextColor(TEXTE); setPadding(0, dp(4), 0, 0)
                    })
                    l.addView(TextView(activity).apply {
                        text = "⚠ " + m.avertissement; textSize = 11f
                        setTextColor(0xFFE57373.toInt()); setPadding(0, dp(6), 0, 0)
                    })
                    // bouton : generer la mission complete en KMZ volable
                    l.addView(Button(activity).apply {
                        text = activity.getString(ca.cineflight.stage.R.string.pr_generer_mission_kmz); isAllCaps = false; textSize = 15f
                        setTextColor(Color.WHITE)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
                        setOnClickListener { genererMissionKmz(rapport) }
                    })
                    l
                })
                col.addView(espace(8))
            }
            // bouton : exporter la fiche de tournage complete
            col.addView(espace(4))
            col.addView(Button(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_exporter_fiche_btn); isAllCaps = false; textSize = 15f
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                setOnClickListener { exporterFiche(rapport) }
            })
            // bouton discret : voir aussi les recettes alternatives
            if (rapport.recettes.isNotEmpty()) {
                col.addView(TextView(activity).apply {
                    text = activity.getString(ca.cineflight.stage.R.string.pr_voir_alternatives); textSize = 13f
                    setTextColor(ACCENT); setPadding(dp(2), dp(8), 0, dp(4))
                    setOnClickListener { afficherRecettesAlternatives(rapport.recettes) }
                })
            }
        } else {
            // fallback : recettes generiques
            col.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_plans_recommandes); textSize = 11f
                setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), 0, 0, dp(6))
            })
            for (rl in rapport.recettes) {
                val recetteSolo = Catalogue.parRecettesCles(listOf(rl.cle)).firstOrNull()
                col.addView(cadre(pleineLargeur = true) {
                    val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    l.addView(TextView(activity).apply {
                        text = "${rl.emoji}  ${rl.nom}"; textSize = 17f
                        setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                    })
                    l.addView(TextView(activity).apply {
                        text = rl.justification; textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
                    })
                    if (recetteSolo != null) {
                        l.setOnClickListener { choisirRecette(recetteSolo) }
                    } else {
                        l.alpha = 0.5f
                    }
                    l
                })
                col.addView(espace(8))
            }
        }
        overlay.addView(scroll)
    }

    /** Genere la mission KMZ complete et l'ouvre dans le partage Android.
     *  Point de decollage : GPS reel si sur place, sinon le lieu analyse. */
    private fun genererMissionKmz(rapport: RapportLieu) {
        // SECURITE : si on decolle depuis la position GPS actuelle (sur place),
        // le drone monte a la VERTICALE de CE point. Il ne doit donc PAS y avoir
        // de personnes a cet endroit. On bloque et on fait confirmer que le drone
        // est pose A L'ECART de tout sujet/groupe avant de generer la mission.
        if (dernierLieuSurPlace && !decollageEcartConfirme) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(
                activity, ca.cineflight.stage.R.style.DialogCineFlight)
                .setTitle(activity.getString(ca.cineflight.stage.R.string.pr_dlg_decollage_titre))
                .setMessage(
                    activity.getString(ca.cineflight.stage.R.string.pr_dlg_decollage_msg))
                .setCancelable(false)
                .setPositiveButton(activity.getString(ca.cineflight.stage.R.string.pr_dlg_decollage_ok)) { _, _ ->
                    decollageEcartConfirme = true
                    genererMissionKmz(rapport)   // relance, garde franchie
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        // reinitialise pour la prochaine generation
        decollageEcartConfirme = false

        val analyseur = analyseurLieu ?: return
        val lancer = lancerCoroutine ?: return
        val overlay = racine ?: return
        // ecran d'attente
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_mission_kmz_titre), activity.getString(ca.cineflight.stage.R.string.pr_generation_encours)) { afficherEtape1() })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = if (dernierLieuSurPlace)
                    activity.getString(ca.cineflight.stage.R.string.pr_gen_traj_gps)
                else
                    activity.getString(ca.cineflight.stage.R.string.pr_gen_traj_lieu)
                textSize = 14f; setTextColor(TEXTE_DOUX)
            }
        })
        overlay.addView(scroll)

        val lat = rapport.centreLat
        val lon = rapport.centreLon
        // point de decollage : GPS reel si sur place, sinon null (= lieu analyse)
        val depLat = if (dernierLieuSurPlace) lat else null
        val depLon = if (dernierLieuSurPlace) lon else null

        lancer {
            val dossier = activity.getExternalFilesDir(null) ?: activity.filesDir
            val fichier = java.io.File(dossier, "mission_cineflight.kmz")
            val resultat = try {
                analyseur.telechargerMissionKmzAvecDepart(lat, lon, profilChoisi, depLat, depLon, fichier)
            } catch (_: Exception) { null }
            activity.runOnUiThread {
                if (!estOuvert) return@runOnUiThread
                if (resultat == null) {
                    afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_mission_echec))
                } else {
                    partagerFichierKmz(resultat)
                }
            }
        }
    }

    /** Ecran apres generation du KMZ : partager OU lancer en simulation. */
    private fun partagerFichierKmz(fichier: java.io.File) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_mission_generee), "${fichier.name}") { afficherEtape1() })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_mission_prete)
                textSize = 13f; setTextColor(TEXTE_DOUX)
            }
        })
        col.addView(espace(10))
        // Bouton : exporter (partage Android)
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_exporter_djifly); isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { partagerKmzIntent(fichier) }
        })
        col.addView(espace(8))
        // Bouton : ouvrir la VRAIE carte (satellite) + simulation
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_voir_carte_simuler); isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                val it = android.content.Intent(activity, ca.cineflight.stage.CarteMissionActivity::class.java)
                it.putExtra("kmz_path", fichier.absolutePath)
                activity.startActivity(it)
            }
        })
        overlay.addView(scroll)
    }

    /** Ouvre le partage Android pour le fichier KMZ. */
    private fun partagerKmzIntent(fichier: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", fichier)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, activity.getString(ca.cineflight.stage.R.string.pr_kmz_subject))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(android.content.Intent.createChooser(intent, activity.getString(ca.cineflight.stage.R.string.pr_export_kmz_chooser)))
        } catch (e: Exception) {
            afficherLieuIndisponible(activity.getString(ca.cineflight.stage.R.string.pr_mission_generee_fmt, fichier.absolutePath))
        }
    }

    /** Genere une fiche de tournage texte (tous les plans) et ouvre le partage. */
    private fun exporterFiche(rapport: RapportLieu) {
        val sb = StringBuilder()
        sb.append(activity.getString(ca.cineflight.stage.R.string.pr_fiche_entete))
        sb.append(activity.getString(ca.cineflight.stage.R.string.pr_fiche_lieu_fmt, rapport.dominant))
        if (rapport.storyboardDureeS > 0)
            sb.append(activity.getString(ca.cineflight.stage.R.string.pr_fiche_sequence_fmt, rapport.storyboardDureeS))
        sb.append("\n")
        for (p in rapport.storyboard) {
            sb.append("${p.ordre}. ${p.role.uppercase()} — ${p.planLibelle}\n")
            sb.append(activity.getString(ca.cineflight.stage.R.string.pr_fiche_sujet_fmt, p.sujet))
            val params = buildString {
                if (p.altitudeM > 0) append("Altitude ~${p.altitudeM} m")
                if (p.rayonM > 0) append(activity.getString(ca.cineflight.stage.R.string.pr_rayon_fmt, p.rayonM))
                else if (p.distanceM > 0) append(" · Distance ~${p.distanceM} m")
                if (p.dureeMinS > 0) append(activity.getString(ca.cineflight.stage.R.string.pr_fiche_duree_fmt, p.dureeMinS, p.dureeMaxS))
            }
            if (params.isNotBlank()) sb.append("   $params\n")
            if (p.consigne.isNotBlank()) sb.append(activity.getString(ca.cineflight.stage.R.string.pr_fiche_consigne_fmt, p.consigne))
            if (p.securite.isNotBlank()) sb.append(activity.getString(ca.cineflight.stage.R.string.pr_fiche_securite_fmt, p.securite))
            sb.append("\n")
        }
        sb.append(activity.getString(ca.cineflight.stage.R.string.pr_fiche_note))
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, activity.getString(ca.cineflight.stage.R.string.pr_fiche_subject_fmt, rapport.dominant))
                putExtra(android.content.Intent.EXTRA_TEXT, sb.toString())
            }
            activity.startActivity(android.content.Intent.createChooser(intent, activity.getString(ca.cineflight.stage.R.string.pr_exporter_fiche_chooser)))
        } catch (_: Exception) { }
    }

    /** Affiche les recettes generiques en alternative (depuis le bouton discret). */
    private fun afficherRecettesAlternatives(recettes: List<RecetteLieu>) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_recettes_alt), activity.getString(ca.cineflight.stage.R.string.pr_plans_individuels)) { afficherEtape1() })
        col.addView(espace(8))
        for (rl in recettes) {
            val recetteSolo = Catalogue.parRecettesCles(listOf(rl.cle)).firstOrNull()
            col.addView(cadre(pleineLargeur = true) {
                val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                l.addView(TextView(activity).apply {
                    text = "${rl.emoji}  ${rl.nom}"; textSize = 17f
                    setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                })
                l.addView(TextView(activity).apply {
                    text = rl.justification; textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
                })
                if (recetteSolo != null) {
                    l.setOnClickListener { choisirRecette(recetteSolo) }
                } else {
                    l.alpha = 0.5f
                }
                l
            })
            col.addView(espace(8))
        }
        overlay.addView(scroll)
    }

    private fun afficherLieuIndisponible(message: String) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_analyse_indispo), activity.getString(ca.cineflight.stage.R.string.pr_pas_souci)) { afficherEtape1() })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = message + activity.getString(ca.cineflight.stage.R.string.pr_ou_recette_suffix)
                textSize = 14f; setTextColor(TEXTE_DOUX)
            }
        })
        col.addView(espace(10))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_choisir_recette); isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { afficherEtape1() }
        })
        overlay.addView(scroll)
    }

    // ===================== PANORAMA PHOTO 360 (mode paysage) =====================
    private fun cartePanorama(): View {
        return cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_panorama); textSize = 19f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_panorama_desc)
                textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
            })
            l.setOnClickListener { afficherChoixPanorama() }
            l
        }
    }

    private fun afficherChoixPanorama() {
        val lancer = lancerPanoramaPaysage ?: return
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_panorama_titre), activity.getString(ca.cineflight.stage.R.string.pr_choisissez_qualite)) { afficherEtape1() })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_panorama_consigne)
                textSize = 13f; setTextColor(TEXTE_DOUX)
            }
        })
        col.addView(espace(10))
        for (preset in PanoramaPreset.values()) {
            col.addView(cadre(pleineLargeur = true) {
                val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                l.addView(TextView(activity).apply {
                    text = "${preset.nomFr}  ·  ${preset.nbPhotos()} photos"; textSize = 17f
                    setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                })
                l.addView(TextView(activity).apply {
                    text = preset.accroche; textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, 0)
                })
                l.setOnClickListener { afficherExecutionPanorama(preset) }
                l
            })
            col.addView(espace(8))
        }
        overlay.addView(scroll)
    }

    private fun afficherExecutionPanorama(preset: PanoramaPreset) {
        val lancer = lancerPanoramaPaysage ?: return
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_panorama_encours), preset.nomFr) { })

        val txtProgres = TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_preparation); textSize = 18f
            setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
        }
        // Voyant "qui pilote" : gris tant que rien n'avance (decollage/montee), vert
        // des la 1ere progression (preuve que le Virtual Stick tient et que l'app pilote).
        val voyantPilote = TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_pilote_attente)
            textSize = 13f; setTextColor(0xFF90A4AE.toInt()); setPadding(0, dp(6), 0, 0)
        }
        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(txtProgres)
            l.addView(voyantPilote)
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_gardez_immobile)
                textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, 0)
            })
            l
        })
        col.addView(espace(12))
        val btnStop = Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_arreter_panorama); isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { annulerPanorama?.invoke() }
        }
        col.addView(btnStop)
        overlay.addView(scroll)

        // lancer le panorama : les callbacks reviennent sur le thread UI
        lancer(preset,
            { i, total -> activity.runOnUiThread {
                if (estOuvert) {
                    txtProgres.text = activity.getString(ca.cineflight.stage.R.string.pr_photo_fmt, i, total)
                    voyantPilote.text = activity.getString(ca.cineflight.stage.R.string.pr_pilote_app)
                    voyantPilote.setTextColor(0xFF43A047.toInt())
                }
            } },
            { nb -> activity.runOnUiThread {
                if (!estOuvert) return@runOnUiThread
                when {
                    nb == -2 -> afficherFinPanorama(activity.getString(ca.cineflight.stage.R.string.pr_pas_en_vol), false)
                    nb <= 0  -> afficherFinPanorama(activity.getString(ca.cineflight.stage.R.string.pr_panorama_arrete), false)
                    else     -> afficherFinPanorama(activity.getString(ca.cineflight.stage.R.string.pr_photos_prises_fmt, nb), true)
                }
            } }
        )
    }

    private fun afficherFinPanorama(message: String, succes: Boolean) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(if (succes) activity.getString(ca.cineflight.stage.R.string.pr_panorama_termine) else activity.getString(ca.cineflight.stage.R.string.pr_panorama_court), "") { afficherEtape1() })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = message; textSize = 15f; setTextColor(TEXTE_DOUX)
            }
        })
        // Assemblage 360 cote serveur (si capture reussie et service dispo)
        val cbAssembler = assemblerPano360
        if (succes && cbAssembler != null) {
            col.addView(espace(10))
            val txtEtatAsm = TextView(activity).apply {
                text = ""; textSize = 13f; setTextColor(ACCENT); setPadding(dp(2), dp(4), 0, dp(4))
            }
            val btnAsm = Button(activity).apply {
                text = "\uD83C\uDF10  Assembler mon panorama 360"; isAllCaps = false; textSize = 16f
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00897B.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            }
            btnAsm.setOnClickListener {
                btnAsm.isEnabled = false
                btnAsm.text = "Assemblage en cours\u2026"
                txtEtatAsm.text = "Preparation\u2026"
                cbAssembler(
                    { msg, pct -> activity.runOnUiThread { if (estOuvert) txtEtatAsm.text = "$msg  ($pct%)" } },
                    { fichier -> activity.runOnUiThread {
                        if (!estOuvert) return@runOnUiThread
                        if (fichier != null) afficherPanoramaResultat(fichier)
                        else {
                            btnAsm.isEnabled = true
                            btnAsm.text = "\uD83C\uDF10  Reessayer l'assemblage 360"
                            txtEtatAsm.text = activity.getString(ca.cineflight.stage.R.string.pr_assemblage_impossible)
                        }
                    } }
                )
            }
            col.addView(btnAsm)
            col.addView(txtEtatAsm)
            col.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_pano_attente)
                textSize = 11.5f; setTextColor(TEXTE_DOUX); setPadding(dp(2), dp(2), 0, 0)
            })
        }
        col.addView(espace(10))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_retour); isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { afficherEtape1() }
        })
        overlay.addView(scroll)
    }

    /** Ecran resultat : apercu du panorama assemble + partage. */
    private fun afficherPanoramaResultat(fichier: java.io.File) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_pano_pret), "") { afficherEtape1() })
        try {
            val opt = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = android.graphics.BitmapFactory.decodeFile(fichier.absolutePath, opt)
            if (bmp != null) col.addView(android.widget.ImageView(activity).apply {
                setImageBitmap(bmp); adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        } catch (_: Exception) {}
        col.addView(espace(8))
        col.addView(TextView(activity).apply {
            text = "Enregistre : ${fichier.name}"; textSize = 12f; setTextColor(TEXTE_DOUX)
        })
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_partager_pano_btn); isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", fichier)
                    val it = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(android.content.Intent.createChooser(it, activity.getString(ca.cineflight.stage.R.string.pr_partager_pano)))
                } catch (_: Exception) {}
            }
        })
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_retour); isAllCaps = false; textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(CARTE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            setOnClickListener { afficherEtape1() }
        })
        overlay.addView(scroll)
    }

    // Carte recette dans la grille (1/2 largeur)
    private fun carteRecette(r: Recette): View {
        val carte = cadre(pleineLargeur = false) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply { text = r.emoji; textSize = 26f })
            l.addView(TextView(activity).apply {
                text = r.nom; textSize = 15f; setTextColor(TEXTE)
                setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0)
            })
            l.addView(TextView(activity).apply {
                text = r.accroche; textSize = 11f; setTextColor(TEXTE_DOUX)
                setPadding(0, dp(2), 0, 0); maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            l.addView(TextView(activity).apply {
                text = "⏱️ ~${r.dureeS} s"; textSize = 11f; setTextColor(ACCENT)
                setPadding(0, dp(4), 0, 0)
            })
            l.setOnClickListener { choisirRecette(r) }
            l
        }
        return carte
    }

    // Cadre carte reutilisable
    private fun cadre(pleineLargeur: Boolean, largeurPx: Int = 0, contenu: () -> View): View {
        val carte = androidx.cardview.widget.CardView(activity).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            val lp = if (pleineLargeur)
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            else GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            (lp as? ViewGroup.MarginLayoutParams)?.setMargins(dp(4), dp(4), dp(4), dp(4))
            layoutParams = lp
        }
        val pad = LinearLayout(activity).apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        pad.addView(contenu())
        carte.addView(pad)
        return carte
    }

    // ===================== ETAPE 2 : "Que filmez-vous ?" =====================
    private fun choisirRecette(r: Recette) {
        if (r.estComplete) {
            // sujet deja fixe -> directement etape 3
            preparerEtAfficher(r, sujetChoisi = null)
        } else {
            afficherEtape2(r)
        }
    }

    private fun afficherEtape2(r: Recette) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete("${r.emoji} ${r.nom}", activity.getString(ca.cineflight.stage.R.string.pr_que_filmez)) { afficherEtape1() })
        for (sujet in r.sujetsCompatibles) {
            col.addView(cadre(pleineLargeur = true) {
                TextView(activity).apply {
                    text = sujet.nomFr; textSize = 17f; setTextColor(TEXTE)
                    setTypeface(typeface, Typeface.BOLD)
                    setOnClickListener { preparerEtAfficher(r, sujet) }
                }
            })
            col.addView(espace(8))
        }
        overlay.addView(scroll)
    }

    // ===================== "Surprends-moi" : faits simples =====================
    private fun afficherSurprends() {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_surprends_titre), activity.getString(ca.cineflight.stage.R.string.pr_decrivez_scene)) { afficherEtape1() })

        // Choix simples (faits) : nombre de sujets + mouvement. L'espace vient du contexte reel.
        var sujets = 1
        var bouge = false
        val ctx = fournirContexte()

        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_combien_sujets); textSize = 14f; setTextColor(TEXTE_DOUX); setPadding(0, dp(8), 0, dp(4))
        })
        val ligneSujets = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val btnsSujets = mutableListOf<Button>()
        listOf(
            activity.getString(ca.cineflight.stage.R.string.pr_aucun) to 0,
            activity.getString(ca.cineflight.stage.R.string.pr_une_personne) to 1,
            activity.getString(ca.cineflight.stage.R.string.pr_plusieurs) to 3
        ).forEach { (lbl, n) ->
            ligneSujets.addView(Button(activity).apply {
                text = lbl; isAllCaps = false; textSize = 14f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(4), 0, 0) }
                val estDef = (n == sujets)
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (estDef) ACCENT else 0xFF455A64.toInt())
                setTextColor(if (estDef) Color.WHITE else TEXTE)
                setOnClickListener {
                    sujets = n
                    btnsSujets.forEach { b -> b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF455A64.toInt()); b.setTextColor(TEXTE) }
                    backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT); setTextColor(Color.WHITE)
                }
                btnsSujets.add(this)
            })
        }
        col.addView(ligneSujets)

        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_ca_bouge); textSize = 14f; setTextColor(TEXTE_DOUX); setPadding(0, dp(12), 0, dp(4))
        })
        val ligneBouge = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val btnsBouge = mutableListOf<Button>()
        listOf(activity.getString(ca.cineflight.stage.R.string.pr_plutot_calme) to false, activity.getString(ca.cineflight.stage.R.string.pr_beaucoup) to true).forEach { (lbl, b) ->
            ligneBouge.addView(Button(activity).apply {
                text = lbl; isAllCaps = false; textSize = 14f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(4), 0, 0) }
                val estDef = (b == bouge)
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (estDef) ACCENT else 0xFF455A64.toInt())
                setTextColor(if (estDef) Color.WHITE else TEXTE)
                setOnClickListener {
                    bouge = b
                    btnsBouge.forEach { x -> x.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF455A64.toInt()); x.setTextColor(TEXTE) }
                    backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT); setTextColor(Color.WHITE)
                }
                btnsBouge.add(this)
            })
        }
        col.addView(ligneBouge)

        col.addView(espace(16))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_trouver_plan); isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                val r = Catalogue.surprendsMoi(sujets, ctx.espace, bouge)
                // surprends-moi : on prend le 1er sujet compatible comme sujet par defaut
                val sujet = if (r.estComplete) null else r.sujetsCompatibles.firstOrNull()
                preparerEtAfficher(r, sujet)
            }
        })
        overlay.addView(scroll)
    }

    // ===================== ETAPE 3 : pastille + lancer =====================
    private fun preparerEtAfficher(r: Recette, sujetChoisi: Scene?) {
        val ctx = fournirContexte()
        val verdict = assistant.preparer(r, sujetChoisi, ctx)
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete("${r.emoji} ${r.nom}", r.accroche) { afficherEtape1() })

        val past = verdict.pastille()
        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(activity).apply {
                text = "${past.emoji}  ${past.texte}"; textSize = 18f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            })
            val detail = when (verdict) {
                is Verdict.Valide -> activity.getString(ca.cineflight.stage.R.string.pr_pret_filmer_fmt, verdict.sequence.dureeTotaleS)
                is Verdict.Adapte -> activity.getString(ca.cineflight.stage.R.string.pr_plan_ajuste_prefix) + verdict.raisons.joinToString(" · ") +
                        activity.getString(ca.cineflight.stage.R.string.pr_duree_estimee_fmt, verdict.sequence.dureeTotaleS)
                is Verdict.Refuse -> verdict.message
            }
            l.addView(TextView(activity).apply {
                text = detail; textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(6), 0, 0)
            })
            l
        })
        // Duree de CE plan (reglable par ambiance) : 50%..300%, memorisee par recette.
        val rgP = reglages
        if (rgP != null && verdict !is Verdict.Refuse) {
            val clePlan = Catalogue.cleDe(r)
            ajouterSlider(col, activity.getString(ca.cineflight.stage.R.string.pr_slider_duree),
                (rgP.getFacteurDureeRecette(clePlan) * 100).toInt(), 50, 300,
                { "$it %" }, {},
                onFini = { v -> rgP.setFacteurDureeRecette(clePlan, v / 100f); preparerEtAfficher(r, sujetChoisi) })
            // Style de duree : Normal / Plan tenu / Ralenti (memorise par recette).
            val styleActuel = rgP.getStyleDuree(clePlan)
            col.addView(cadre(pleineLargeur = true) {
                val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                l.addView(TextView(activity).apply {
                    text = activity.getString(ca.cineflight.stage.R.string.pr_style_duree); textSize = 15f
                    setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
                })
                val rangee = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0)
                }
                val styles = listOf(
                    StyleDuree.NORMAL to activity.getString(ca.cineflight.stage.R.string.pr_style_normal),
                    StyleDuree.PLAN_TENU to activity.getString(ca.cineflight.stage.R.string.pr_style_tenu),
                    StyleDuree.RALENTI to activity.getString(ca.cineflight.stage.R.string.pr_style_ralenti)
                )
                for ((st, lab) in styles) {
                    rangee.addView(Button(activity).apply {
                        text = lab; isAllCaps = false; textSize = 13f
                        setTextColor(if (st == styleActuel) Color.WHITE else TEXTE_DOUX)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            if (st == styleActuel) ACCENT else 0xFF263238.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                            setMargins(dp(2), 0, dp(2), 0)
                        }
                        setOnClickListener {
                            rgP.setStyleDuree(clePlan, st); preparerEtAfficher(r, sujetChoisi)
                        }
                    })
                }
                l.addView(rangee)
                l.addView(TextView(activity).apply {
                    text = when (styleActuel) {
                        StyleDuree.NORMAL -> activity.getString(ca.cineflight.stage.R.string.pr_style_normal_desc)
                        StyleDuree.PLAN_TENU -> activity.getString(ca.cineflight.stage.R.string.pr_style_tenu_desc)
                        StyleDuree.RALENTI -> activity.getString(ca.cineflight.stage.R.string.pr_style_ralenti_desc)
                    }
                    textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(6), 0, 0)
                })
                l
            })
            col.addView(espace(8))
        }
        // Consigne pilote/sujet, derivee de la choregraphie du plan
        val seqPourConsigne = (verdict as? Verdict.Valide)?.sequence ?: (verdict as? Verdict.Adapte)?.sequence
        if (seqPourConsigne != null) {
            val sceneConsigne = r.sceneFixe ?: sujetChoisi ?: r.sujetsCompatibles.firstOrNull() ?: Scene.DANSEUR_SOLO
            col.addView(cadre(pleineLargeur = true) {
                TextView(activity).apply {
                    text = "🎬  " + Grammaire.consignePilote(sceneConsigne, seqPourConsigne)
                    textSize = 13f; setTextColor(ACCENT); setPadding(dp(4), dp(4), dp(4), dp(4))
                }
            })
            col.addView(espace(8))
        }
        col.addView(espace(16))

        when (verdict) {
            is Verdict.Valide, is Verdict.Adapte -> {
                if (demarrerAutoCadrageCb != null) {
                    col.addView(Button(activity).apply {
                        text = "🛫  Placement auto — je me filme"; isAllCaps = false; textSize = 15f
                        setTextColor(Color.WHITE)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00695C.toInt())
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                        setOnClickListener { afficherAutoCadrage(r, verdict) }
                    })
                    col.addView(espace(8))
                }
                col.addView(Button(activity).apply {
                    text = activity.getString(ca.cineflight.stage.R.string.pr_lancer_plan); isAllCaps = false; textSize = 16f
                    setTextColor(Color.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                    setOnClickListener {
                        val blocage = etatVolOk?.invoke()
                        if (blocage != null && decollerAuSol != null && estPoseEtPret?.invoke() == true) {
                            proposerDecollageAuSol(r, verdict)
                        } else if (blocage != null) {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, ca.cineflight.stage.R.style.DialogCineFlight)
                                .setTitle(activity.getString(ca.cineflight.stage.R.string.pr_drone_pas_pret))
                                .setMessage(blocage)
                                .setPositiveButton("Compris", null)
                                .show()
                        } else afficherExecution(r, verdict)
                    }
                })
            }
            is Verdict.Refuse -> {
                col.addView(Button(activity).apply {
                    text = activity.getString(ca.cineflight.stage.R.string.pr_essayer_fmt, verdict.alternative.emoji, verdict.alternative.nom)
                    isAllCaps = false; textSize = 15f
                    setTextColor(Color.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                    setOnClickListener { choisirRecette(verdict.alternative) }
                })
            }
        }
        overlay.addView(scroll)
    }

    // ===================== ECRAN REGLAGES =====================
    /** Curseur (SeekBar) reutilisable avec libelle dynamique, ajoute a `col`.
     *  onChange : a chaque cran (libelle live) ; onFini : au relachement (persister/recalculer). */
    private fun ajouterSlider(
        col: LinearLayout, titre: String, valeurInit: Int, min: Int, max: Int,
        format: (Int) -> String, onChange: (Int) -> Unit, onFini: (Int) -> Unit = {}
    ) {
        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val lbl = TextView(activity).apply {
                text = "$titre : ${format(valeurInit)}"; textSize = 15f
                setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
            }
            l.addView(lbl)
            l.addView(SeekBar(activity).apply {
                this.max = max - min
                progress = valeurInit - min
                setPadding(0, dp(8), 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = p + min
                        lbl.text = "$titre : ${format(v)}"
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { onFini((sb?.progress ?: 0) + min) }
                })
            })
            l
        })
        col.addView(espace(8))
    }

    private fun afficherReglages() {
        val rg = reglages ?: return
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(enTete(activity.getString(ca.cineflight.stage.R.string.pr_reglages_titre), activity.getString(ca.cineflight.stage.R.string.pr_ajustez_pref)) { afficherEtape1() })

        // Helper local : delegue au slider reutilisable (aussi utilise par chaque plan).
        fun slider(titre: String, valeurInit: Int, min: Int, max: Int,
                   format: (Int) -> String, onChange: (Int) -> Unit) =
            ajouterSlider(col, titre, valeurInit, min, max, format, onChange)

        // (La duree se regle desormais PLAN PAR PLAN, sur l'ecran de chaque ambiance.)

        // Vitesse globale : -1 / 0 / +1
        slider(activity.getString(ca.cineflight.stage.R.string.pr_slider_vitesse), rg.getDecalageVitesse() + 1, 0, 2,
            { when (it) { 0 -> activity.getString(ca.cineflight.stage.R.string.pr_plus_lent); 2 -> activity.getString(ca.cineflight.stage.R.string.pr_plus_rapide); else -> "Normal" } },
            { rg.setDecalageVitesse(it - 1) })

        // Vitesse du pivot panoramique : 8..40 deg/s
        slider(activity.getString(ca.cineflight.stage.R.string.pr_slider_balayage), rg.getVitessePivotDps().toInt(), 8, 40,
            { "$it °/s" }, { rg.setVitessePivotDps(it.toFloat()) })

        // Duree de l'analyse : 4..20 s
        slider(activity.getString(ca.cineflight.stage.R.string.pr_slider_analyse), rg.getDureeAnalyseS(), 4, 20,
            { "$it s" }, { rg.setDureeAnalyseS(it) })

        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_reglages_note)
            textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(dp(4), dp(8), dp(4), 0)
        })
        overlay.addView(scroll)
    }

    /** Le drone est pose : propose un decollage automatique au lieu de bloquer.
     *  Par securite, on NE lance PAS le plan au ras du sol : apres le decollage
     *  (vol stationnaire bas), le pilote monte a son altitude de tournage aux
     *  manettes, puis relance "Lancer le plan" (l'etat de vol passe alors OK). */
    private fun proposerDecollageAuSol(r: Recette, verdict: Verdict) {
        val dec = decollerAuSol ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, ca.cineflight.stage.R.style.DialogCineFlight)
            .setTitle(activity.getString(ca.cineflight.stage.R.string.pr_decoller_sol_titre))
            .setMessage(
                activity.getString(ca.cineflight.stage.R.string.pr_decoller_sol_msg)
            )
            .setNegativeButton(activity.getString(ca.cineflight.stage.R.string.pr_annuler), null)
            .setPositiveButton(activity.getString(ca.cineflight.stage.R.string.pr_decoller_btn)) { _, _ ->
                val progres = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, ca.cineflight.stage.R.style.DialogCineFlight)
                    .setTitle(activity.getString(ca.cineflight.stage.R.string.pr_decollage_titre))
                    .setMessage(activity.getString(ca.cineflight.stage.R.string.pr_decollage_msg))
                    .setCancelable(false)
                    .show()
                dec { ok ->
                    activity.runOnUiThread {
                        try { progres.dismiss() } catch (_: Exception) {}
                        val titre = if (ok) activity.getString(ca.cineflight.stage.R.string.pr_en_vol_stat) else activity.getString(ca.cineflight.stage.R.string.pr_decollage_impossible)
                        val msg = if (ok)
                            activity.getString(ca.cineflight.stage.R.string.pr_decollage_reussi)
                        else
                            activity.getString(ca.cineflight.stage.R.string.pr_decollage_echec)
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, ca.cineflight.stage.R.style.DialogCineFlight)
                            .setTitle(titre)
                            .setMessage(msg)
                            .setPositiveButton("Compris", null)
                            .show()
                    }
                }
            }
            .show()
    }

    /** Ecran AUTO-CADRAGE SOLO : le drone se place et cadre le sujet ; le plan se lance a la MAIN. */
    private fun afficherAutoCadrage(r: Recette, verdict: Verdict) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        autoRecette = r; autoVerdict = verdict
        val (scroll, col) = conteneurScroll()
        col.addView(TextView(activity).apply {
            text = "🛫  ${r.nom}"; textSize = 22f
            setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), 0, dp(4), dp(8))
        })
        val txtEtat = TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_preparation); textSize = 18f; setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD)
        }
        autoEtatTexte = txtEtat
        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(txtEtat)
            l.addView(TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_autocadrage_msg)
                textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(6), 0, 0)
            })
            l
        })
        col.addView(espace(12))
        val btnLancer = Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_lancer_plan); isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            visibility = android.view.View.GONE
            setOnClickListener {
                commandeAutoCadrage?.invoke("lancer")
                val rr = autoRecette; val vv = autoVerdict
                if (rr != null && vv != null) afficherExecution(rr, vv)
            }
        }
        autoBtnLancer = btnLancer
        col.addView(btnLancer)
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_arret_btn); isAllCaps = false; textSize = 17f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFC62828.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            setOnClickListener { commandeAutoCadrage?.invoke("arret"); autoEtatTexte = null; autoBtnLancer = null; afficherArrete() }
        })
        ajouterBoutonRamener(col)
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_annuler); isAllCaps = false; textSize = 14f
            setTextColor(TEXTE_DOUX)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF263238.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            setOnClickListener { commandeAutoCadrage?.invoke("annuler"); autoEtatTexte = null; autoBtnLancer = null; afficherEtape1() }
        })
        overlay.addView(scroll)
        demarrerAutoCadrageCb?.invoke()
    }

    /** Appele par MainActivity a chaque changement d'etat de la sequence auto-cadrage. */
    fun majEtatAuto(message: String, pretAConfirmer: Boolean, fini: Boolean) {
        autoEtatTexte?.text = message
        autoBtnLancer?.visibility = if (pretAConfirmer) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Ecran affiche APRES un ARRET : le drone est en vol stationnaire. On NE ferme pas le
     *  panneau, pour laisser le choix : le ramener (RTH) ou fermer et reprendre la main. */
    private fun afficherArrete() {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_drone_arrete); textSize = 22f
            setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), 0, dp(4), dp(8))
        })
        col.addView(cadre(pleineLargeur = true) {
            TextView(activity).apply {
                text = activity.getString(ca.cineflight.stage.R.string.pr_hover_msg)
                textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        })
        col.addView(espace(12))
        ajouterBoutonRamener(col)
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_fermer_reprendre); isAllCaps = false; textSize = 15f
            setTextColor(TEXTE_DOUX)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF263238.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { fermer() }
        })
        overlay.addView(scroll)
    }

    /** Bouton "Ramener le drone" (RTH) avec confirmation. Masque si non branche. */
    private fun ajouterBoutonRamener(col: LinearLayout) {
        val cb = ramenerDrone ?: return
        col.addView(espace(8))
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_ramener_btn); isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, ca.cineflight.stage.R.style.DialogCineFlight)
                    .setTitle(activity.getString(ca.cineflight.stage.R.string.pr_ramener_titre))
                    .setMessage(activity.getString(ca.cineflight.stage.R.string.pr_ramener_msg))
                    .setNegativeButton(activity.getString(ca.cineflight.stage.R.string.pr_annuler), null)
                    .setPositiveButton("Ramener") { _, _ -> cb(); fermer() }
                    .show()
            }
        })
    }

    private fun afficherExecution(r: Recette, verdict: Verdict) {
        val overlay = racine ?: return
        overlay.removeAllViews()
        val (scroll, col) = conteneurScroll()
        // pas de bouton Retour ici : pendant l'execution, on Arrete ou on attend la fin
        col.addView(TextView(activity).apply {
            text = "${r.emoji}  ${r.nom}"; textSize = 22f
            setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), 0, dp(4), dp(12))
        })

        // Carte de progression (mise a jour en direct)
        val txtEtape = TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_demarrage); textSize = 18f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD)
        }
        val txtReste = TextView(activity).apply {
            text = ""; textSize = 14f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, 0)
        }
        col.addView(cadre(pleineLargeur = true) {
            val l = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            l.addView(txtEtape); l.addView(txtReste); l
        })
        col.addView(espace(16))

        // Bouton ARRETER (annulation propre — PAS un arret d'urgence)
        col.addView(Button(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_arreter); isAllCaps = false; textSize = 17f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFC62828.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            setOnClickListener {
                assistant.annuler()      // annule proprement : drone stationnaire, suivi conserve
                afficherArrete()         // reste ouvert : Ramener (RTH) ou reprendre la main
            }
        })
        ajouterBoutonRamener(col)
        col.addView(espace(8))
        col.addView(TextView(activity).apply {
            text = activity.getString(ca.cineflight.stage.R.string.pr_arreter_note)
            textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(dp(4), 0, dp(4), 0)
        })
        overlay.addView(scroll)

        // Lancement avec progression live
        assistant.lancerVerdict(
            verdict,
            onProgres = { p ->
                activity.runOnUiThread {
                    txtEtape.text = activity.getString(ca.cineflight.stage.R.string.pr_etape_fmt, p.mouvementNom, p.etape, p.total)
                    txtReste.text = activity.getString(ca.cineflight.stage.R.string.pr_reste_fmt, p.resteEtapeS, p.resteTotalS)
                }
            },
            onFini = {
                activity.runOnUiThread {
                    txtEtape.text = activity.getString(ca.cineflight.stage.R.string.pr_plan_termine)
                    txtReste.text = activity.getString(ca.cineflight.stage.R.string.pr_drone_stable)
                    // ferme l'overlay apres un court instant pour rendre le cockpit
                    overlay.postDelayed({ if (estOuvert) fermer() }, 1500)
                }
            }
        )
    }
}

