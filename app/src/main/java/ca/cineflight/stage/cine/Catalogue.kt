package ca.cineflight.stage.cine

/**
 * Le CATALOGUE : la couche visible. Ce que l'utilisateur choisit.
 * Chaque recette pointe vers un Effet (entree dans la grammaire) ; certaines fixent
 * deja la Scene (recettes-completes), d'autres la demandent ensuite (recettes-effet).
 */

enum class Famille { EFFET, COMPLETE }

data class Recette(
    val emoji: String,
    val nom: String,
    val accroche: String,
    val famille: Famille,
    val effet: Effet,
    val sceneFixe: Scene? = null,                 // non-null pour les recettes-completes
    val sujetsCompatibles: List<Scene> = emptyList(),
    val espaceRequis: Espace = Espace.MOYEN,       // prerequis pour le validateur
    val dureeS: Int = 24,                          // duree estimee du plan
    val signature: Signature = Signature.AUCUNE    // variation cinema pour differencier des recettes proches
) {
    val estComplete: Boolean get() = sceneFixe != null
}

object Catalogue {

    // --- Les recettes vedettes (noms confirmes, registre evocateur grand public) ---
    val VEDETTES: List<Recette> = listOf(
        // Recettes-effet
        Recette("🏔️", "Panorama epique", "Revelez toute la grandeur du lieu.",
            Famille.EFFET, Effet.GRANDEUR,
            sujetsCompatibles = listOf(Scene.PAYSAGE, Scene.GROUPE_DANSE, Scene.GROUPE_MUSIQUE),
            espaceRequis = Espace.LARGE, dureeS = 26),
        Recette("🎬", "Entree spectaculaire", "Creez une ouverture digne d'un film.",
            Famille.EFFET, Effet.GRANDEUR,
            sujetsCompatibles = listOf(Scene.DANSEUR_SOLO, Scene.CHANTEUR, Scene.GROUPE_MUSIQUE),
            espaceRequis = Espace.MOYEN, dureeS = 20),
        Recette("🌅", "Revelation magique", "Devoilez progressivement le decor.",
            Famille.EFFET, Effet.MYSTERE,
            sujetsCompatibles = listOf(Scene.PAYSAGE, Scene.MARIAGE),
            espaceRequis = Espace.LARGE, dureeS = 28),
        Recette("🎉", "Ambiance festive", "Capturez l'energie d'un groupe.",
            Famille.EFFET, Effet.CELEBRATION,
            sujetsCompatibles = listOf(Scene.GROUPE_DANSE, Scene.GROUPE_MUSIQUE, Scene.MARIAGE),
            espaceRequis = Espace.LARGE, dureeS = 24),
        Recette("🏃", "Action dynamique", "Suivez le mouvement sans effort.",
            Famille.EFFET, Effet.ENERGIE,
            sujetsCompatibles = listOf(Scene.ATHLETE, Scene.DANSEUR_SOLO),
            espaceRequis = Espace.MOYEN, dureeS = 22),
        Recette("💍", "Moment precieux", "Mettez l'emotion au premier plan.",
            Famille.EFFET, Effet.EMOTION,
            sujetsCompatibles = listOf(Scene.MARIAGE, Scene.CHANTEUR, Scene.DANSEUR_SOLO),
            espaceRequis = Espace.MOYEN, dureeS = 16),
        Recette("🎭", "Presence intime", "Une ambiance douce et rapprochee.",
            Famille.EFFET, Effet.INTIMITE,
            sujetsCompatibles = listOf(Scene.CHANTEUR, Scene.DISCOURS, Scene.DANSEUR_SOLO),
            espaceRequis = Espace.RESTREINT, dureeS = 16),
        Recette("🔥", "Energie maximale", "Du rythme, du mouvement, de l'impact.",
            Famille.EFFET, Effet.ENERGIE,
            sujetsCompatibles = listOf(Scene.GROUPE_DANSE, Scene.ATHLETE),
            espaceRequis = Espace.LARGE, dureeS = 24, signature = Signature.ORBITE_FINALE),
        Recette("✨", "Souvenir inoubliable", "Un beau plan simple et sur, a tous les coups.",
            Famille.EFFET, Effet.EMOTION,
            sujetsCompatibles = Scene.values().toList(),
            espaceRequis = Espace.RESTREINT, dureeS = 18),
        // Recettes-completes (scene fixee)
        Recette("🚗", "Evasion sur la route", "Parfait pour vehicules et deplacements.",
            Famille.COMPLETE, Effet.LIBERTE, sceneFixe = Scene.VEHICULE,
            sujetsCompatibles = listOf(Scene.VEHICULE),
            espaceRequis = Espace.LARGE, dureeS = 24),
        Recette("🎤", "Performance en vedette", "Mettez un artiste au centre de l'attention.",
            Famille.COMPLETE, Effet.EMOTION, sceneFixe = Scene.CHANTEUR,
            sujetsCompatibles = listOf(Scene.CHANTEUR),
            espaceRequis = Espace.MOYEN, dureeS = 20, signature = Signature.ORBITE_FINALE),
        Recette("🌲", "Immersion nature", "Plongez dans un paysage vivant.",
            Famille.COMPLETE, Effet.GRANDEUR, sceneFixe = Scene.PAYSAGE,
            sujetsCompatibles = listOf(Scene.PAYSAGE),
            espaceRequis = Espace.LARGE, dureeS = 26),
        // Recettes ciblees marche quebecois (memes mouvements, nom qui parle au client)
        Recette("🏡", "Mon chalet en vedette", "Sublimez votre chalet et son terrain.",
            Famille.COMPLETE, Effet.GRANDEUR, sceneFixe = Scene.PAYSAGE,
            sujetsCompatibles = listOf(Scene.PAYSAGE),
            espaceRequis = Espace.LARGE, dureeS = 26, signature = Signature.ORBITE_FINALE),
        Recette("🏠", "Maison a vendre", "Une presentation immobiliere qui se demarque.",
            Famille.COMPLETE, Effet.GRANDEUR, sceneFixe = Scene.PAYSAGE,
            sujetsCompatibles = listOf(Scene.PAYSAGE),
            espaceRequis = Espace.MOYEN, dureeS = 24, signature = Signature.ORBITE_TETE),
        // --- Souvenirs familiaux et nature (environnements controles, sujet connu) ---
        Recette("👨\u200D👩\u200D👧\u200D👦", "Reunion de famille", "Le plan souvenir reunissant toutes les generations.",
            Famille.COMPLETE, Effet.EMOTION, sceneFixe = Scene.FAMILLE,
            sujetsCompatibles = listOf(Scene.FAMILLE),
            espaceRequis = Espace.MOYEN, dureeS = 22),
        Recette("🎂", "Fete a celebrer", "Anniversaire, retraite : capturez la joie du groupe.",
            Famille.COMPLETE, Effet.CELEBRATION, sceneFixe = Scene.FAMILLE,
            sujetsCompatibles = listOf(Scene.FAMILLE),
            espaceRequis = Espace.MOYEN, dureeS = 22),
        Recette("🌇", "Coucher de soleil", "Un sujet isole sublime par la lumiere doree.",
            Famille.EFFET, Effet.MYSTERE,
            sujetsCompatibles = listOf(Scene.PAYSAGE, Scene.MARIAGE, Scene.DANSEUR_SOLO, Scene.FAMILLE),
            espaceRequis = Espace.MOYEN, dureeS = 24, signature = Signature.FINAL_STATIQUE),
        Recette("🚣", "Sur l'eau", "Accompagnez un kayak, un voilier ou un nageur.",
            Famille.EFFET, Effet.LIBERTE,
            sujetsCompatibles = listOf(Scene.ATHLETE, Scene.VEHICULE, Scene.PAYSAGE),
            espaceRequis = Espace.LARGE, dureeS = 24)
    )

