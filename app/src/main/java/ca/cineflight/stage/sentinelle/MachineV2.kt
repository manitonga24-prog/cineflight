package ca.cineflight.stage.sentinelle

/**
 * MACHINE À ÉTATS V2 — Couche 2 de la Sentinelle V2.
 *
 * Traduction Kotlin du module Python validé (38/38 tests).
 *
 * Orchestre la recherche d'altitude par ouverture :
 *   ARME -> MONTEE_PALIER -> STATIONNAIRE -> BALAYAGE_360
 *        -> EVALUATION_OUVERTURE -> (MONTEE_PALIER | PROPOSER_DEPART | ARRET_PLAFOND)
 *
 * >>> INVARIANT CENTRAL (approche B) <<<
 * La V2 ne commande JAMAIS le drone directement. À chaque cycle elle SOUMET une
 * intention au noyau. Si le noyau retourne autre chose que AUCUNE, la V2 NE
 * PROGRESSE PAS : aucun compteur n'avance (altitude cible, temps de scan, angle,
 * nombre de paliers). Le drone est gelé par le noyau ; la V2 gèle son état et
 * réessaie au cycle suivant.
 *
 * Le SCORE d'ouverture est fourni par une lambda injectée (scoreFn), pour rester
 * découplé du module de score. scoreFn() retourne Pair(score 0..100, confiance 0..100).
 *
 * >>> IMPORTANT : cycle() DOIT être appelé à fréquence quasi constante (~10 Hz). <<<
 * Les temporisations (tStationnaire, durée de scan) et l'accumulation d'angle de
 * balayage sont calculées à partir de `dt`. Un appel à fréquence variable (2 Hz
 * puis 30 Hz) fausserait les durées et pourrait faire « sauter » l'angle cumulé.
 * Utiliser un Handler/timer à intervalle fixe, et passer le MÊME dt qu'à la
 * fréquence réelle d'appel.
 */

enum class EtatV2 {
    ARME,
    MONTEE_PALIER,
    STATIONNAIRE,
    BALAYAGE_360,
    EVALUATION_OUVERTURE,
    PROPOSER_DEPART,     // score >= seuil et confiance OK : on s'arrête, pilote valide
    ARRET_PLAFOND,       // plafond/paliers atteint sans ouverture
    TERMINE              // pilote a validé : la mission peut commencer
}

/** Paramètres de la montée. NON définitifs (calibration, cf. spec §3.3/3.4). */
data class ConfigV2(
    val pasPalierM: Double = 5.0,
    val altitudeMaxM: Double = 35.0,        // verrou 1 : plafond dur
    val plafondCapteurMinM: Double = 3.0,   // verrou 3 : obstacle capteur au-dessus (m). A calibrer.
    val paliersMax: Int = 7,                // verrou 2 : anti-emballement
    val seuilScore: Double = 60.0,          // NON définitif
    val seuilConfiance: Double = 50.0,      // verrou §11ter.3bis
    val vitesseMonteeMs: Double = 1.0,
    val vitesseYawDegs: Double = 20.0,
    val dureeStationnaireS: Double = 1.0,
    val batterieMinPct: Double = 40.0
)

/** Ce que la V2 reçoit à chaque cycle pour décider. */
data class EntreeCycle(
    val capteurs: EtatCapteurs,
    val altitudeM: Double,
    val capDeg: Double,
    val batteriePct: Double = 100.0,
    val piloteAccepte: Boolean = false,
    val piloteAnnule: Boolean = false,
    val plafondCapteurM: Double = Double.MAX_VALUE   // distance obstacle au-dessus (capteurs). MAX = rien detecte.
)

