package ca.cineflight.stage.control

import android.util.Log
import com.tencent.yolo11ncnn.YOLO11Ncnn
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

/**
 * YoloSuivi - branche le flux du drone sur YOLO embarque (detectYUV natif).
 *
 * Capte les frames NV21 du drone via ICameraStreamManager.addFrameListener,
 * les passe a YOLO (detectYUV), et renvoie la boite du sujet principal
 * (la personne la plus grande dans le cadre) via le callback onSujet.
 *
 * Les coordonnees sont normalisees 0..1 (x,y,w,h) par rapport a l'image,
 * pretes pour OverlayYolo et le pilotage.
 */
class YoloSuivi(
    private val yolo: YOLO11Ncnn,
    private val onSujet: (Boolean, Float, Float, Float, Float) -> Unit,
    private val onTag: (Int) -> Unit = {},
    /**
     * Observateur optionnel de TOUTES les detections de la frame (toutes classes,
     * AVANT le filtre de suivi). Sert a l'analyse de scene / rapport de reperage.
     * Chaque element : [classe, cx, cy, fw, fh] normalises 0..1. Ne modifie pas le suivi.
     */
    private val onDetections: ((List<FloatArray>) -> Unit)? = null,
    /**
     * Observateur optionnel d'INFO de suivi : confiance (0..1) de la boite
     * choisie + nombre de personnes (classes suivies) detectees dans la frame.
     * Sert aux garde-fous du mode top-down autonome (un seul sujet, confiance
     * elevee). N'affecte pas le suivi. Emis a chaque frame ou un sujet est vu.
     */
    private val onSuiviInfo: ((confiance: Float, nbPersonnes: Int) -> Unit)? = null,
    /**
     * Observateur optionnel MULTI-JOUEURS : la liste COMPLETE des personnes suivies de
     * la frame (classes suivies), chaque element [cx, cy, fw, fh, conf] normalise 0..1.
     * Sert au mode SOCCER (tracker multi-joueurs + centre de groupe). N'affecte PAS le
     * suivi mono-cible existant. Emis a chaque frame (liste vide si aucune personne).
     */
    private val onJoueurs: ((List<FloatArray>) -> Unit)? = null
) {
    private var dernierTagVu = -1
    private var compteurTag = 0
    private var dernierTagDeclenche = -1
    @Volatile private var dernierTailleTag = 0   // cote moyen px du dernier tag vu (0 = aucun)
    // Intrusion : une personne (0) ou un animal (15-19 COCO) est-il visible MAINTENANT ?
    @Volatile private var intrusionVisibleFlag = false
    @Volatile private var intrusionMajMs = 0L
    private val CLASSES_INTRUSION = setOf(0, 15, 16, 17, 18, 19)  // personne + animaux
    // Derniere frame NV21 recue (pour le score d'ouverture Sentinelle V2).
    @Volatile private var derniereFrame: ByteArray? = null
    @Volatile private var derniereW = 0
    @Volatile private var derniereH = 0
    private val CLASSE_PERSONNE = 0
    // Classes COCO suivies en mode AUTO (sans toucher). Defaut = personne.
    // 0=personne, 16=chien, 17=cheval, 2=voiture, 3=moto
    @Volatile private var classesSuivies: Set<Int> = setOf(0)
    /**
     * Score d'ouverture V0 (Sentinelle V2) sur la DERNIERE frame du drone.
     * Retourne (score 0..100, confiance 0..100) ou null si pas de frame / illisible.
     * Le natif renvoie [score, confiance, evaluable].
     */
    /**
     * Intrusion : une personne ou un animal est-il visible MAINTENANT (< 1,5 s) ?
     * Sert a l'anti-intrusion de la Sentinelle (bloque le depart si quelqu'un est present).
     */
    fun intrusionVisible(): Boolean =
        intrusionVisibleFlag && (System.currentTimeMillis() - intrusionMajMs) < 1500L

    fun scoreOuvertureCourant(): Pair<Double, Double>? {
        val f = derniereFrame ?: return null
        val w = derniereW; val h = derniereH
        if (w <= 0 || h <= 0 || f.size < w * h) return null
        return try {
            val r = yolo.scoreOuvertureYUV(f, w, h)
            if (r == null || r.size < 3) null
            else Pair(r[0].toDouble(), r[1].toDouble())
        } catch (e: Throwable) { Log.e("YoloSuivi", "scoreOuverture: $e"); null }
    }
    /** Definit les classes a suivre en mode auto (le toucher reste universel). */
    fun setClassesSuivies(classes: Set<Int>) { classesSuivies = if (classes.isEmpty()) setOf(0) else classes }
    private var actif = false

    private val listener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray, offset: Int, length: Int,
            width: Int, height: Int, format: ICameraStreamManager.FrameFormat
        ) {
            if (!actif) return
            if (frameData.size < width * height) return
            // memoriser la frame pour le score d'ouverture (copie defensive)
            derniereFrame = frameData.copyOf(); derniereW = width; derniereH = height
            try {
                val res = yolo.detectYUV(frameData, width, height)
                traiter(res, width, height)
                try {
                    val tags = yolo.detectArucoYUV(frameData, width, height)
                    traiterTags(tags)
                } catch (e: Throwable) { Log.e("YoloSuivi", "aruco: $e") }
            } catch (e: Throwable) {
                Log.e("YoloSuivi", "detectYUV: $e")
            }
        }
    }

    private fun traiter(res: FloatArray?, w: Int, h: Int) {
        if (res == null || res.isEmpty()) {
            onJoueurs?.invoke(emptyList()); onSujet(false, 0f, 0f, 0f, 0f); return
        }
        val n = res[0].toInt()
        // collecte toutes les personnes (centre normalise + taille)
        val personnes = ArrayList<FloatArray>()  // [cx, cy, fw, fh]
        // collecte TOUTES les detections (toutes classes) pour l'analyse de scene (option B)
        val toutes = if (onDetections != null) ArrayList<FloatArray>(n) else null  // [classe, cx, cy, fw, fh]
        var intrusionDansFrame = false
        for (i in 0 until n) {
            val o = 1 + i * 6
            val cl = res[o].toInt()
            if (cl in CLASSES_INTRUSION) intrusionDansFrame = true
            val conf = res[o + 1]   // confiance 0..1 (A VERIFIER en vol via l'affichage)
            val x = res[o + 2]; val y = res[o + 3]
            val ww = res[o + 4]; val hh = res[o + 5]
            val cx = (x + ww / 2f) / w
            val cy = (y + hh / 2f) / h
            // analyse de scene : on garde TOUTE detection, avec sa classe
            toutes?.add(floatArrayOf(cl.toFloat(), cx, cy, ww / w, hh / h))
            // suivi : toucher = universel (toute classe) ; auto = classes choisies
            if (!cibleDesignee && cl !in classesSuivies) continue
            personnes.add(floatArrayOf(cx, cy, ww / w, hh / h, conf))
        }
        // intrusion : mise a jour du flag (toujours, meme si personne -> remet a false)
        intrusionVisibleFlag = intrusionDansFrame
        intrusionMajMs = System.currentTimeMillis()
        // expose la scene complete a l'observateur (n'affecte pas le suivi)
        if (toutes != null) onDetections?.invoke(toutes)
        // expose la liste MULTI-JOUEURS complete (mode soccer) — meme si vide.
        onJoueurs?.invoke(personnes)
        if (personnes.isEmpty()) { onSujet(false, 0f, 0f, 0f, 0f); return }

        val choisie: FloatArray = if (cibleDesignee) {
            // ré-association : la personne la plus proche de la derniere position connue
            var best = personnes[0]
            var bestD = Float.MAX_VALUE
            for (p in personnes) {
                val dx = p[0] - cibleCx; val dy = p[1] - cibleCy
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = p }
            }
            best
        } else {
            // pas de cible designee : la plus grande (comportement par defaut)
            personnes.maxByOrNull { it[2] * it[3] }!!
        }
        // memorise la position pour la frame suivante
        cibleCx = choisie[0]; cibleCy = choisie[1]
        onSujet(true, choisie[0], choisie[1], choisie[2], choisie[3])
        // info de suivi (garde-fous top-down) : confiance du sujet choisi + nb personnes.
        // choisie[4] = confiance si presente (collectee plus haut), sinon 0.
        val confChoisie = if (choisie.size > 4) choisie[4] else 0f
        onSuiviInfo?.invoke(confChoisie, personnes.size)
    }

    // --- Cible designee par le pilote (toucher ecran) ---
    @Volatile private var cibleDesignee = false
    @Volatile private var cibleCx = 0.5f
    @Volatile private var cibleCy = 0.5f

    /** Le pilote touche l'ecran : on designe la cible a cette position normalisee (0..1). */
    fun designerCible(cxNorm: Float, cyNorm: Float) {
        cibleCx = cxNorm.coerceIn(0f, 1f)
        cibleCy = cyNorm.coerceIn(0f, 1f)
        cibleDesignee = true
    }

    /** Annule la designation : retour au comportement "plus grande personne". */
    fun annulerCible() { cibleDesignee = false }

    fun aCibleDesignee(): Boolean = cibleDesignee

    /** Cote moyen (px) du dernier tag ArUco vu, 0 si aucun. Indicateur de proximite. */
    fun tailleDernierTag(): Int = dernierTailleTag

    fun demarrer(index: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN) {
        if (actif) return
        try {
            MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
                index, ICameraStreamManager.FrameFormat.NV21, listener
            )
            actif = true
            Log.i("YoloSuivi", "Suivi YOLO demarre")
        } catch (e: Exception) {
            Log.e("YoloSuivi", "demarrer: $e")
        }
    }

    fun arreter() {
        if (!actif) return
        actif = false
        try {
            MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(listener)
        } catch (e: Exception) {
            Log.e("YoloSuivi", "arreter: $e")
        }
    }

    // Nouveau format natif : [n, id0, taille0, id1, taille1, ...] (taille = cote moyen px).
    private fun traiterTags(tags: IntArray?) {
        if (tags == null || tags.isEmpty() || tags[0] == 0) {
            compteurTag = 0
            dernierTagVu = -1
            return
        }
        val id = tags[1]
        val taille = if (tags.size > 2) tags[2] else 0   // cote moyen du 1er tag (px)
        dernierTailleTag = taille
        Log.i("YoloSuivi", "Tag $id vu, taille ${taille}px")
        if (id == dernierTagVu) compteurTag++ else { dernierTagVu = id; compteurTag = 1 }
        if (compteurTag >= 5 && id != dernierTagDeclenche) {
            dernierTagDeclenche = id
            onTag(id)
        }
    }

}

