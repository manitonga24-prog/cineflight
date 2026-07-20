package ca.cineflight.stage.sport.soccer

/**
 * SoccerDirector — LE REALISATEUR AUTONOME (pur, aucun SDK/Android).
 *
 * Coeur de la realisation cinema de CineFlight. Au lieu de SUIVRE une consigne (taille
 * cible fixe), il CHERCHE le meilleur cadrage en maximisant l'indice de qualite
 * (SoccerCadrageScore). Methode : RECHERCHE LOCALE PREDICTIVE.
 *
 *   altitude actuelle
 *        -> score actuel
 *        -> SIMULER altitude +pas et altitude -pas (sans bouger le drone)
 *        -> comparer les 3 scores predits
 *        -> choisir la meilleure altitude (direction desiree)
 *        -> RATE LIMITER (variation bornee par vitesse verticale * dt)
 *        -> repeter a chaque frame
 *
 * SIMULATION SANS EFFET DE BORD : on ne rappelle PAS SoccerCadrageScore.calculer()
 * (qui memorise l'historique de stabilite). L'altitude n'agit, geometriquement, que sur
 * la TAILLE des joueurs dans l'image (taille ~ k / altitude). Les autres composantes
 * (nombre, lead room, stabilite) sont ~invariantes a un petit pas d'altitude : on les
 * fige a leur valeur courante et on ne fait varier que la composante taille predite.
 *
 * EXTENSIBLE : aujourd'hui l'axe pilote est l'ALTITUDE. La meme mecanique (candidats +/-,
 * predire, scorer, choisir, rate-limiter) s'appliquera demain a la distance, au decalage
 * lateral, au zoom, au yaw, au pitch nacelle. C'est le futur "realisateur complet".
 *
 * PORTEE : observation/planification pure. Ne commande RIEN ; produit une altitude
 * "optimisee" que l'arbitre + double verrou gouverneront le jour de l'emission reelle.
 *
 * dt : calcule par l'appelant depuis un timestamp MONOTONE (System.nanoTime), borne ici.
 */