class MachineV2(
    private val noyau: NoyauSecurite,
    private val scoreFn: () -> Pair<Double, Double>,   // (score, confiance)
    val cfg: ConfigV2 = ConfigV2(),
    private val dt: Double = 0.1
) {
    var etat: EtatV2 = EtatV2.ARME
        private set
    var nbPaliers: Int = 0
        private set
    var altitudeCibleM: Double = 0.0
        private set
    var dernierScore: Double? = null
        private set
    var derniereConfiance: Double? = null
        private set
    var derniereRaisonNoyau: RaisonBlocage = RaisonBlocage.AUCUNE
        private set

    private var tStationnaire = 0.0
    private var capBalayageCumule = 0.0
    private var capPrecedent: Double? = null

    private val journalInterne = mutableListOf<String>()
    val journal: List<String> get() = journalInterne.toList()

    private fun log(msg: String) { journalInterne.add("[${etat.name}] $msg") }

    // ------------------------------------------------------------------
    //  Un cycle de la machine. Retourne l'état courant après traitement.
    // ------------------------------------------------------------------
    fun cycle(e: EntreeCycle): EtatV2 {
        // État terminal : plus rien
        if (etat == EtatV2.TERMINE) return etat

        // Attente d'une décision pilote : pas de mouvement, on attend
        if (etat == EtatV2.PROPOSER_DEPART || etat == EtatV2.ARRET_PLAFOND) {
            return gererAttentePilote(e)
        }

        // États en mouvement : calculer une INTENTION, la SOUMETTRE au noyau
        val (pitch, roll, throttle, yaw) = intentionCourante()
        val raison = noyau.soumettreIntention(pitch, roll, throttle, yaw, e.capteurs)
        derniereRaisonNoyau = raison

        // INVARIANT CENTRAL : si le noyau bloque, on NE PROGRESSE PAS.
        if (raison != RaisonBlocage.AUCUNE) {
            return etat   // drone gelé (0,0,0,0 déjà émis par le noyau), état figé
        }

        // noyau OK -> progresser selon l'état
        when (etat) {
            EtatV2.ARME -> progresserArme(e)
            EtatV2.MONTEE_PALIER -> progresserMontee(e)
            EtatV2.STATIONNAIRE -> progresserStationnaire(e)
            EtatV2.BALAYAGE_360 -> progresserBalayage(e)
            EtatV2.EVALUATION_OUVERTURE -> progresserEvaluation(e)
            else -> {}
        }
        return etat
    }

    // intention de mouvement selon l'état (ce que la V2 VEUT faire)
    private data class Intention(val pitch: Double, val roll: Double, val throttle: Double, val yaw: Double)

    private fun intentionCourante(): Intention = when (etat) {
        EtatV2.MONTEE_PALIER -> Intention(0.0, 0.0, cfg.vitesseMonteeMs, 0.0)   // monter
        EtatV2.BALAYAGE_360 -> Intention(0.0, 0.0, 0.0, cfg.vitesseYawDegs)     // tourner
        else -> Intention(0.0, 0.0, 0.0, 0.0)                                  // stationnaire
    }

    // ------------------------------------------------------------------
    //  Progression par état (appelée seulement si noyau == AUCUNE)
    // ------------------------------------------------------------------
    private fun progresserArme(e: EntreeCycle) {
        if (e.batteriePct < cfg.batterieMinPct) {
            log("batterie insuffisante a l'armement -> arret plafond (pilote juge)")
            etat = EtatV2.ARRET_PLAFOND
            return
        }
        altitudeCibleM = e.altitudeM + cfg.pasPalierM
        log("arme OK -> montee vers ${"%.1f".format(altitudeCibleM)} m")
        etat = EtatV2.MONTEE_PALIER
    }

    private fun progresserMontee(e: EntreeCycle) {
        if (e.altitudeM >= altitudeCibleM) {
            nbPaliers += 1
            tStationnaire = 0.0
            log("palier $nbPaliers atteint a ${"%.1f".format(e.altitudeM)} m -> stationnaire")
            etat = EtatV2.STATIONNAIRE
        }
    }

    private fun progresserStationnaire(e: EntreeCycle) {
        tStationnaire += dt
        if (tStationnaire >= cfg.dureeStationnaireS) {
            capBalayageCumule = 0.0
            capPrecedent = e.capDeg
            log("stabilise -> balayage 360")
            etat = EtatV2.BALAYAGE_360
        }
    }

    private fun progresserBalayage(e: EntreeCycle) {
        capPrecedent?.let { prec ->
            var delta = (e.capDeg - prec) % 360.0
            if (delta < 0) delta += 360.0
            // Protection ticks anormaux : un delta > 180° en un seul cycle signale
            // soit un tick trop espacé (gel), soit une ambiguïté de sens de rotation.
            // On l'ignore plutôt que de cumuler une valeur fausse (prudent : on
            // préfère un balayage un peu plus long qu'un tour compté à tort).
            if (delta <= 180.0) {
                capBalayageCumule += delta
            }
        }
        capPrecedent = e.capDeg
        if (capBalayageCumule >= 360.0) {
            log("tour complet (${"%.0f".format(capBalayageCumule)} deg) -> evaluation")
            etat = EtatV2.EVALUATION_OUVERTURE
        }
    }

    private fun progresserEvaluation(e: EntreeCycle) {
        val (score, confiance) = scoreFn()
        dernierScore = score
        derniereConfiance = confiance
        log("score=${"%.1f".format(score)} confiance=${"%.1f".format(confiance)}")

        // score acceptable ET confiance suffisante -> proposer (verrou confiance)
        if (score >= cfg.seuilScore && confiance >= cfg.seuilConfiance) {
            log("ouverture suffisante et fiable -> PROPOSER_DEPART")
            etat = EtatV2.PROPOSER_DEPART
            return
        }
        // sinon monter encore : vérifier les 2 verrous + batterie
        if (!peutMonterEncore(e)) {
            log("plafond/paliers/batterie atteint sans ouverture -> ARRET_PLAFOND")
            etat = EtatV2.ARRET_PLAFOND
            return
        }
        altitudeCibleM = e.altitudeM + cfg.pasPalierM
        log("score insuffisant -> nouveau palier vers ${"%.1f".format(altitudeCibleM)} m")
        etat = EtatV2.MONTEE_PALIER
    }

    /** Deux verrous indépendants (§3.4) + batterie. */
    private fun peutMonterEncore(e: EntreeCycle): Boolean {
        if (e.altitudeM + cfg.pasPalierM > cfg.altitudeMaxM) return false   // verrou 1
        if (nbPaliers >= cfg.paliersMax) return false                       // verrou 2
        if (e.plafondCapteurM < cfg.plafondCapteurMinM) return false        // verrou 3 : obstacle au-dessus
        if (e.batteriePct < cfg.batterieMinPct) return false                // batterie
        return true
    }

    // ------------------------------------------------------------------
    //  Attente décision pilote (départ JAMAIS automatique, §3.5)
    // ------------------------------------------------------------------
    private fun gererAttentePilote(e: EntreeCycle): EtatV2 {
        // Pendant l'attente, le drone reste stationnaire : on soumet quand même une
        // intention nulle (le noyau garde le dernier mot sur la sécurité).
        noyau.soumettreIntention(0.0, 0.0, 0.0, 0.0, e.capteurs)
        if (e.piloteAccepte) {
            log("pilote valide -> TERMINE (mission peut commencer)")
            etat = EtatV2.TERMINE
        } else if (e.piloteAnnule) {
            log("pilote annule")
            etat = EtatV2.TERMINE
        }
        return etat
    }
}

