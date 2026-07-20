package ca.cineflight.stage.cine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PONT entre le moteur cine (Grammaire/Catalogue/Validateur) et les mouvements REELS de l'app.
 *
 * Il ne reimplemente AUCUN mouvement : il pilote l'app existante exactement comme le ferait
 * un appui successif sur les boutons. Pour chaque pas d'une Sequence validee, il :
 *   1. pose le plan (cibleHPlan)  -> via Commandes.appliquerPlan
 *   2. pose le mouvement (mouvementActuel) -> via Commandes.appliquerMouvement
 *   3. attend la duree du pas
 *   4. passe au pas suivant
 *
 * MainActivity fournit l'implementation de Commandes (acces a ses variables/fonctions).
 * Ainsi le moteur reste decouple de l'app, et l'app garde TOUTE sa couche actuelle intacte.
 */
interface CommandesVol {
    /** Applique un plan/cadrage (equivaut a appuyer sur Gros/Americain/Pied/Ensemble). */
    fun appliquerPlan(cibleHPlan: Float)
    /** Applique un mouvement (equivaut a appuyer sur Statique/Orbite/.../Suivi). code = mouvementActuel. */
    fun appliquerMouvement(code: Int)
    /** S'assure que le suivi auto est actif sur la cible verrouillee (basculerMode(true)). */
    fun assurerModeAuto()
    /** Retour a l'etat neutre en fin de sequence (Statique), sans couper le suivi. */
    fun terminer()
    /** Affiche un court message a l'utilisateur (bandeau ephemere existant). */
    fun bandeau(message: String)
    /** RALENTI : regle le multiplicateur de vitesse cine du PAS courant (<=1 = plus lent).
     *  Defaut neutre : une implementation qui ne gere pas le ralenti l'ignore. */
    fun reglerVitesseCine(facteur: Float) {}
}

/**
 * Execute une Sequence validee, pas a pas, en respectant les durees.
 * Annulable : conserver le Job retourne et appeler annuler().
 */
class ExecuteurSequence(
    private val scope: CoroutineScope,
    private val commandes: CommandesVol
) {
    private var job: Job? = null

    val enCours: Boolean get() = job?.isActive == true

    /** Etat d'avancement pousse a l'UI pendant l'execution. */
    data class Progression(
        val etape: Int,           // index du pas, base 1
        val total: Int,           // nombre de pas
        val mouvementNom: String, // ex "Orbite"
        val resteEtapeS: Int,     // secondes restantes sur le pas courant
        val resteTotalS: Int      // secondes restantes sur toute la sequence
    )

    /**
     * Lance l'execution.
     * onProgres(p) est appele regulierement (~4 Hz) pour rafraichir l'indicateur.
     * onFini() est appele a la fin normale (pas en cas d'annulation).
     */
    fun lancer(
        sequence: Sequence,
        onProgres: (Progression) -> Unit = {},
        onFini: () -> Unit = {}
    ) {
        annuler() // une seule sequence a la fois
        job = scope.launch {
            commandes.assurerModeAuto()
            val total = sequence.pas.size
            val dureeTotale = sequence.dureeTotaleS
            var ecouleAvant = 0   // secondes ecoulees avant le pas courant
            for ((i, pas) in sequence.pas.withIndex()) {
                if (!isActive) return@launch
                commandes.reglerVitesseCine(pas.facteurVitesse)
                commandes.appliquerPlan(pas.plan.cibleHPlan)
                commandes.appliquerMouvement(pas.mouvement.code)
                commandes.bandeau("${i + 1}/$total · ${pas.mouvement.nomFr}")
                // attente de la duree du pas, en increments fins pour rester reactif + rafraichir l'UI
                var restantMs = pas.dureeS * 1000L
                while (restantMs > 0 && isActive) {
                    val resteEtapeS = ((restantMs + 999L) / 1000L).toInt()
                    val ecouleTotal = ecouleAvant + (pas.dureeS - resteEtapeS)
                    onProgres(Progression(
                        etape = i + 1, total = total,
                        mouvementNom = pas.mouvement.nomFr,
                        resteEtapeS = resteEtapeS,
                        resteTotalS = (dureeTotale - ecouleTotal).coerceAtLeast(0)
                    ))
                    val tranche = if (restantMs > 250L) 250L else restantMs
                    delay(tranche)
                    restantMs -= tranche
                }
                ecouleAvant += pas.dureeS
            }
            if (isActive) {
                commandes.terminer()
                onFini()
            }
        }
    }

    /** Arrete l'execution en cours PROPREMENT (n'est PAS un arret d'urgence).
     *  Annule la sequence et remet le drone en etat neutre via commandes.terminer()
     *  (Statique), sans couper le mode suivi ni le Virtual Stick. */
    fun annuler() {
        job?.cancel()
        job = null
        try { commandes.terminer() } catch (_: Exception) {}
    }
}

/**
 * Orchestrateur de haut niveau : du choix utilisateur jusqu'a l'execution.
 * Relie Catalogue -> Grammaire -> Validateur -> ExecuteurSequence.
 *
 * Retourne le Verdict pour que l'UI affiche la pastille (🟢/🟡/🔴) AVANT de lancer.
 * L'execution n'est lancee que si l'utilisateur confirme (cf. lancerVerdict).
 */
class AssistantRealisateur(
    private val scope: CoroutineScope,
    private val commandes: CommandesVol,
    /** Reglages utilisateur (duree, vitesse). null = valeurs de base de la grammaire. */
    private val reglages: ReglagesCine? = null
) {
    private val executeur = ExecuteurSequence(scope, commandes)

    val executionEnCours: Boolean get() = executeur.enCours

    /**
     * Prepare un plan a partir d'une recette + sujet (si recette-effet) + contexte reel.
     * Ne lance RIEN : renvoie le verdict (avec sa pastille) pour confirmation utilisateur.
     */
    fun preparer(
        recette: Recette,
        sujetChoisi: Scene?,
        contexte: ContexteValidation
    ): Verdict {
        val brute = Catalogue.versSequence(recette, sujetChoisi, reglages)
        return Validateur.valider(brute, contexte, alternative = Catalogue.VALEUR_SURE)
    }

    /**
     * Lance l'execution d'un verdict validable (Valide ou Adapte).
     * Pour un Refuse, l'UI doit proposer l'alternative (ne pas appeler ici).
     * onProgres pousse l'avancement (~4 Hz) pour l'indicateur d'execution.
     */
    fun lancerVerdict(
        verdict: Verdict,
        onProgres: (ExecuteurSequence.Progression) -> Unit = {},
        onFini: () -> Unit = {}
    ): Boolean {
        val seq = when (verdict) {
            is Verdict.Valide -> verdict.sequence
            is Verdict.Adapte -> verdict.sequence
            is Verdict.Refuse -> return false
        }
        executeur.lancer(seq, onProgres, onFini)
        return true
    }

    fun annuler() = executeur.annuler()
}

