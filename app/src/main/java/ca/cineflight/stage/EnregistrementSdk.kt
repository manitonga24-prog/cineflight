package ca.cineflight.stage

import android.content.Context
import android.util.Log
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EnregistrementSdk — enregistre l'app aupres du DJI Mobile SDK v5.
 *
 * PREREQUIS :
 *  - App Key DJI declaree dans AndroidManifest.xml (com.dji.sdk.API_KEY),
 *    liee au package name ca.cineflight.stage.
 *  - StageApplication appelle Helper.install() dans attachBaseContext()
 *    (sinon les libs natives ne sont pas installees et init() echoue).
 *  - Connexion Internet au PREMIER lancement (le SDK valide la cle aupres
 *    des serveurs DJI).
 *
 * SEQUENCE MSDK v5 (cf. doc officielle) :
 *  1. SDKManager.getInstance().init(context, callback)
 *  2. attendre onInitProcess(INITIALIZE_COMPLETE)
 *  3. appeler SDKManager.getInstance().registerApp()
 *  4. resultat dans onRegisterSuccess() / onRegisterFailure()
 *
 * IMPORTANT : le SDK garde une reference FORTE au callback. On utilise donc un
 * 'object' (singleton, cycle de vie = appli) et JAMAIS une Activity/Fragment,
 * pour eviter les fuites memoire.
 *
 * -- TELEMETRIE / TIMING DES LISTENERS ---------------------------------------
 * En MSDK v5, les cles (KeyManager.listen) abonnees AVANT que l'appareil soit
 * connecte ne se redeclenchent PAS automatiquement a la connexion : la
 * telemetrie (batterie, GPS, altitude...) reste alors vide meme si la video
 * passe. La solution est d'abonner / reabonner les listeners APRES la connexion
 * du produit. On expose donc [onConnexionProduit], rappele a chaque
 * onProductConnect / onProductChanged avec connecte=true, et a chaque
 * onProductDisconnect avec connecte=false. MainActivity s'en sert pour
 * (re)brancher les listeners au bon moment.
 */
object EnregistrementSdk {

    private const val TAG = "EnregistrementSdk"

    // garantit que le callback de l'UI (onResultat) n'est appele qu'UNE seule fois,
    // meme si le SDK renvoie plusieurs evenements.
    private val resultatEnvoye = AtomicBoolean(false)
    private var initAppele = false

    /**
     * Callback de connexion produit (drone). Rappele sur le thread du SDK :
     *  - connecte = true  -> drone connecte (ou reconnecte / change)
     *  - connecte = false -> drone deconnecte
     * MainActivity y branche le (re)abonnement des listeners de telemetrie et
     * repasse elle-meme sur le thread UI si besoin.
     */
    @Volatile
    var onConnexionProduit: ((connecte: Boolean) -> Unit)? = null

    /** Vrai si le SDK considere actuellement un produit comme connecte. */
    @Volatile
    var produitConnecte: Boolean = false
        private set

    /**
     * Demarre l'enregistrement du SDK. [onResultat] est rappele une seule fois :
     *  - (true, "ok")             si l'enregistrement reussit
     *  - (false, description)     si l'init ou l'enregistrement echoue
     *
     * @param context idealement applicationContext (cycle de vie long).
     */
    fun enregistrer(context: Context, onResultat: (Boolean, String) -> Unit) {
        if (initAppele) {
            // deja initialise : si on est deja enregistre, on le signale directement.
            if (SDKManager.getInstance().isRegistered) onResultat(true, "deja enregistre")
            return
        }
        initAppele = true
        resultatEnvoye.set(false)

        SDKManager.getInstance().init(context, object : SDKManagerCallback {

            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                Log.i(TAG, "onInitProcess: $event ($totalProcess)")
                // l'enregistrement ne doit etre lance qu'une fois l'init terminee.
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    Log.i(TAG, "init complete -> registerApp()")
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onRegisterSuccess() {
                Log.i(TAG, "onRegisterSuccess")
                envoyer(onResultat, true, "ok")
            }

            override fun onRegisterFailure(error: IDJIError) {
                val desc = error.description() ?: "erreur inconnue"
                Log.e(TAG, "onRegisterFailure: $desc")
                envoyer(onResultat, false, desc)
            }

            override fun onProductConnect(productId: Int) {
                Log.i(TAG, "onProductConnect: $productId")
                produitConnecte = true
                onConnexionProduit?.invoke(true)
            }

            override fun onProductDisconnect(productId: Int) {
                Log.i(TAG, "onProductDisconnect: $productId")
                produitConnecte = false
                onConnexionProduit?.invoke(false)
            }

            override fun onProductChanged(productId: Int) {
                Log.i(TAG, "onProductChanged: $productId")
                // un changement de produit est traite comme une (re)connexion :
                // on rebranche les listeners.
                produitConnecte = true
                onConnexionProduit?.invoke(true)
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                if (total > 0) Log.i(TAG, "DB download: $current/$total")
            }
        })
    }

    private fun envoyer(cb: (Boolean, String) -> Unit, ok: Boolean, msg: String) {
        // n'appelle le callback de l'UI qu'une seule fois.
        if (resultatEnvoye.compareAndSet(false, true)) cb(ok, msg)
    }
}

