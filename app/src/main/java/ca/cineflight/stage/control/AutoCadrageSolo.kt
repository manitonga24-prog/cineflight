package ca.cineflight.stage.control

import android.content.Context
import ca.cineflight.stage.R

/**
 * AutoCadrageSolo — sequence AUTONOME de decollage + montee + recherche/cadrage du sujet,
 * pensee pour filmer SEUL (l'operateur est souvent le sujet devant la camera).
 *
 * Le drone se place et vous cadre tout seul, MAIS il ne lance JAMAIS de mouvement
 * cinematique (orbite, travelling, revelation...). Le "Lancer le plan" reste une action
 * MANUELLE : c'est le choix de securite central.
 *
 * Machine a etats PURE et testable : elle ne touche pas au SDK DJI. Le pilotage reel est
 * realise par un ActionsAuto (fourni par MainActivity). Elle est pilotee par des "tick"
 * periodiques qui lui donnent l'altitude et l'etat du sujet ; elle decide les transitions
 * et declenche les INTENTIONS (decoller, monter, maintenir, cadrer, arreter).
 *
 * Regle maitresse : sujet perdu => on ARRETE le cadrage, on MAINTIENT la position (hover),
 * on attend. JAMAIS on ne "continue" un mouvement.
 */
enum class EtatAuto {
    PRET,                 // au sol, pret a demarrer
    COMPTE_A_REBOURS,     // 3-2-1 annulable, avant decollage
    MONTEE,               // decollage effectue + montee vers l'altitude de tournage
    RECHERCHE_SUJET,      // stabilise en l'air, cherche une personne (sans se deplacer)
    SUJET_TROUVE,         // transitoire : une personne vient d'etre detectee
    CADRAGE,              // centre / cadre le sujet
    ATTENTE_CONFIRMATION, // cadre et stable : attend le "Lancer le plan" MANUEL
    SUJET_PERDU,          // sujet perdu : cadrage stoppe, maintien en position, attente
    ANNULE,               // annule par l'utilisateur
    ARRET                 // arret d'urgence
}

/** Intentions realisees par la couche vol (MainActivity). Aucune logique de decision ici. */
interface ActionsAuto {
    /** Decollage reel (DJI startTakeoff). onFini(true) si le drone est en l'air. */
    fun decoller(onFini: (Boolean) -> Unit)
    /** Commence la montee vers l'altitude cible (AGL, metres). Appelee une fois a l'entree. */
    fun commencerMontee(altitudeCibleM: Double)
    /** Immobilise le drone (hover) : vx=vy=vz=0, aucun suivi actif. */
    fun maintenirPosition()
    /** Active la recherche + le cadrage automatique du sujet (suivi YOLO). */
    fun activerCadrage()
    /** Arret : hover immediat + sortie du mode auto (reprise manuelle a la telecommande). */
    fun arreterTout()
    /** Notifie l'UI d'un changement d'etat (+ message lisible pour le pilote). */
    fun onEtat(etat: EtatAuto, message: String)
}

/**
 * @param altitudeCibleM  altitude de tournage visee (AGL).
 * @param compteAReboursS duree du compte a rebours avant decollage.
 * @param toleranceAltM   marge acceptee sous l'altitude cible pour considerer "arrive".
 * @param stabilisationMs hover minimal apres la montee avant de chercher le sujet.
 * @param cadrageStableMs duree de cadrage stable avant de passer en attente de confirmation.
 * @param perteAvantHoldMs duree de perte du sujet avant de basculer en SUJET_PERDU.
 * @param monteeTimeoutMs securite : si l'altitude n'est pas atteinte, on cesse de monter.
 */
