package ca.cineflight.stage.test

/**
 * TestStatsVol.kt
 * -----------------------------------------------------------------------------
 * Sonde les 3 cles statistiques cumulatives du controleur de vol via MSDK 5.18 :
 *   - KeyAircraftTotalFlightTimes     -> nombre de vols       (Integer)
 *   - KeyAircraftTotalFlightDuration  -> duree totale         (Integer, secondes)   [depuis 5.5.0]
 *   - KeyAircraftTotalFlightDistance  -> distance totale      (Integer, metres)
 *
 * OBJECTIF : determiner empiriquement si le Mini 3 / Mini 4 Pro renvoie une vraie
 * valeur ou null / KeyNotSupported, puisque la doc DJI ne confirme ces cles que
 * sur enterprise (M300 / Mavic 2 Enterprise Advanced).
 *
 * Distinguer :
 *   - VALEUR (ex: 0)   -> cle supportee ; 0 = drone neuf jamais vole.
 *   - null             -> cle existe mais non remplie sur ce modele.
 *   - ABSENT (onFailure) -> cle NON supportee sur ce drone (!= 0 vol).
 *
 * SORTIE : AlertDialog plein texte (lisible, reste affiche jusqu'a OK), au lieu
 * d'un Toast tronque. Logs conserves en doublon.
 *
 * USAGE : TestStatsVol.lancer(activity) une fois le drone CONNECTE.
 *   -> IMPORTANT : passer une Activity (pas applicationContext) car un dialog
 *      a besoin d'un contexte d'UI a fenetre. Voir surcharge de compat ci-dessous.
 * -----------------------------------------------------------------------------
 */

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import java.util.concurrent.atomic.AtomicInteger

object TestStatsVol {

    private const val TAG = "CF_TestStatsVol"
    private val ui = Handler(Looper.getMainLooper())

    private data class Resultat(
        var cle: String,
        var statut: Statut = Statut.EN_ATTENTE,
        var valeur: Int? = null,
        var erreur: String? = null
    )

    private enum class Statut { EN_ATTENTE, VALEUR, NULL, NON_SUPPORTE }

    /**
     * Lancer le test. [activity] sert a afficher l'AlertDialog (contexte UI).
     * Si tu n'as qu'un Context applicatif, utilise lancer(context) plus bas
     * (repli sur Toast, moins lisible).
     */
    fun lancer(activity: Activity) {
        executer(activity, activity)
    }

    /** Surcharge de repli : sans Activity, on ne peut afficher qu'un Toast. */
    fun lancer(context: Context) {
        executer(context, null)
    }

    private fun executer(context: Context, activity: Activity?) {
        Log.i(TAG, "=== DEBUT TEST STATS VOL (MSDK 5.18) ===")

        val km = KeyManager.getInstance()
        if (km == null) {
            Log.e(TAG, "KeyManager null -> SDK non initialise / drone non connecte. ABANDON.")
            afficher(context, activity, "ERREUR", "Drone non connecte (KeyManager null).")
            return
        }

        val resTimes = Resultat("Nb vols")
        val resDuration = Resultat("Duree (s)")
        val resDistance = Resultat("Distance (m)")
        val restantes = AtomicInteger(3)

        fun terminerSi() {
            if (restantes.decrementAndGet() == 0) {
                verdict(context, activity, listOf(resTimes, resDuration, resDistance))
            }
        }

        sonder(km, KeyTools.createKey(FlightControllerKey.KeyAircraftTotalFlightTimes), resTimes) { terminerSi() }
        sonder(km, KeyTools.createKey(FlightControllerKey.KeyAircraftTotalFlightDuration), resDuration) { terminerSi() }
        sonder(km, KeyTools.createKey(FlightControllerKey.KeyAircraftTotalFlightDistance), resDistance) { terminerSi() }
    }

    private fun <T> sonder(
        km: KeyManager,
        key: dji.sdk.keyvalue.key.DJIKey<T>,
        res: Resultat,
        onDone: () -> Unit
    ) {
        km.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T?) {
                val v = (value as? Number)?.toInt()
                if (v != null) {
                    res.statut = Statut.VALEUR
                    res.valeur = v
                    Log.i(TAG, "[${res.cle}] VALEUR = $v")
                } else {
                    res.statut = Statut.NULL
                    Log.w(TAG, "[${res.cle}] NULL")
                }
                onDone()
            }