class SoccerDirector(
    private val cadrage: SoccerCadrageScore = SoccerCadrageScore(),
    /** Pas de simulation (m) : on teste +pas et -pas autour de l'altitude courante. */
    private val pasSimM: Double = 0.5,
    /** Vitesse verticale max de la proposition (m/s). 0.5 = cinematographique, doux. */
    private val vitesseMaxMps: Double = 0.5,
    /** Zone morte sur le score : sous ce gain, on ne bouge pas (anti-oscillation).
     *  Petit car un pas de 0.5 m ne fait varier le score que faiblement ; on veut laisser
     *  passer un vrai gradient tout en bloquant le bruit numerique. */
    private val epsilonScore: Float = 0.0002f,
    /** dt de repli si mesure invalide (s). */
    private val dtRepliS: Double = 0.05,
    /** dt borne [min,max] (s) : ecrete les frames trop rapides/lentes et les pauses. */
    private val dtMinS: Double = 0.02,
    private val dtMaxS: Double = 0.25,
    /** Plafond absolu de securite (m). */
    private val plafondAbsoluM: Double = 35.0,
    /** Lissage de la confiance observee 0..1 (0 = fige, 1 = instantane). */
    private val lissageConf: Float = 0.2f,
    /** Sous ce niveau de confiance lissee, on TIENT l'altitude (zero-detection). */
    private val seuilConfianceDecision: Float = 0.1f,

    // --- AXE LATERAL (grille altitude x lateral) ---
    /** Pas de simulation lateral (m) : on teste +pas et -pas autour du lateral courant. */
    private val pasLateralSimM: Double = 0.5,
    /** Vitesse laterale max de la proposition (m/s). 0.5 = doux, comme l'altitude. */
    private val vitesseLateraleMaxMps: Double = 0.5,
    /** Amplitude laterale max autorisee (m) de part et d'autre de la position de reference,
     *  bornant l'errance gauche-droite (marge de securite laterale par defaut). */
    private val amplitudeLateraleMaxM: Double = 8.0,
    /** Sensibilite du modele "decalage drone (m) -> deplacement de l'action dans l'image".
     *  1 m de drone deplace l'action de ~[gainLateralImage] fraction d'image. */
    private val gainLateralImage: Float = 0.03f,
    /** Penalite par metre de mouvement altitude (cout de bouger, anti-agitation). */
    private val penaliteAltitude: Float = 0.002f,
    /** Penalite par metre de mouvement lateral. */
    private val penaliteLaterale: Float = 0.002f,
    /** Penalite supplementaire quand le lateral CHANGE de sens (anti gauche-droite). */
    private val penaliteChangementDir: Float = 0.004f,

    // --- AXE ZOOM OPTIQUE (ajustement fin, actif SEULEMENT si zoom optique continu) ---
    /** Capacite de zoom de la camera. Seul OPTICAL_CONTINUOUS active l'axe zoom. */
    private val capaciteZoom: CapaciteZoom = CapaciteZoom.ZOOM_NONE,
    /** Pas de simulation zoom (facteur multiplicatif) : on teste x(1-pas), x1, x(1+pas). */
    private val pasZoom: Float = 0.05f,
    /** Facteur de zoom min/max autorises (borne l'ampleur du tele). */
    private val zoomMin: Float = 1.0f,
    private val zoomMax: Float = 2.0f,
    /** Vitesse de zoom max (facteur/s). Doux : le zoom est un ajustement FIN. */
    private val vitesseZoomParS: Float = 0.15f,
    /** SEUIL d'amelioration pour bouger le zoom : altitude d'abord, zoom en ajustement fin
     *  seulement (le zoom ne remplace pas le mouvement du drone). Choisi au-dessus du bruit
     *  mais assez bas pour laisser le zoom aider quand l'altitude est deja bornee. */
    private val seuilZoom: Float = 0.005f,
    /** Penalite par unite de mouvement zoom (elevee : on prefere garder le zoom au repos). */
    private val penaliteZoom: Float = 0.02f,
) {

    /** Resultat d'une frame de realisation. */
    data class Plan(
        /** Altitude proposee apres rate-limit (m). */
        val altitudeM: Double,
        /** Altitude "desiree" avant rate-limit (direction du meilleur candidat). */
        val altitudeDesireeM: Double,
        /** Score de cadrage a l'altitude courante [0,1]. */
        val scoreActuel: Float,
        /** dt effectif utilise (s), apres bornage. */
        val dtS: Double,
        /** Direction choisie : +1 monter, -1 descendre, 0 tenir (zone morte). */
        val sens: Int,
        /** Confiance observee LISSEE [0,1], pour diagnostic. */
        val confianceLissee: Float,
        // --- axe lateral (0 si non pilote via realiser mono-axe) ---
        /** Decalage lateral propose apres rate-limit (m ; + = drone vers la droite). */
        val lateralM: Double = 0.0,
        /** Decalage lateral desire avant rate-limit (m). */
        val lateralDesireM: Double = 0.0,
        /** Sens lateral choisi : +1 droite, -1 gauche, 0 tenir. */
        val sensLateral: Int = 0,
        // --- axe zoom optique (1.0 = neutre ; actif seulement si zoom optique continu) ---
        /** Facteur de zoom propose apres rate-limit (1.0 = grand-angle natif). */
        val zoom: Float = 1.0f,
        /** true si l'axe zoom est reellement pilote (camera a zoom optique continu). */
        val zoomActif: Boolean = false,
    )

    /** Vitesse verticale max configuree (m/s), pour l'appelant. */
    val vitesseMax: Double get() = vitesseMaxMps

    private var altCourante = Double.NaN
    private var dernierNanos = 0L
    private var confLissee = Float.NaN
    private var premiereFrame = true
    private var lateralCourant = 0.0     // decalage lateral courant (m), reference = 0
    private var dernierSensLat = 0       // dernier sens lateral applique (anti-oscillation)
    private var zoomCourant = 1.0f       // facteur de zoom courant (1.0 = neutre)

    fun reset() {
        altCourante = Double.NaN; dernierNanos = 0L; confLissee = Float.NaN; premiereFrame = true
        lateralCourant = 0.0; dernierSensLat = 0; zoomCourant = 1.0f
    }

    /**
     * Une frame de realisation.
     *
     * @param nanosMonotone  timestamp MONOTONE (System.nanoTime()), pour calculer dt.
     * @param altitudeActuelleM altitude reelle/estimee courante (m).
     * @param tailleObserveeImg hauteur moyenne des joueurs dans l'image [0,1].
     * @param nbJoueurs nombre de joueurs pertinents.
     * @param actionCx,actionCy centre d'action a l'ecran [0,1].
     * @param dirX direction horizontale du jeu [-1,1].
     * @param plageMinM,plageMaxM plage d'altitude autorisee par la PHASE courante.
     */
    fun realiser(
        nanosMonotone: Long,
        altitudeActuelleM: Double,
        tailleObserveeImg: Float,
        nbJoueurs: Int,
        actionCx: Float,
        actionCy: Float,
        dirX: Float,
        plageMinM: Double,
        plageMaxM: Double,
        confianceObservee: Float = 1f,
    ): Plan {
        // --- dt monotone, borne, avec repli et anti-saut apres pause ---
        val dt = calculerDt(nanosMonotone)

        // --- bornes effectives (phase, plafond, fail-safe) ---
        val minM = plageMinM.coerceIn(0.0, plafondAbsoluM)
        val maxM = plageMaxM.coerceIn(minM, plafondAbsoluM)
        val altAct = (if (altitudeActuelleM.isFinite()) altitudeActuelleM else (minM + maxM) / 2.0)
            .coerceIn(minM, maxM)
        if (altCourante.isNaN()) altCourante = altAct

        val taille = (if (tailleObserveeImg.isFinite()) tailleObserveeImg else 0f).coerceIn(0.001f, 1f)

        // --- confiance observee, LISSEE dans le temps (evite les a-coups) ---
        val confObs = (if (confianceObservee.isFinite()) confianceObservee else 0f).coerceIn(0f, 1f)
        confLissee = if (confLissee.isNaN()) confObs else confLissee + (confObs - confLissee) * lissageConf

        // --- composantes INVARIANTES a un petit pas d'altitude (figees) ---
        val cNombre = cadrage.composanteNombre(nbJoueurs)
        val cLead = cadrage.composanteLeadRoom(actionCx, dirX)
        // stabilite : on N'appelle PAS composanteStabilite ici (effet potentiel d'historique
        // cote appelant) ; l'altitude n'agit pas sur elle -> on la neutralise a 1 pour la
        // COMPARAISON des candidats (constante, ne change pas le classement). Le vrai score
        // de stabilite reste calcule par l'appelant via SoccerCadrageScore.calculer().
        val cStabConst = 1f

        // --- 3 candidats d'altitude : actuel, +pas, -pas (bornes) ---
        val candidats = doubleArrayOf(
            altAct,
            (altAct + pasSimM).coerceIn(minM, maxM),
            (altAct - pasSimM).coerceIn(minM, maxM),
        )

        // score PREDIT pour un candidat : la taille change (taille ~ k/alt) ET la confiance
        // predite change avec la taille (monter -> joueurs plus petits -> confiance qui baisse).
        // C'est ce qui permet a la confiance d'INFLUENCER le choix monter/rester/descendre,
        // pas seulement d'abaisser le score affiche.
        // k calibre sur l'observation courante : k = taille * altAct.
        val k = taille * altAct
        fun scorePredit(altCand: Double): Float {
            val taillePred = if (altCand > 1e-6) (k / altCand).toFloat() else taille
            // score de taille CONTINU (jamais plat) : le realisateur sait toujours ou aller.
            val cTaille = cadrage.scoreTailleContinu(taillePred)
            val cadr = cadrage.combiner(cTaille, cNombre, cLead, cStabConst)
            val confCand = confiancePredite(confLissee.toDouble(), taille.toDouble(), taillePred.toDouble())
            return (cadr * cadrage.facteurConfiance(confCand.toFloat())).coerceIn(0f, 1f)
        }

        val scoreActuel = scorePredit(altAct)
        var meilleurAlt = altAct
        var meilleurScore = scoreActuel
        for (c in candidats) {
            val s = scorePredit(c)
            if (s > meilleurScore) { meilleurScore = s; meilleurAlt = c }
        }

        // --- garde-fou ZERO-DETECTION : confiance quasi nulle = on ne DECIDE rien sur des
        //     donnees absentes/peu fiables. On TIENT l'altitude (pas de descente agressive
        //     pour "aller chercher" des joueurs qui ne sont peut-etre pas la). ---
        val donneesFiables = confLissee >= seuilConfianceDecision

        // --- zone morte : gain insuffisant -> tenir l'altitude ---
        val desiree = if (!donneesFiables || meilleurScore - scoreActuel < epsilonScore) altAct
                      else meilleurAlt

        // --- rate limiter : variation reelle bornee par vitesseMax * dt ---
        //     PREMIERE FRAME : aucun mouvement (pas de dt fiable ni d'historique). On se
        //     contente d'initialiser l'altitude a la valeur observee.
        val maxDelta = if (premiereFrame) 0.0 else vitesseMaxMps * dt
        val delta = (desiree - altCourante).coerceIn(-maxDelta, maxDelta)
        altCourante = (altCourante + delta).coerceIn(minM, maxM)
        premiereFrame = false

        val sens = when {
            desiree > altAct + 1e-6 -> 1
            desiree < altAct - 1e-6 -> -1
            else -> 0
        }

        return Plan(
            altitudeM = altCourante,
            altitudeDesireeM = desiree,
            scoreActuel = scoreActuel,
            dtS = dt,
            sens = sens,
            confianceLissee = if (confLissee.isNaN()) 0f else confLissee,
        )
    }

    /**
     * REALISATION MULTIAXE (altitude x lateral) via MINI-GRILLE 3x3.
     *
     * Pour chaque combinaison (altitude ∈ {-, 0, +}, lateral ∈ {-, 0, +}) on PREDIT :
     *   - la taille des joueurs (taille ~ k/altitude) -> composante taille + confiance ;
     *   - la position de l'action dans l'image (actionCx) : decaler le drone a DROITE (+lat)
     *     pousse l'action vers la GAUCHE du cadre (actionCx diminue), et inversement ;
     *   - le LEAD ROOM recalcule avec ce actionCx predit.
     * On applique la confiance, puis des PENALITES de mouvement (cout de bouger + cout de
     * changer de sens lateral) pour eviter l'agitation. On choisit la meilleure paire, puis
     * chaque axe est RATE-LIMITE independamment et LISSE. Bornage terrain via l'amplitude
     * laterale max. Zone morte sur le gain. Observation pure : ne commande rien.
     *
     * @param lateralActuelM decalage lateral courant (m) ; passe 0 si non suivi cote appelant.
     * @param amplitudeLateraleM amplitude laterale autorisee (m) ; <=0 -> valeur par defaut.
     * (autres parametres identiques a realiser()).
     */
    fun realiser2D(
        nanosMonotone: Long,
        altitudeActuelleM: Double,
        lateralActuelM: Double,
        tailleObserveeImg: Float,
        nbJoueurs: Int,
        actionCx: Float,
        actionCy: Float,
        dirX: Float,
        plageMinM: Double,
        plageMaxM: Double,
        confianceObservee: Float = 1f,
        amplitudeLateraleM: Double = -1.0,
    ): Plan {
        val dt = calculerDt(nanosMonotone)

        // bornes altitude
        val minM = plageMinM.coerceIn(0.0, plafondAbsoluM)
        val maxM = plageMaxM.coerceIn(minM, plafondAbsoluM)
        val altAct = (if (altitudeActuelleM.isFinite()) altitudeActuelleM else (minM + maxM) / 2.0)
            .coerceIn(minM, maxM)
        if (altCourante.isNaN()) altCourante = altAct

        // bornes laterales (marge de securite)
        val ampMax = (if (amplitudeLateraleM > 0.0) amplitudeLateraleM else amplitudeLateraleMaxM)
        val latAct = (if (lateralActuelM.isFinite()) lateralActuelM else 0.0).coerceIn(-ampMax, ampMax)
        // synchronise l'etat interne sur le lateral reel fourni par l'appelant.
        lateralCourant = lateralCourant.coerceIn(-ampMax, ampMax)

        val taille = (if (tailleObserveeImg.isFinite()) tailleObserveeImg else 0f).coerceIn(0.001f, 1f)
        val cx0 = (if (actionCx.isFinite()) actionCx else 0.5f).coerceIn(0f, 1f)

        val confObs = (if (confianceObservee.isFinite()) confianceObservee else 0f).coerceIn(0f, 1f)
        confLissee = if (confLissee.isNaN()) confObs else confLissee + (confObs - confLissee) * lissageConf

        val cNombre = cadrage.composanteNombre(nbJoueurs)
        val cStabConst = 1f
        val k = taille * altAct

        // candidats par axe (bornes)
        val altCands = doubleArrayOf(
            altAct,
            (altAct + pasSimM).coerceIn(minM, maxM),
            (altAct - pasSimM).coerceIn(minM, maxM),
        )
        val latCands = doubleArrayOf(
            latAct,
            (latAct + pasLateralSimM).coerceIn(-ampMax, ampMax),
            (latAct - pasLateralSimM).coerceIn(-ampMax, ampMax),
        )

        // score d'un candidat (altitude, lateral, zoom) AVEC penalites de mouvement.
        // La taille EFFECTIVE dans l'image = taille geometrique (via altitude) * facteur zoom
        // optique. Le zoom n'agit que sur la taille (il ne bouge pas le drone).
        fun scoreCand(altCand: Double, latCand: Double, zoomCand: Float): Float {
            val tailleGeo = if (altCand > 1e-6) (k / altCand).toFloat() else taille
            val taillePred = (tailleGeo * zoomCand).coerceIn(0.001f, 1f)
            val cTaille = cadrage.scoreTailleContinu(taillePred)
            // deplacement drone (latCand-latAct) -> deplacement action dans l'image (sens oppose).
            val cxPred = (cx0 - (latCand - latAct).toFloat() * gainLateralImage).coerceIn(0f, 1f)
            val cLead = cadrage.composanteLeadRoom(cxPred, dirX)
            val cadr = cadrage.combiner(cTaille, cNombre, cLead, cStabConst)
            val confCand = confiancePredite(confLissee.toDouble(), taille.toDouble(), taillePred.toDouble())
            var s = (cadr * cadrage.facteurConfiance(confCand.toFloat())).coerceIn(0f, 1f)
            // penalites de mouvement (cout de bouger).
            s -= (kotlin.math.abs(altCand - altAct)).toFloat() * penaliteAltitude
            s -= (kotlin.math.abs(latCand - latAct)).toFloat() * penaliteLaterale
            s -= kotlin.math.abs(zoomCand - zoomCourant) * penaliteZoom
            // penalite de changement de sens lateral (anti gauche-droite).
            val sensLat = when {
                latCand > latAct + 1e-9 -> 1
                latCand < latAct - 1e-9 -> -1
                else -> 0
            }
            if (sensLat != 0 && dernierSensLat != 0 && sensLat != dernierSensLat) {
                s -= penaliteChangementDir
            }
            return s
        }

        // ETAPE 1 : grille 9 (altitude x lateral), zoom fige au courant.
        val scoreActuel = scoreCand(altAct, latAct, zoomCourant)
        var meilleurAlt = altAct
        var meilleurLat = latAct
        var meilleurScore = scoreActuel
        for (a in altCands) for (l in latCands) {
            val s = scoreCand(a, l, zoomCourant)
            if (s > meilleurScore) { meilleurScore = s; meilleurAlt = a; meilleurLat = l }
        }

        // ETAPE 2 : ZOOM en AJUSTEMENT FIN, seulement si zoom OPTIQUE CONTINU. On teste
        // zoom-/neutre/+ autour du MEILLEUR candidat alt/lat, et on ne bouge le zoom que si
        // le gain depasse un seuil SIGNIFICATIF (altitude d'abord, zoom en finition).
        val zoomActif = capaciteZoom.pilotableEnContinu()
        var zoomDesire = zoomCourant
        if (zoomActif) {
            val zoomCands = floatArrayOf(
                zoomCourant,
                (zoomCourant + pasZoom).coerceIn(zoomMin, zoomMax),
                (zoomCourant - pasZoom).coerceIn(zoomMin, zoomMax),
            )
            val baseZoom = scoreCand(meilleurAlt, meilleurLat, zoomCourant)
            var meilleurZoom = zoomCourant
            var meilleurScoreZoom = baseZoom
            for (z in zoomCands) {
                val s = scoreCand(meilleurAlt, meilleurLat, z)
                if (s > meilleurScoreZoom) { meilleurScoreZoom = s; meilleurZoom = z }
            }
            if (meilleurScoreZoom - baseZoom >= seuilZoom) zoomDesire = meilleurZoom
        }

        val donneesFiables = confLissee >= seuilConfianceDecision
        val bouge = donneesFiables && (meilleurScore - scoreActuel >= epsilonScore)
        val altDesiree = if (bouge) meilleurAlt else altAct
        val latDesire = if (bouge) meilleurLat else latAct
        // zoom : autorise meme si l'alt/lat ne bouge pas (ajustement fin), mais coupe si
        // donnees non fiables (zero-detection).
        val zoomCible = if (donneesFiables) zoomDesire else zoomCourant

        // rate limiters INDEPENDANTS (premiere frame : pas de mouvement).
        val maxDeltaAlt = if (premiereFrame) 0.0 else vitesseMaxMps * dt
        val maxDeltaLat = if (premiereFrame) 0.0 else vitesseLateraleMaxMps * dt
        val dAlt = (altDesiree - altCourante).coerceIn(-maxDeltaAlt, maxDeltaAlt)
        altCourante = (altCourante + dAlt).coerceIn(minM, maxM)
        val dLat = (latDesire - lateralCourant).coerceIn(-maxDeltaLat, maxDeltaLat)
        lateralCourant = (lateralCourant + dLat).coerceIn(-ampMax, ampMax)
        // rate limiter ZOOM (facteur/s), doux ; inactif si pas de zoom optique.
        if (zoomActif && !premiereFrame) {
            val maxDeltaZoom = vitesseZoomParS * dt.toFloat()
            val dZoom = (zoomCible - zoomCourant).coerceIn(-maxDeltaZoom, maxDeltaZoom)
            zoomCourant = (zoomCourant + dZoom).coerceIn(zoomMin, zoomMax)
        }
        premiereFrame = false

        val sensAlt = when {
            altDesiree > altAct + 1e-6 -> 1
            altDesiree < altAct - 1e-6 -> -1
            else -> 0
        }
        val sensLat = when {
            latDesire > latAct + 1e-9 -> 1
            latDesire < latAct - 1e-9 -> -1
            else -> 0
        }
        if (sensLat != 0) dernierSensLat = sensLat

        return Plan(
            altitudeM = altCourante,
            altitudeDesireeM = altDesiree,
            scoreActuel = scoreActuel,
            dtS = dt,
            sens = sensAlt,
            confianceLissee = if (confLissee.isNaN()) 0f else confLissee,
            lateralM = lateralCourant,
            lateralDesireM = latDesire,
            sensLateral = sensLat,
            zoom = zoomCourant,
            zoomActif = zoomActif,
        )
    }

    /**
     * CONFIANCE PREDITE (pure) pour une altitude candidate : si on change l'altitude, la
     * taille des joueurs change (taillePredite), et la confiance YOLO evolue avec (les
     * joueurs plus GROS sont mieux detectes, les plus PETITS moins bien). Modele V1 simple
     * base sur le ratio de taille ; a remplacer par une calibration reelle plus tard.
     *
     * @param confianceObservee confiance a l'altitude courante [0,1].
     * @param tailleObservee    taille joueur observee (unite image, >0).
     * @param taillePredite     taille joueur predite au candidat (unite image).
     */
    fun confiancePredite(confianceObservee: Double, tailleObservee: Double, taillePredite: Double): Double {
        if (!confianceObservee.isFinite() || !tailleObservee.isFinite() ||
            !taillePredite.isFinite() || tailleObservee <= 0.0) return 0.0
        val ratio = (taillePredite / tailleObservee).coerceIn(0.5, 1.15)
        return (confianceObservee * ratio).coerceIn(0.0, 1.0)
    }

    /** dt monotone, borne, repli si invalide, anti-saut apres pause longue.
     *  Utilise le flag [premiereFrame] (et non nanos<=0) pour ne pas confondre un
     *  timestamp legitime de 0 avec "pas encore initialise". */
    private fun calculerDt(nanosMonotone: Long): Double {
        if (premiereFrame) { dernierNanos = nanosMonotone; return dtRepliS }
        val brutS = (nanosMonotone - dernierNanos) / 1_000_000_000.0
        dernierNanos = nanosMonotone
        // dt <= 0 (horloge non monotone, meme frame) -> repli.
        val dt = if (brutS <= 0.0 || !brutS.isFinite()) dtRepliS else brutS
        // bornage : une pause longue est ECRETEE a dtMax -> pas de grand saut d'altitude.
        return dt.coerceIn(dtMinS, dtMaxS)
    }
}
