package ca.cineflight.stage.voice

/**
 * PHASE 3B — Surveillance vocale du SUIVI DU SUJET (cinematographie).
 *
 * PRINCIPE : observe l'etat consolide du suivi (position RTK du sujet, fraicheur,
 * qualite RTK, mode de fusion vision/RTK, age des donnees serveur) et annonce les
 * transitions importantes. Il n'agit JAMAIS sur le pilotage ni sur le suivi.
 *
 * ISOLATION : classe pure. Recoit des PRIMITIVES (pas les types de l'app) pour rester
 * decouplee. L'appelant (MainActivity) mappe ses enums vers ces primitives. Fail-open,
 * anti-bavardage : chaque famille n'annonce qu'au CHANGEMENT d'etat.
 *
 * Les annonces nomment TOUJOURS "sujet" pour lever l'ambiguite avec le GPS drone/pilote.
 */
class SubjectVoiceMonitor(
    private val systeme: FlightVoiceSystem
) {
    // qualite RTK : 0=LOST, 1=GPS(simple), 2=FLOAT, 3=FIX. -1 = inconnu.
    @Volatile private var rtkNiveauPrecedent = -1
    // presence + fraicheur : 0=perdu, 1=perime, 2=present-frais. -1 inconnu.
    @Volatile private var presenceNiveauPrecedent = -1
    // fusion : 0=bloque(perdu), 1=float/vision, 2=fix/complet. -1 inconnu.
    @Volatile private var fusionNiveauPrecedent = -1
    // serveur : 0=indisponible, 1=retard, 2=live. -1 inconnu.
    @Volatile private var serveurNiveauPrecedent = -1
    // true des qu'une session LIVE (niveau 2) a existe au moins une fois. Tant que c'est
    // faux (drone eteint / jamais connecte), on n'annonce AUCUNE perte serveur : il n'y a
    // rien a "perdre" et cela evitait un faux "donnees interrompues" repete en boucle.
    @Volatile private var serveurDejaLive = false
    @Volatile private var tropPrecedent = false

    private fun actif(): Boolean = try {
        systeme.settings.active && systeme.settings.observationDji
    } catch (_: Throwable) { false }

    private fun pub(e: FlightVoiceEvent) {
        try { systeme.publishVoiceEventSafely(e, System.currentTimeMillis()) } catch (_: Throwable) {}
    }
    private fun now() = System.currentTimeMillis()

    /**
     * PRESENCE + FRAICHEUR du sujet.
     * @param niveau 0=perdu (liaison balise), 1=perime (>seuil), 2=present et frais.
     * @param balise true si l'absence vient d'une perte de la balise (Pi), pas juste peremption.
     */
    fun observerPresence(niveau: Int, balise: Boolean) {
        runCatching {
            if (!actif()) { presenceNiveauPrecedent = niveau; return }
            if (niveau == presenceNiveauPrecedent) return
            val avant = presenceNiveauPrecedent
            presenceNiveauPrecedent = niveau
            when (niveau) {
                2 -> if (avant >= 0 && avant < 2) pub(VoiceEvents.subjectRestored(now()))
                     else if (avant == -1) { /* 1er etat : pas d'annonce */ }
                1 -> pub(VoiceEvents.subjectStale(now()))
                0 -> if (balise) pub(VoiceEvents.subjectBeaconLost(now()))
                     else pub(VoiceEvents.subjectLost(now()))
            }
        }
    }

    /** QUALITE RTK du sujet : 0=LOST,1=GPS,2=FLOAT,3=FIX. Transitions seulement. */
    fun observerRtkQualite(niveau: Int) {
        runCatching {
            if (!actif()) { rtkNiveauPrecedent = niveau; return }
            if (niveau == rtkNiveauPrecedent) return
            val avant = rtkNiveauPrecedent
            rtkNiveauPrecedent = niveau
            if (avant == -1) return   // 1er etat connu
            when (niveau) {
                3 -> pub(VoiceEvents.rtkSubjectFixed(now()))            // remonte a FIX
                2 -> if (avant == 3) pub(VoiceEvents.rtkSubjectDegraded(now()))  // FIX -> FLOAT
                1 -> if (avant >= 2) pub(VoiceEvents.rtkSubjectPrecisionLow(now())) // FLOAT -> GPS
                0 -> { /* LOST : gere par observerPresence (subject.lost) */ }
            }
        }
    }

    /**
     * MODE DE FUSION vision/RTK : reflete l'etat du systeme COMBINE.
     * @param niveau 0=bloque (sujet perdu), 1=float/vision pilote, 2=fix/complet.
     * @param visionOk true si la vision (YOLO) est valide a cet instant.
     * @param rtkOk true si le RTK sujet est valide a cet instant.
     */
    fun observerFusion(niveau: Int, visionOk: Boolean, rtkOk: Boolean) {
        runCatching {
            if (!actif()) { fusionNiveauPrecedent = niveau; return }
            if (niveau == fusionNiveauPrecedent) return
            val avant = fusionNiveauPrecedent
            fusionNiveauPrecedent = niveau
            if (avant == -1) return
            when (niveau) {
                2 -> if (avant == 0) pub(VoiceEvents.fusionSubjectFound(now()))
                     else pub(VoiceEvents.fusionStable(now()))
                1 -> {
                    // Passage en FLOAT/vision : preciser ce qui reste.
                    if (rtkOk && !visionOk) pub(VoiceEvents.fusionVisionLostRtkOk(now()))
                    else if (!rtkOk && visionOk) pub(VoiceEvents.fusionRtkLostVisionOk(now()))
                    else if (avant == 0) pub(VoiceEvents.fusionSubjectFound(now()))
                }
                0 -> pub(VoiceEvents.fusionBothLost(now()))
            }
        }
    }

    /** Desaccord important vision <-> RTK (deux positions incoherentes). */
    fun observerIncoherence(incoherent: Boolean) {
        runCatching {
            if (!actif()) return
            if (incoherent) pub(VoiceEvents.fusionIncoherent(now()))
        }
    }

    /** DISTANCE de securite avec le sujet : true = trop proche. Transition seulement. */
    fun observerTropProche(trop: Boolean) {
        runCatching {
            if (!actif()) { tropPrecedent = trop; return }
            if (trop == tropPrecedent) return
            tropPrecedent = trop
            if (trop) pub(VoiceEvents.subjectTooClose(now()))
        }
    }

    // ---------- PREDICTION ----------
    // etat : 0=attente, 1=disponible, 2=degrade, 3=suspendu. -1 inconnu.
    @Volatile private var predNiveauPrecedent = -1
    fun observerPrediction(niveau: Int) {
        runCatching {
            if (!actif()) { predNiveauPrecedent = niveau; return }
            if (niveau == predNiveauPrecedent) return
            predNiveauPrecedent = niveau
            when (niveau) {
                0 -> pub(VoiceEvents.predictionPending(now()))
                1 -> pub(VoiceEvents.predictionAvailable(now()))
                2 -> pub(VoiceEvents.predictionDegraded(now()))
                3 -> pub(VoiceEvents.predictionSuspended(now()))
            }
        }
    }

    fun observerTrajectoireIncertaine(incertaine: Boolean) {
        runCatching {
            if (!actif()) return
            if (incertaine) pub(VoiceEvents.predictionUncertain(now()))
        }
    }

    // ---------- SERVEUR / RESEAU (base sur l'AGE des donnees) ----------
    /** niveau : 0=indisponible, 1=retard eleve, 2=live. Transition seulement. */
    fun observerServeur(niveau: Int) {
        runCatching {
            if (!actif()) { serveurNiveauPrecedent = niveau; return }
            if (niveau == serveurNiveauPrecedent) return
            val avant = serveurNiveauPrecedent
            serveurNiveauPrecedent = niveau
            if (avant == -1) return
            // Tant qu'aucune session LIVE n'a jamais existe (drone eteint / non connecte),
            // on NE PARLE PAS de perte serveur : rien n'a ete perdu. Cela supprime le faux
            // "Donnees du sujet interrompues" qui se repetait en boucle a l'ouverture.
            if (niveau == 2) serveurDejaLive = true
            if (!serveurDejaLive) return
            when (niveau) {
                2 -> pub(VoiceEvents.serverRestored(now()))
                1 -> pub(VoiceEvents.serverDataDelayed(now()))
                0 -> if (avant == 1) pub(VoiceEvents.serverDataInterrupted(now()))
                     else pub(VoiceEvents.serverUnavailable(now()))
            }
        }
    }

    /** Reinitialise a chaque nouvelle session (pas d'annonce residuelle). */
    fun nouvelleSessionVol() {
        runCatching {
            rtkNiveauPrecedent = -1; presenceNiveauPrecedent = -1
            fusionNiveauPrecedent = -1; serveurNiveauPrecedent = -1
            serveurDejaLive = false
            predNiveauPrecedent = -1; tropPrecedent = false
        }
    }
}
