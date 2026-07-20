package ca.cineflight.stage.sentinelle

import ca.cineflight.stage.control.EtatCockpit

/**
 * BrancheSentinelle.kt — Relie la Sentinelle V2 aux donnees reelles de l'app.
 *
 * Convertit l'EtatCockpit (telemetrie reelle) en EtatCapteurs (ce que le noyau
 * attend) + EntreeCycle (ce que la machine attend), puis fait avancer un cycle.
 *
 * >>> AVERTISSEMENT DE SECURITE (premier branchement) <<<
 * L'anti-intrusion YOLO temps reel (personnes/animaux) N'EST PAS encore cable.
 * intrusionDetectee est force a false. La Sentinelle protege donc contre STOP
 * pilote, perte video, perte telemetrie — mais PAS encore contre une personne
 * entrant dans le champ. NE PAS utiliser en presence de public tant que YOLO
 * temps reel n'est pas branche (voir brancherIntrusionYolo ci-dessous).
 *
 * >>> AJOUT RTK (mode sujet mobile, option A) <<<
 * rtkSujetOk transporte l'etat du suivi du sujet RTK (SuiviSujetRtk) jusqu'au
 * noyau. false = sujet perdu OU drone sous la distance minimale (3 m) -> le noyau
 * gele le drone (doctrine "non vu != sur"). Defaut true : un vol SANS mode sujet
 * n'est pas affecte.
 */

/** Mapping prudent EtatCockpit -> EtatCapteurs (regle : inconnu = non OK). */
fun lireCapteurs(
    etat: EtatCockpit,
    stopPilote: Boolean,
    intrusionDetectee: Boolean = false,   // temporaire : YOLO temps reel non cable
    yoloActif: Boolean = true,            // temporaire : idem
    rtkSujetOk: Boolean = true            // AJOUT RTK : suivi sujet sur ? (defaut true si mode sujet inactif)
): EtatCapteurs = EtatCapteurs(
    videoOk           = etat.signalVideoPct > 0,   // -1 inconnu ou 0 perdu -> non OK
    yoloActif         = yoloActif,
    telemetrieOk      = etat.connecte,             // drone connecte
    intrusionDetectee = intrusionDetectee,
    stopPilote        = stopPilote,
    ageVideoS         = 0.0,    // pas d'horodatage de frame ici ; signalVideoPct sert de garde
    ageTelemetrieS    = 0.0,
    rtkSujetOk        = rtkSujetOk                  // AJOUT RTK
)

/**
 * Controleur d'integration : assemble noyau + machine + adaptateur, et expose un
 * `tick(etat, stopPilote, ...)` a appeler dans la boucle de l'app (~10 Hz).
 *
 * @param pont      le sink securite (PontPiloteSecuriteAdapter en prod, ou
 *                  PontDjiAdapter(pontSimule) en test). Voir NON-DUPLICATION.
 * @param scoreFn   fournit (score 0..100, confiance 0..100). Au debut : score fixe.
 * @param config    parametres de montee (NON definitifs)
 * @param dt        pas de temps entre deux tick() — DOIT correspondre a la cadence reelle
 */
class SentinelleRuntime(
    pont: PontDrone,
    scoreFn: () -> Pair<Double, Double>,
    config: ConfigV2 = ConfigV2(),
    private val dt: Double = 0.1
) {
    private val noyau = NoyauSecurite(pont)
    private val machine = MachineV2(noyau, scoreFn, config, dt)

    val etat: EtatV2 get() = machine.etat
    val derniereRaison: RaisonBlocage get() = noyau.derniereRaison
    val detailErreur: DetailErreur get() = noyau.detailErreur
    val nbPaliers: Int get() = machine.nbPaliers
    val dernierScore: Double? get() = machine.dernierScore
    val derniereConfiance: Double? get() = machine.derniereConfiance

    /** AVERTISSEMENT a afficher dans l'UI tant que YOLO temps reel n'est pas cable. */
    val antiIntrusionActif: Boolean = false

    /**
     * Un cycle de la Sentinelle, alimente par la telemetrie reelle.
     * A appeler a cadence fixe (~10 Hz) tant que etat != TERMINE.
     */
    fun tick(
        etat: EtatCockpit,
        stopPilote: Boolean,
        piloteAccepte: Boolean = false,
        piloteAnnule: Boolean = false,
        intrusionDetectee: Boolean = false,
        plafondCapteurM: Double = Double.MAX_VALUE,
        rtkSujetOk: Boolean = true                     // AJOUT RTK
    ): EtatV2 {
        val capteurs = lireCapteurs(etat, stopPilote, intrusionDetectee, rtkSujetOk = rtkSujetOk)  // AJOUT RTK
        val entree = EntreeCycle(
            capteurs = capteurs,
            altitudeM = if (etat.altitudeAgl.isNaN()) 0.0 else etat.altitudeAgl,
            capDeg = if (etat.capDeg.isNaN()) 0.0 else etat.capDeg.toDouble(),
            batteriePct = if (etat.batteriePct < 0) 0.0 else etat.batteriePct.toDouble(),
            piloteAccepte = piloteAccepte,
            piloteAnnule = piloteAnnule,
            plafondCapteurM = plafondCapteurM
        )
        return machine.cycle(entree)
    }

    fun arretImmediat() = noyau.arretImmediat()
    fun acquitterErreur() = noyau.acquitterErreur()
    fun journalSecurite(): List<EvenementSecurite> = noyau.historique
    fun journalMachine(): List<String> = machine.journal
}

// ---------------------------------------------------------------------------
//  A FAIRE plus tard : brancher l'intrusion YOLO temps reel.
//
//  AgregateurReperage actuel produit un RAPPORT de reconnaissance (accumulation
//  de frames), pas un signal d'intrusion instantane. Il faudra exposer, depuis le
//  pipeline YOLO temps reel, un simple booleen "une personne/animal est visible
//  maintenant" (classes 0,15,16,17,18,19), puis le passer a lireCapteurs(...) via
//  le parametre intrusionDetectee. Tant que ce n'est pas fait, antiIntrusionActif
//  reste false et l'UI DOIT l'indiquer.
// ---------------------------------------------------------------------------

