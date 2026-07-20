package ca.cineflight.stage.control

import android.util.Log
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * GestionnaireModeVol — ARBITRE d'exclusivite entre les deux modes de pilotage.
 * -----------------------------------------------------------------------------
 * PROBLEME RESOLU :
 *   Deux sous-systemes peuvent commander le drone, et ils ne doivent JAMAIS
 *   etre actifs en meme temps :
 *     - VIRTUAL_STICK : PiloteDrone envoie des vitesses a 15 Hz (suivi YOLO,
 *       evitement reactif, pilotage manuel cockpit).
 *     - WAYPOINTS     : ExecuteurMissionWpml execute une mission KMZ/WPML
 *       native (trajectoire autonome dans le controleur de vol).
 *
 *   Si les deux tournent ensemble -> le drone recoit des commandes VS pendant
 *   qu'il suit une mission -> comportement indefini. DJI exige l'exclusivite.
 *
 * PRINCIPE :
 *   Une seule source de verite pour le mode courant (AtomicReference).
 *   Toute transition passe par ici. Avant d'activer un mode, on coupe l'autre
 *   PROPREMENT et on attend la confirmation (callback) avant de demarrer.
 *
 * NON-DUPLICATION (regle projet) :
 *   - N'introduit AUCUNE nouvelle ecoute SDK ni boucle. Il ORCHESTRE les API
 *     publiques existantes : PiloteDrone.demarrer/arreter, ExecuteurMissionWpml
 *     .executer/interrompre. PontDji reste le seul a toucher le SDK.
 *   - Ne reimplemente pas le Virtual Stick ni les waypoints : il les sequence.
 *
 * USAGE (dans MainActivity, une instance unique apres construction du pilote) :
 *   val modeVol = GestionnaireModeVol(pilote, scope = lifecycleScope)
 *   // pilotage temps reel (suivi, manuel) :
 *   modeVol.activerVirtualStick { ok -> ... }
 *   // mission planifiee :
 *   modeVol.lancerMission(kmz, onProgres = {...}) { ok, msg -> ... }
 *   // retour pilotage apres mission / interruption :
 *   modeVol.activerVirtualStick { ok -> ... }
 *   // arret d'urgence (depuis n'importe quel mode) :
 *   modeVol.arretUrgence()
 * -----------------------------------------------------------------------------
 */
