package ca.cineflight.stage.sentinelle

import ca.cineflight.stage.control.PiloteDrone

/**
 * ExempleIntegration.kt — Comment brancher la Sentinelle V2 dans CineFlight Solo.
 *
 * Ce fichier montre :
 *   1) l'ADAPTATEUR qui relie PontDrone (interface) au vrai PontDji ;
 *   2) comment alimenter EtatCapteurs depuis YOLO / vidéo / télémétrie ;
 *   3) la boucle de cycle (à appeler ~10 Hz depuis un Handler/coroutine).
 *
 * NOTE : ce fichier est un MODÈLE. Les appels à `pontDji` et aux sources de
 * télémétrie/YOLO sont à brancher sur tes objets réels. La logique (noyau +
 * machine) est, elle, déjà validée et ne change pas.
 */

// ---------------------------------------------------------------------------
//  1. ADAPTATEUR : PontDji réel -> interface PontDrone
// ---------------------------------------------------------------------------
//
// PontDji (interface imbriquée dans PiloteDrone) utilise des Float ; PontDrone
// utilise des Double. L'adaptateur fait la conversion. C'est le SEUL endroit qui
// connaît ce détail : le noyau et la machine restent en Double, intacts.
// Note : le type PiloteDrone.PontDji accepte aussi bien PontDjiReel (vrai drone)
// que PontDjiSimule (simulateur) — pratique pour tester.

class PontDjiAdapter(private val pont: PiloteDrone.PontDji) : PontDrone {
    override fun envoyerVitesses(pitch: Double, roll: Double, throttle: Double, yaw: Double) {
        pont.envoyerVitesses(pitch.toFloat(), roll.toFloat(), throttle.toFloat(), yaw.toFloat())
    }
    override fun orienterNacelle(pitchDeg: Double, yawDeg: Double, yawAbsolu: Boolean) {
        pont.orienterNacelle(pitchDeg.toFloat(), yawDeg.toFloat(), yawAbsolu)
    }
}


// ---------------------------------------------------------------------------
//  2. Construire EtatCapteurs à chaque cycle depuis tes sources réelles
// ---------------------------------------------------------------------------
//
// À remplir depuis : l'état du flux vidéo, le détecteur YOLO (AgregateurReperage),
// la télémétrie DJI, et le bouton STOP de l'UI.
//
//   fun lireCapteurs(): EtatCapteurs = EtatCapteurs(
//       videoOk            = fluxVideo.estActif(),
//       yoloActif          = yolo.aReponduRecemment(),
//       telemetrieOk       = telemetrie.estValide(),
//       intrusionDetectee  = yolo.intrusionPersonneOuAnimal(),
//       stopPilote         = ui.stopPresse(),
//       ageVideoS          = fluxVideo.ageDerniereFrameS(),
//       ageTelemetrieS     = telemetrie.ageS()
//   )


// ---------------------------------------------------------------------------
//  3. CONTRÔLEUR : assemble tout et fait tourner la boucle
// ---------------------------------------------------------------------------
//
// ÉVOLUTION FUTURE (à ne PAS implémenter maintenant) : un listener pour découpler
// l'UI de la boucle. Le contrôleur notifierait les changements plutôt que de
// laisser l'UI lire l'état à chaque tick. Esquisse :
//
//   interface SentinelleListener {
//       fun onEtatChange(ancien: EtatV2, nouveau: EtatV2)
//       fun onScore(score: Double, confiance: Double)
//       fun onBlocage(raison: RaisonBlocage, detail: DetailErreur)
//       fun onProposition(altitudeM: Double, score: Double)   // PROPOSER_DEPART
//   }
//
// Pour l'instant l'UI lit simplement controleur.etat / noyauRef.derniereRaison
// à chaque tick (voir l'exemple de boucle en bas). Le listener viendra quand
// l'UI sera câblée.
class SentinelleControleur(
    pont: PontDrone,
    private val scoreFn: () -> Pair<Double, Double>,   // brancher score_ouverture (TFLite/ONNX)
    config: ConfigV2 = ConfigV2()
) {
    private val noyau = NoyauSecurite(pont)
    private val machine = MachineV2(noyau, scoreFn, config, dt = 0.1)

    val etat: EtatV2 get() = machine.etat
    val noyauRef: NoyauSecurite get() = noyau

    /**
     * À appeler à intervalle régulier (~10 Hz). `lireCapteurs` et la télémétrie
     * sont fournis par l'appelant à chaque tick.
     */
    fun tick(
        capteurs: EtatCapteurs,
        altitudeM: Double,
        capDeg: Double,
        batteriePct: Double,
        piloteAccepte: Boolean = false,
        piloteAnnule: Boolean = false
    ): EtatV2 {
        val e = EntreeCycle(
            capteurs = capteurs,
            altitudeM = altitudeM,
            capDeg = capDeg,
            batteriePct = batteriePct,
            piloteAccepte = piloteAccepte,
            piloteAnnule = piloteAnnule
        )
        return machine.cycle(e)
    }

    /** STOP câblé en dur, hors logique (ex. bouton matériel). */
    fun arretImmediat() = noyau.arretImmediat()

    /** Le pilote acquitte une erreur système. */
    fun acquitterErreur() = noyau.acquitterErreur()
}

// ---------------------------------------------------------------------------
//  EXEMPLE de boucle (pseudo, à placer dans une coroutine / Handler de l'app) :
//
//   val controleur = SentinelleControleur(
//       pont = PontDjiAdapter(monPontDji),
//       scoreFn = { scoreOuverture.evaluerFrameCourante() }  // (score, confiance)
//   )
//
//   // toutes les 100 ms, tant qu'on n'est pas TERMINE :
//   val capteurs = lireCapteurs()
//   val etat = controleur.tick(
//       capteurs   = capteurs,
//       altitudeM  = telemetrie.altitudeAGL(),
//       capDeg     = telemetrie.cap(),
//       batteriePct= telemetrie.batterie(),
//       piloteAccepte = uiBoutonValider.estPresse()
//   )
//   when (etat) {
//       EtatV2.PROPOSER_DEPART -> ui.afficherProposition(controleur /* altitude, score */)
//       EtatV2.ARRET_PLAFOND   -> ui.afficherArretPlafond()
//       EtatV2.TERMINE         -> lancerMission()   // la Sentinelle a fini son travail
//       else                   -> ui.afficherEtat(etat, controleur.noyauRef.derniereRaison)
//   }
// ---------------------------------------------------------------------------