    // --- Recettes speciales ---

    /** "Premier vol" : ultra-sure, pour reussir sa toute premiere video. */
    val PREMIER_VOL = Recette(
        "🚁", "Premier vol", "Une premiere video reussie, en toute securite.",
        Famille.COMPLETE, Effet.EMOTION, sceneFixe = Scene.DANSEUR_SOLO,
        sujetsCompatibles = Scene.values().toList(),
        espaceRequis = Espace.RESTREINT, dureeS = 14
    )

    /** La valeur sure universelle, utilisee comme repli partout. */
    val VALEUR_SURE: Recette get() = VEDETTES.first { it.nom == "Souvenir inoubliable" }

    /**
     * "Surprends-moi" : choisit la meilleure recette selon les FAITS mesures, jamais au hasard.
     * faits = ce que YOLO/utilisateur a mesure ; si rien d'exploitable -> valeur sure.
     */
    fun surprendsMoi(sujets: Int, espace: Espace, beaucoupDeMouvement: Boolean): Recette {
        return when {
            sujets == 0                                  -> trouve("Panorama epique")
            sujets >= 3 && beaucoupDeMouvement && espace == Espace.LARGE -> trouve("Energie maximale")
            sujets >= 3 && beaucoupDeMouvement           -> trouve("Ambiance festive")
            sujets in 1..2 && !beaucoupDeMouvement       -> trouve("Moment precieux")
            sujets == 1 && beaucoupDeMouvement           -> trouve("Action dynamique")
            espace == Espace.RESTREINT                   -> trouve("Presence intime")
            else                                         -> VALEUR_SURE   // repli sur
        }
    }