class GestionnaireModeVol(
    private val ctx: Context,
    private val pilote: PiloteDrone,
    private val scope: CoroutineScope
) {

    enum class Mode { AUCUN, VIRTUAL_STICK, WAYPOINTS, TRANSITION }

    private val TAG = "GestionnaireModeVol"

    // Source de verite unique du mode courant. TRANSITION = bascule en cours,
    // bloque toute nouvelle demande tant qu'elle n'est pas terminee.
    private val modeRef = AtomicReference(Mode.AUCUN)

    val mode: Mode get() = modeRef.get()

    /**
     * Bascule vers le VIRTUAL STICK (pilotage temps reel).
     * Si une mission waypoint est en cours, elle est interrompue d'abord.
     * @param onPret (true) une fois le Virtual Stick actif ; (false) si refuse
     *               (transition deja en cours) ou echec d'interruption mission.
     */
    fun activerVirtualStick(onPret: (Boolean) -> Unit) {
        // Deja en Virtual Stick : rien a faire.
        if (modeRef.get() == Mode.VIRTUAL_STICK) { onPret(true); return }
        // Verrou : on n'accepte la transition que depuis un etat stable.
        if (!verrouiller()) {
            Log.w(TAG, "activerVirtualStick refuse : transition deja en cours")
            onPret(false); return
        }

        val precedent = modeAvantTransition
        if (precedent == Mode.WAYPOINTS) {
            // Couper la mission AVANT de rendre la main au Virtual Stick.
            Log.i(TAG, "Interruption mission waypoint avant Virtual Stick")
            ExecuteurMissionWpml.interrompre { ok, msg ->
                if (!ok) {
                    // L'interruption a echoue : on NE demarre PAS le VS (securite).
                    Log.e(TAG, "Echec interruption mission : $msg -> VS non demarre")
                    deverrouiller(Mode.WAYPOINTS)   // on reste en waypoints
                    onPret(false)
                } else {
                    demarrerVirtualStick(onPret)
                }
            }
        } else {
            // Depuis AUCUN : demarrage direct.
            demarrerVirtualStick(onPret)
        }
    }

    private fun demarrerVirtualStick(onPret: (Boolean) -> Unit) {
        // PiloteDrone.demarrer() active le mode VS (pont.activerVirtualStick(true))
        // et lance la boucle 15 Hz. Idempotent cote pilote (return si deja actif).
        pilote.demarrer(scope)
        deverrouiller(Mode.VIRTUAL_STICK)
        Log.i(TAG, "Mode = VIRTUAL_STICK")
        onPret(true)
    }

    /**
     * Bascule vers les WAYPOINTS (mission autonome).
     * Coupe d'abord le Virtual Stick (boucle 15 Hz + mode VS) pour liberer le
     * controleur de vol, PUIS uploade et lance la mission.
     * @param kmz       fichier KMZ/WPML genere par le serveur CineFlight.
     * @param onProgres (waypoint courant) pendant le vol.
     * @param onTermine (succes, message) a la fin / echec / interruption.
     */
    fun lancerMission(
        kmz: File,
        onProgres: (Int) -> Unit,
        onTermine: (Boolean, String) -> Unit
    ) {
        if (!verrouiller()) {
            Log.w(TAG, "lancerMission refuse : transition deja en cours")
            onTermine(false, "Transition deja en cours."); return
        }

        // Toute la bascule vers WAYPOINTS se fait dans le scope : on ATTEND l'arret
        // effectif du Virtual Stick (cancelAndJoin) avant de rendre le FC a la
        // mission, pour qu'aucun ancien tick VS ne survive pendant que les waypoints
        // prennent le controle. arreterEtAttendre() etant suspend, la suite (upload
        // + lancement) s'enchaine seulement une fois le VS reellement stoppe.
        scope.launch {
            if (modeAvantTransition == Mode.VIRTUAL_STICK) {
                Log.i(TAG, "Arret synchrone du Virtual Stick avant mission waypoint")
                pilote.arreterEtAttendre()
            }
            // ExecuteurMissionWpml gere son propre cycle d'etat ; on enrobe onTermine
            // pour repasser en AUCUN a la fin (le pilote reprend via activerVirtualStick()).
            Log.i(TAG, "Mode = WAYPOINTS (upload + lancement)")
            deverrouiller(Mode.WAYPOINTS)
            ExecuteurMissionWpml.executer(
                ctx,
                kmz = kmz,
                onProgres = onProgres,
                onTermine = { ok, msg ->
                    // Fin de mission (FINISHED / INTERRUPTED / ERREUR) : plus aucun
                    // mode pilote actif. Le drone hover (waypoint) ; l'appelant
                    // decide de reprendre en VS ou de lancer un RTH / atterrissage.
                    modeRef.set(Mode.AUCUN)
                    Log.i(TAG, "Mission terminee ($ok) : $msg -> Mode = AUCUN")
                    onTermine(ok, msg)
                }
            )
        }
    }

    /**
     * ARRET D'URGENCE — coupe TOUT, quel que soit le mode courant.
     *   - Virtual Stick : hover immediat + sortie VS (PiloteDrone.arretUrgence()).
     *   - Waypoints     : interruption de la mission (le drone hover).
     * Le drone reste en l'air, pilotable a la telecommande. NE coupe PAS les moteurs.
     */
    fun arretUrgence() {
        Log.w(TAG, "ARRET D'URGENCE (mode courant = ${modeRef.get()})")
        // On force la sortie VS dans tous les cas (sans effet si deja off).
        try { pilote.arretUrgence() } catch (e: Throwable) { Log.e(TAG, "arretUrgence pilote: ${e.message}") }
        // Si une mission tournait, on l'interrompt aussi.
        if (ExecuteurMissionWpml.phase == ExecuteurMissionWpml.Phase.EN_VOL ||
            ExecuteurMissionWpml.phase == ExecuteurMissionWpml.Phase.UPLOAD) {
            ExecuteurMissionWpml.interrompre { ok, msg ->
                Log.w(TAG, "Interruption mission (urgence) : $ok / $msg")
            }
        }
        modeRef.set(Mode.AUCUN)
    }

    // --- Verrou de transition -------------------------------------------------
    // modeAvantTransition memorise l'etat reel juste avant de passer en TRANSITION,
    // pour savoir quoi couper. verrouiller() echoue si une transition est deja
    // en cours (empeche les bascules concurrentes).
    @Volatile private var modeAvantTransition: Mode = Mode.AUCUN

    private fun verrouiller(): Boolean {
        val actuel = modeRef.get()
        if (actuel == Mode.TRANSITION) return false
        modeAvantTransition = actuel
        return modeRef.compareAndSet(actuel, Mode.TRANSITION)
    }

    private fun deverrouiller(nouveau: Mode) {
        modeRef.set(nouveau)
    }
}