            override fun onFailure(error: IDJIError) {
                res.statut = Statut.NON_SUPPORTE
                res.erreur = "${error.errorCode()} / ${error.description()}"
                Log.e(TAG, "[${res.cle}] ABSENT -> ${res.erreur}")
                onDone()
            }
        })
    }

    private fun verdict(context: Context, activity: Activity?, resultats: List<Resultat>) {
        val sb = StringBuilder()
        resultats.forEach { r ->
            val ligne = when (r.statut) {
                Statut.VALEUR -> "${r.valeur}"
                Statut.NULL -> "null (non remplie)"
                Statut.NON_SUPPORTE -> "ABSENT - non supportee\n   (${r.erreur})"
                Statut.EN_ATTENTE -> "pas de retour"
            }
            sb.append("• ${r.cle} : $ligne\n\n")
            Log.i(TAG, "  ${r.cle} -> $ligne")
        }

        val auMoinsUneValeur = resultats.any { it.statut == Statut.VALEUR }
        if (auMoinsUneValeur) {
            sb.append("=> Stats de vol EXPOSEES sur ce drone.\n(0 = drone neuf, jamais vole)")
            Log.i(TAG, "RESULTAT : stats DJI exposees.")
        } else {
            sb.append("=> AUCUNE stat de vol exposee\n(cles non supportees sur ce modele,\nINDEPENDANT du nombre de vols)\n=> fallback compteur local active.")
            Log.w(TAG, "RESULTAT : aucune stat DJI -> fallback (SondeVolLocale).")
            SondeVolLocale.activer(context)
        }

        afficher(context, activity, "STATS VOL", sb.toString())
        Log.i(TAG, "=== FIN TEST STATS VOL ===")
    }

    /** Dialog si Activity dispo (lisible), sinon repli Toast. */
    private fun afficher(context: Context, activity: Activity?, titre: String, message: String) {
        ui.post {
            if (activity != null && !activity.isFinishing) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(titre)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                Toast.makeText(context.applicationContext, "$titre\n$message", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * -----------------------------------------------------------------------------
 * SondeVolLocale : fallback si les cles DJI ne renvoient rien.
 * Compte les vols / accumule la duree via transitions de vol (KeyIsFlying).
 *
 * ANTI-DUPLICATION : si PontDjiReelCockpit ecoute DEJA KeyIsFlying, NE PAS
 * ajouter ce listen -> brancher le comptage dans le listener existant.
 * Le TODO PersistanceStats reste a cabler sur MissionCapture (SQLite).
 * -----------------------------------------------------------------------------
 */
object SondeVolLocale {

    private const val TAG = "CF_SondeVolLocale"
    private var actif = false
    private var enVol = false
    private var nbVolsSession = 0
    private var debutVolMs = 0L
    private var dureeCumuleeMs = 0L

    fun activer(context: Context) {
        if (actif) { Log.w(TAG, "Deja actif."); return }
        actif = true
        Log.i(TAG, "Fallback compteur local ACTIF (ecoute KeyIsFlying).")

        val km = KeyManager.getInstance() ?: run {
            Log.e(TAG, "KeyManager null -> impossible d'ecouter isFlying.")
            return
        }
        val keyIsFlying = KeyTools.createKey(FlightControllerKey.KeyIsFlying)
        km.listen(keyIsFlying, this) { _, nouvelEtat ->
            val vol = (nouvelEtat as? Boolean) ?: return@listen
            onTransitionVol(vol)
        }
    }

    private fun onTransitionVol(vol: Boolean) {
        if (vol && !enVol) {
            enVol = true
            debutVolMs = System.currentTimeMillis()
            nbVolsSession += 1
            Log.i(TAG, "DECOLLAGE -> vol #$nbVolsSession (session)")
        } else if (!vol && enVol) {
            enVol = false
            val dureeVolMs = System.currentTimeMillis() - debutVolMs
            dureeCumuleeMs += dureeVolMs
            Log.i(TAG, "ATTERRISSAGE -> ${dureeVolMs / 1000}s ; cumul ${dureeCumuleeMs / 1000}s")
            // TODO PersistanceStats.incrementer(nbVols=1, dureeSec=dureeVolMs/1000) sur MissionCapture.
        }
    }

    fun desactiver() {
        if (!actif) return
        KeyManager.getInstance()?.cancelListen(this)
        actif = false
        Log.i(TAG, "Fallback DESACTIVE.")
    }
}