    private fun trouve(nom: String): Recette = VEDETTES.firstOrNull { it.nom == nom } ?: VALEUR_SURE

    /** Cle stable d'une recette, derivee du nom : majuscules, sans accents, espaces -> _.
     *  Ex : "Panorama epique" -> "PANORAMA_EPIQUE". Sert d'identifiant pour le LLM vision. */
    fun cleDe(r: Recette): String = normaliserCle(r.nom)

    private fun normaliserCle(nom: String): String {
        val sansAccent = nom
            .replace("Ã©", "e").replace("Ã¨", "e").replace("Ãª", "e").replace("Ã ", "a")
            .replace("â", "a").replace("î", "i").replace("ô", "o").replace("û", "u")
            .replace("ç", "c")
            .replace("É", "E").replace("È", "E")
        return sansAccent.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
    }

    /** Resout des cles de recettes (issues du LLM vision) en recettes reelles.
     *  Les cles inconnues sont ignorees ; si rien ne matche, repli sur la valeur sure. */
    fun parRecettesCles(cles: List<String>): List<Recette> {
        val toutes = VEDETTES + PREMIER_VOL
        val out = LinkedHashSet<Recette>()
        for (cle in cles) {
            val k = normaliserCle(cle)
            toutes.firstOrNull { normaliserCle(it.nom) == k }?.let { out.add(it) }
        }
        if (out.isEmpty()) out.add(VALEUR_SURE)
        return out.toList()
    }

    /** "Surprends-moi" a partir d'un rapport de reperage YOLO (faits mesures). */
    fun surprendsMoi(rapport: RapportReperage): Recette =
        surprendsMoi(rapport.sujets, rapport.espaceLibre, rapport.beaucoupDeMouvement)

    /**
     * Analyse auto : propose 2-3 recettes pertinentes a partir des faits, sans prétendre
     * connaitre le type de scene. L'utilisateur choisit ensuite.
     */
    fun suggestions(rapport: RapportReperage): List<Recette> {
        val s = rapport.sujets
        val large = rapport.espaceLibre == Espace.LARGE
        val restreint = rapport.espaceLibre == Espace.RESTREINT
        val bouge = rapport.beaucoupDeMouvement
        val out = LinkedHashSet<Recette>()
        when {
            s == 0 -> { out.add(trouve("Panorama epique")); out.add(trouve("Immersion nature")); out.add(trouve("Revelation magique")) }
            s >= 3 && bouge -> { out.add(trouve("Energie maximale")); out.add(trouve("Ambiance festive")); if (large) out.add(trouve("Panorama epique")) }
            s >= 3 -> { out.add(trouve("Ambiance festive")); out.add(trouve("Entree spectaculaire")) }
            s in 1..2 && bouge -> { out.add(trouve("Action dynamique")); out.add(trouve("Moment precieux")) }
            s in 1..2 -> { out.add(trouve("Moment precieux")); out.add(trouve("Presence intime")); out.add(trouve("Entree spectaculaire")) }
        }
        if (restreint) out.add(trouve("Presence intime"))
        // --- FAITS SECONDAIRES (disposition/position/direction) : ils RE-CLASSENT le vivier,
        //     sans jamais exclure une recette. La valeur sure reste le filet, en dernier. ---
        val filet = VALEUR_SURE
        val ordonnees = out.filter { it.nom != filet.nom }
            .sortedByDescending { scoreSecondaire(it, rapport) }  // tri STABLE : ex-aequo = ordre des faits principaux
        val finale = LinkedHashSet<Recette>()
        finale.addAll(ordonnees)
        finale.add(filet)  // filet de securite, jamais promu par les faits secondaires
        return finale.toList().take(3)
    }