class AutoCadrageSolo(
    private val ctx: Context,
    private val actions: ActionsAuto,
    private val altitudeCibleM: Double = 9.0,
    private val compteAReboursS: Int = 3,
    private val toleranceAltM: Double = 0.8,
    private val stabilisationMs: Long = 1500,
    private val cadrageStableMs: Long = 1500,
    private val perteAvantHoldMs: Long = 800,
    private val monteeTimeoutMs: Long = 20000
) {
    var etat: EtatAuto = EtatAuto.PRET
        private set

    private var minuterie: Long = 0        // ms dans l'etat courant (stabilisation / cadrage / rebours)
    private var chronoMontee: Long = 0     // ms totales de montee (securite anti-blocage)
    private var perte: Long = 0            // ms depuis la derniere detection du sujet
    private var decollageEnCours = false

    /** Vrai UNIQUEMENT quand le drone est cadre et attend le lancement MANUEL du plan. */
    val pretAConfirmer: Boolean get() = etat == EtatAuto.ATTENTE_CONFIRMATION

    /** Vrai tant que la sequence est active (ni au repos, ni terminee). */
    val enCours: Boolean get() = etat != EtatAuto.PRET && etat != EtatAuto.ANNULE && etat != EtatAuto.ARRET

    private fun aller(nouvel: EtatAuto, message: String) {
        if (etat == nouvel) return
        etat = nouvel
        minuterie = 0
        actions.onEtat(nouvel, message)
    }

    /** Remet la machine au repos (reutilisable pour une nouvelle sequence). */
    fun reset() {
        etat = EtatAuto.PRET; minuterie = 0; chronoMontee = 0; perte = 0; decollageEnCours = false
    }

    /** Demarre la sequence (depuis PRET). Ne decolle pas tout de suite : compte a rebours d'abord. */
    fun demarrer() {
        if (etat != EtatAuto.PRET) return
        perte = 0; chronoMontee = 0; decollageEnCours = false
        aller(EtatAuto.COMPTE_A_REBOURS, ctx.getString(R.string.aut_decollage_rebours, compteAReboursS))
    }

    /** Annulation utilisateur : au sol -> simple annulation ; en vol -> hover + reprise manuelle. */
    fun annuler() {
        if (etat == EtatAuto.ANNULE || etat == EtatAuto.ARRET) return
        val enVol = etat != EtatAuto.PRET && etat != EtatAuto.COMPTE_A_REBOURS
        if (enVol) actions.maintenirPosition()
        aller(EtatAuto.ANNULE,
            if (enVol) ctx.getString(R.string.aut_annule_vol) else ctx.getString(R.string.aut_annule))
    }

    /** Arret d'urgence : hover immediat + sortie du mode auto. Toujours prioritaire. */
    fun arret() {
        actions.arreterTout()
        aller(EtatAuto.ARRET, ctx.getString(R.string.aut_arret))
    }

    /**
     * Tick periodique (ex ~4 Hz). Fournit l'etat reel mesure :
     * @param dtMs         temps ecoule depuis le dernier tick.
     * @param altitudeAglM altitude au-dessus du decollage (NaN si inconnue).
     * @param sujetPresent une personne est detectee avec assez de confiance.
     * @param cadrageOk    le sujet est centre / a la bonne taille (cadrage stable).
     */
    fun tick(dtMs: Long, altitudeAglM: Double, sujetPresent: Boolean, cadrageOk: Boolean) {
        minuterie += dtMs
        if (sujetPresent) perte = 0 else perte += dtMs

        when (etat) {
            EtatAuto.COMPTE_A_REBOURS -> {
                val reste = compteAReboursS - (minuterie / 1000L).toInt()
                if (reste > 0) {
                    actions.onEtat(EtatAuto.COMPTE_A_REBOURS, "Décollage dans ${reste}…")
                } else if (!decollageEnCours) {
                    decollageEnCours = true
                    actions.decoller { ok ->
                        if (ok) {
                            chronoMontee = 0
                            aller(EtatAuto.MONTEE, ctx.getString(R.string.aut_montee, altitudeCibleM.toInt()))
                            actions.commencerMontee(altitudeCibleM)
                        } else {
                            arret()
                        }
                    }
                }
            }
            EtatAuto.MONTEE -> {
                chronoMontee += dtMs
                val arrive = !altitudeAglM.isNaN() && altitudeAglM >= altitudeCibleM - toleranceAltM
                val abandonMontee = chronoMontee >= monteeTimeoutMs
                if (arrive || abandonMontee) {
                    actions.maintenirPosition()      // stabilisation avant de chercher
                    if (minuterie >= stabilisationMs) {
                        aller(EtatAuto.RECHERCHE_SUJET,
                            if (arrive) ctx.getString(R.string.aut_recherche_ok) else ctx.getString(R.string.aut_recherche_lim))
                        actions.activerCadrage()
                    }
                } else {
                    minuterie = 0                    // pas encore arrive : le chrono de stabilisation ne court pas
                }
            }
            EtatAuto.RECHERCHE_SUJET -> {
                if (sujetPresent) aller(EtatAuto.SUJET_TROUVE, ctx.getString(R.string.aut_trouve))
                else actions.maintenirPosition()     // cherche SANS se deplacer
            }
            EtatAuto.SUJET_TROUVE -> {
                aller(EtatAuto.CADRAGE, ctx.getString(R.string.aut_cadrage))   // transitoire (feedback UI)
            }
            EtatAuto.CADRAGE -> {
                when {
                    perte >= perteAvantHoldMs -> { aller(EtatAuto.SUJET_PERDU, ctx.getString(R.string.aut_perdu)); actions.maintenirPosition() }
                    cadrageOk && minuterie >= cadrageStableMs -> aller(EtatAuto.ATTENTE_CONFIRMATION, ctx.getString(R.string.aut_pret))
                    !cadrageOk -> minuterie = 0        // cadrage pas encore stable : on recommence le chrono
                }
            }
            EtatAuto.ATTENTE_CONFIRMATION -> {
                // On tient le cadrage et on ATTEND le lancement MANUEL. Aucune action auto.
                if (perte >= perteAvantHoldMs) { aller(EtatAuto.SUJET_PERDU, ctx.getString(R.string.aut_perdu)); actions.maintenirPosition() }
            }
            EtatAuto.SUJET_PERDU -> {
                // REGLE MAITRESSE : on maintient, on attend, on ne continue JAMAIS.
                if (sujetPresent) { aller(EtatAuto.CADRAGE, ctx.getString(R.string.aut_retrouve)); actions.activerCadrage() }
                else actions.maintenirPosition()
            }
            EtatAuto.PRET, EtatAuto.ANNULE, EtatAuto.ARRET -> { /* etats de repos : rien */ }
        }
    }
}