    /** Poids SECONDAIRE d'une recette selon les faits fins (disposition, position, direction du
     *  mouvement). Ne peut jamais exclure une recette : il ne fait que la remonter/descendre dans
     *  la liste. Volontairement PLUS FAIBLE que les faits principaux (sujets/espace/energie), et
     *  divise par deux quand la mesure est peu fiable (confiance faible). */
    private fun scoreSecondaire(r: Recette, rapport: RapportReperage): Int {
        var b = 0
        when (rapport.disposition) {
            Disposition.DISPERSEE -> if (r.espaceRequis == Espace.LARGE) b += 2   // disperses -> plans larges
            Disposition.GROUPEE   -> if (r.espaceRequis != Espace.LARGE) b += 2   // groupes  -> plans plus serres
            Disposition.ISOLEE    -> {}
        }
        when (rapport.mouvement) {
            MouvementScene.LATERAL  -> if (r.effet == Effet.ENERGIE || r.effet == Effet.LIBERTE) b += 2   // -> travelling/suivi
            MouvementScene.VERTICAL -> if (r.effet == Effet.GRANDEUR || r.effet == Effet.MYSTERE) b += 2  // -> revelation
            MouvementScene.STATIQUE -> {}
        }
        if (rapport.position != Position.CENTRE &&
            (r.effet == Effet.ENERGIE || r.effet == Effet.LIBERTE)) b += 1   // decadre -> un mouvement lateral aide a recomposer
        if (rapport.confiance < 0.6f) b /= 2   // mesure peu fiable : les faits secondaires pesent moins
        return b
    }

    /**
     * Traduit une recette + un sujet en sequence brute via la grammaire.
     * Pour une recette-complete, le sujet est ignore (scene deja fixee).
     * Si reglages != null, applique le facteur de duree et le decalage de vitesse.
     */
    fun versSequence(recette: Recette, sujetChoisi: Scene?, reglages: ReglagesCine? = null): Sequence {
        val scene = recette.sceneFixe ?: sujetChoisi
            ?: recette.sujetsCompatibles.firstOrNull()
            ?: Scene.DANSEUR_SOLO
        // Duree de BASE : le facteur est applique PAR PAS ci-dessous, jamais globalement.
        val brute = Grammaire.appliquerSignature(
            Grammaire.generer(scene, recette.effet, recette.dureeS),
            recette.signature
        )
        if (reglages == null) return brute
        val cle = cleDe(recette)
        val f = reglages.getFacteurDureeRecette(cle)
        // Le facteur s'applique SELON le style choisi pour cette recette.
        return when (reglages.getStyleDuree(cle)) {
            // NORMAL : n'allonge que les temps SUR PLACE (orbite/statique/spotlight). Les
            // deplacements gardent leur duree de base (les prolonger les ferait aller plus loin).
            StyleDuree.NORMAL -> Sequence(brute.pas.map { p ->
                val fp = if (p.mouvement.enPlace) f else 1f
                p.copy(dureeS = (p.dureeS * fp).toInt().coerceAtLeast(4),
                       vitesse = reglages.vitesseAjustee(p.vitesse))
            })
            // RALENTI : TOUT est allonge (duree x f) ET ralenti (vitesse / f) -> meme distance.
            //  Le ralentissement reel est fait par l'executeur/pilote via facteurVitesse.
            StyleDuree.RALENTI -> Sequence(brute.pas.map { p ->
                p.copy(dureeS = (p.dureeS * f).toInt().coerceAtLeast(4),
                       vitesse = reglages.vitesseAjustee(p.vitesse),
                       facteurVitesse = if (f > 1f) (1f / f) else 1f)
            })
            // PLAN_TENU : comme NORMAL + un plan fixe tenu a la fin, pour completer jusqu'a total x f.
            StyleDuree.PLAN_TENU -> {
                val base = brute.pas.map { p ->
                    val fp = if (p.mouvement.enPlace) f else 1f
                    p.copy(dureeS = (p.dureeS * fp).toInt().coerceAtLeast(4),
                           vitesse = reglages.vitesseAjustee(p.vitesse))
                }
                val cible = (brute.dureeTotaleS * f).toInt()
                val reste = cible - base.sumOf { it.dureeS }
                Sequence(if (reste >= 2) {
                    val plan = base.lastOrNull()?.plan ?: Plan.ENSEMBLE
                    base + Pas(Mouvement.STATIQUE, Vitesse.LENTE, plan, reste)
                } else base)
            }
        }
    }
}



