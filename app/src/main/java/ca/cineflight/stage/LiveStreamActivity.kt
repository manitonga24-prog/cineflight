package ca.cineflight.stage

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.cineflight.stage.streaming.DjiLiveStreamEngine
import ca.cineflight.stage.streaming.LiveStreamMetrics
import ca.cineflight.stage.streaming.LiveStreamState
import ca.cineflight.stage.streaming.StreamQualityChoisie
import ca.cineflight.stage.streaming.TestDebitConnexion
import ca.cineflight.stage.streaming.YouTubeLiveService
import ca.cineflight.stage.streaming.YouTubeStreamSecretStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * LiveStreamActivity — ecran minimal du prototype de diffusion (V1 YouTube).
 *
 * Chaine : Activity -> YouTubeLiveService -> DjiLiveStreamEngine -> SDK DJI -> YouTube.
 * L'Activity n'a AUCUNE reference au manager DJI ; elle passe par le service.
 *
 * Cycle de vie (regle verbatim) : on N'ARRETE PAS le direct dans onStop(). L'ecran peut
 * etre masque, tourne, ou le pilote peut revenir a Phase3 pendant que le direct continue.
 * L'arret provient UNIQUEMENT du bouton Arreter. Le moteur reste la source de verite.
 *
 * SECURITE : la cle saisie est enregistree chiffree puis le champ est vide ; elle n'est
 * JAMAIS reaffichee. Rien de sensible (cle, URL complete) n'est journalise ici.
 *
 * NB collecte : on suit la convention du projet (lifecycleScope.launch { flow.collect })
 * plutot que repeatOnLifecycle, pour ne pas dependre de lifecycle-runtime-ktx.
 */
class LiveStreamActivity : AppCompatActivity() {

    // Moteur + service crees pour la duree de l'Activity (prototype). Le moteur pourrait
    // etre eleve au niveau applicatif plus tard pour survivre a l'ecran.
    private val service: YouTubeLiveService by lazy {
        YouTubeLiveService(
            engine = DjiLiveStreamEngine(),
            secretStore = YouTubeStreamSecretStore(applicationContext),
        )
    }

    private lateinit var champUrlServeur: TextInputEditText
    private lateinit var champCle: TextInputEditText
    private lateinit var etatCle: TextView
    private lateinit var txtEtat: TextView
    private lateinit var txtResolution: TextView
    private lateinit var txtFps: TextView
    private lateinit var txtDebit: TextView
    private lateinit var btnDemarrer: MaterialButton
    private lateinit var btnArreter: MaterialButton
    private lateinit var groupeResolution: MaterialButtonToggleGroup
    private lateinit var groupeDebit: MaterialButtonToggleGroup
    private lateinit var btnTesterConnexion: MaterialButton
    private lateinit var txtResultatTest: TextView
    private lateinit var groupeDestination: MaterialButtonToggleGroup
    private lateinit var blocNomDest: com.google.android.material.textfield.TextInputLayout
    private lateinit var champNomDest: TextInputEditText

    /** URL RTMP par defaut de YouTube.
     *  IMPORTANT : on utilise rtmp:// (port 1935), PAS rtmps:// (TLS/443). Les drones
     *  DJI echouent au handshake TLS avec rtmps ("TLS_Connect failed"), la session
     *  s'ouvre mais aucune image ne part (fps/debit 0). rtmp:// simple fonctionne. */
    private val URL_YOUTUBE = "rtmp://a.rtmp.youtube.com/live2"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_stream)

        champUrlServeur = findViewById(R.id.champUrlServeur)
        champCle = findViewById(R.id.champCle)
        etatCle = findViewById(R.id.etatCle)
        txtEtat = findViewById(R.id.txtEtat)
        txtResolution = findViewById(R.id.txtResolution)
        txtFps = findViewById(R.id.txtFps)
        txtDebit = findViewById(R.id.txtDebit)
        btnDemarrer = findViewById(R.id.btnDemarrer)
        btnArreter = findViewById(R.id.btnArreter)
        groupeResolution = findViewById(R.id.groupeResolution)
        groupeDebit = findViewById(R.id.groupeDebit)
        btnTesterConnexion = findViewById(R.id.btnTesterConnexion)
        txtResultatTest = findViewById(R.id.txtResultatTest)
        groupeDestination = findViewById(R.id.groupeDestination)
        blocNomDest = findViewById(R.id.blocNomDest)
        champNomDest = findViewById(R.id.champNomDest)

        findViewById<MaterialButton>(R.id.btnEnregistrerCle).setOnClickListener { enregistrerCle() }
        findViewById<MaterialButton>(R.id.btnSupprimerCle).setOnClickListener { supprimerCle() }
        btnDemarrer.setOnClickListener { demarrer() }
        btnArreter.setOnClickListener { arreter() }
        btnTesterConnexion.setOnClickListener { testerConnexion() }
        // Changement de destination : ajuste l'UI + pre-remplit l'URL.
        groupeDestination.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) appliquerDestination(checkedId == R.id.btnDestRegie)
        }

        chargerDestination()   // restaure YouTube/Regie memorise (fait avant chargerUrlServeur)
        chargerUrlServeur()
        chargerQualite()
        rafraichirEtatCle()
        observerMoteur()
        // Recupere AUTO la cle du compte web (aucun geste requis). Le direct se lance
        // ensuite par le bouton "Demarrer le direct" (pas d'auto-start : il laissait
        // le bouton grise si le drone tardait a confirmer).
        synchroniserCleDepuisCompte()
    }

    /**
     * SYNCHRONISATION AUTOMATIQUE de la cle de diffusion depuis le compte web.
     *
     * La cle est saisie UNE FOIS sur cineflight.ca (Parametres) puis stockee cote serveur.
     * A l'ouverture de cet ecran, l'app la recupere (canal authentifie JWT) et l'enregistre
     * localement, sans que l'utilisateur ait a copier-coller quoi que ce soit sur le
     * telephone. Silencieux et non bloquant : en cas d'echec (non connecte / pas de cle /
     * reseau), on garde simplement la cle deja presente sur l'appareil (le cas echeant).
     *
     * SECURITE : la cle n'est jamais journalisee ni affichee ; seul l'etat "enregistree" l'est.
     */
    private fun synchroniserCleDepuisCompte() {
        // Recupere du compte web : cle YouTube ET/OU URL Regie. On applique selon la
        // destination courante (aucun geste requis sur le telephone dans les deux cas).
        lifecycleScope.launch {
            val res = ca.cineflight.stage.cine.CineAuth.recupererCleStream(applicationContext)
            if (res != null) {
                // 1) Cle YouTube : toujours memorisee localement (utile des qu'on repasse en YouTube).
                if (res.cle.isNotBlank()) {
                    service.enregistrerCle(res.cle)     // stockage chiffre local (comme la saisie)
                }
                // 2) URL Regie : memorisee dans PREF_URL_REGIE (comme la saisie manuelle regie).
                res.regieUrl?.takeIf { it.isNotBlank() }?.let { regie ->
                    memoriserUrlRegie(regie)
                }

                if (destinationRegie()) {
                    // Destination active = Regie : on pre-remplit l'URL regie du compte.
                    res.regieUrl?.takeIf { it.isNotBlank() }?.let { regie ->
                        champUrlServeur.setText(regie)
                        info("URL Regie recuperee depuis votre compte CineFlight.")
                    }
                } else {
                    // Destination active = YouTube : URL serveur du compte (sinon defaut conserve).
                    // On corrige un eventuel rtmps:// YouTube (TLS incompatible drone) en rtmp://.
                    corrigerUrlYoutube(res.serveurUrl)?.let { serveur ->
                        champUrlServeur.setText(serveur)
                        memoriserUrlServeur(serveur)
                    }
                    if (res.cle.isNotBlank()) {
                        info("Cle de diffusion recuperee depuis votre compte CineFlight.")
                    }
                }
                rafraichirEtatCle()
            }
            // Aucun message si echec : l'ecran reste utilisable avec les valeurs locales eventuelles.
        }
    }

    // --- Destination : YouTube ou Regie/distributeur ---

    /** Applique le choix de destination : ajuste l'UI + pre-remplit l'URL adaptee. */
    private fun appliquerDestination(regie: Boolean) {
        blocNomDest.visibility = if (regie) android.view.View.VISIBLE else android.view.View.GONE
        val prefs = getSharedPreferences("cineflight", MODE_PRIVATE)
        if (regie) {
            // URL memorisee de la regie, sinon champ vide (a saisir par l'operateur).
            champUrlServeur.setText(prefs.getString(PREF_URL_REGIE, "") ?: "")
            champNomDest.setText(prefs.getString(PREF_NOM_REGIE, "") ?: "")
        } else {
            // YouTube : URL memorisee (si l'operateur l'a changee) sinon defaut.
            champUrlServeur.setText(prefs.getString(PREF_URL_SERVEUR, URL_YOUTUBE) ?: URL_YOUTUBE)
        }
        prefs.edit().putBoolean(PREF_DEST_REGIE, regie).apply()
    }

    /** Restaure la destination memorisee (defaut YouTube). */
    private fun chargerDestination() {
        val regie = getSharedPreferences("cineflight", MODE_PRIVATE).getBoolean(PREF_DEST_REGIE, false)
        groupeDestination.check(if (regie) R.id.btnDestRegie else R.id.btnDestYoutube)
        appliquerDestination(regie)
    }

    /** Vrai si la destination courante est la regie. */
    private fun destinationRegie(): Boolean = groupeDestination.checkedButtonId == R.id.btnDestRegie

    // --- URL serveur memorisee (non sensible : simples SharedPreferences) ---

    /**
     * Migre une ancienne URL YouTube en rtmps:// (TLS, incompatible drone DJI) vers
     * l'URL rtmp:// simple qui fonctionne. Ne touche PAS les URL regie ni les URL
     * deja correctes. Corrige l'ancienne valeur memorisee OU recue du compte.
     */
    private fun corrigerUrlYoutube(url: String?): String? {
        if (url == null) return null
        val u = url.trim()
        // Cible uniquement les URL YouTube en rtmps (le probleme TLS_Connect).
        return if (u.startsWith("rtmps://") && u.contains("youtube")) {
            URL_YOUTUBE   // rtmp://a.rtmp.youtube.com/live2
        } else u
    }

    private fun chargerUrlServeur() {
        // En mode regie, l'URL est deja pre-remplie par appliquerDestination : ne pas ecraser.
        if (destinationRegie()) return
        val enregistree = corrigerUrlYoutube(
            getSharedPreferences("cineflight", MODE_PRIVATE).getString(PREF_URL_SERVEUR, null)
        )
        if (!enregistree.isNullOrBlank()) {
            champUrlServeur.setText(enregistree)
            memoriserUrlServeur(enregistree)   // persiste la version corrigee
        }
        // Sinon on garde la valeur par defaut prereplie dans le layout.
    }

    private fun memoriserUrlServeur(serveur: String) {
        getSharedPreferences("cineflight", MODE_PRIVATE)
            .edit()
            .putString(PREF_URL_SERVEUR, serveur.trim())
            .apply()
    }

    private fun memoriserUrlRegie(url: String) {
        getSharedPreferences("cineflight", MODE_PRIVATE)
            .edit()
            .putString(PREF_URL_REGIE, url.trim())
            .apply()
    }

    // --- Cle ---

    private fun enregistrerCle() {
        val cle = champCle.text?.toString().orEmpty().trim()
        if (cle.isBlank()) {
            info("Saisis d'abord une cle de diffusion.")
            return
        }
        // 1) Stockage local (chiffre) : le direct peut demarrer immediatement.
        service.enregistrerCle(cle)
        champCle.text = null                 // SECURITE : on ne garde pas la cle a l'ecran
        rafraichirEtatCle()
        // 2) Envoi vers le compte CineFlight (POST /api/stream_key) pour que le web,
        //    la page de partage et les autres appareils la connaissent aussi. Non
        //    bloquant : si non connecte ou reseau absent, la cle reste utilisable en local.
        lifecycleScope.launch {
            val pousse = ca.cineflight.stage.cine.CineAuth.envoyerCleStream(applicationContext, cle)
            info(
                if (pousse) "Cle enregistree sur cet appareil et synchronisee sur ton compte CineFlight."
                else "Cle enregistree sur cet appareil. (Non synchronisee sur le compte : connecte-toi pour la partager avec le web.)"
            )
        }
    }

    private fun supprimerCle() {
        service.supprimerCle()
        champCle.text = null
        rafraichirEtatCle()
        info("Cle supprimee.")
    }

    private fun rafraichirEtatCle() {
        etatCle.text = if (service.cleEnregistree())
            "Cle enregistree sur cet appareil"
        else
            "Aucune cle enregistree"
    }

    // --- Qualite (resolution + debit), memorisee comme l'URL ---

    /** Construit le choix de qualite a partir de l'etat courant des deux toggle groups. */
    private fun lireQualite(): StreamQualityChoisie {
        val resolution = when (groupeResolution.checkedButtonId) {
            R.id.btnRes720 -> StreamQualityChoisie.Resolution.P720
            else -> StreamQualityChoisie.Resolution.P1080   // 1080p par defaut
        }
        val bitrateBps = when (groupeDebit.checkedButtonId) {
            R.id.btnDebit2 -> 2_000_000
            R.id.btnDebit4 -> 4_000_000
            R.id.btnDebit6 -> 6_000_000
            else -> null   // Auto : le SDK gere le debit
        }
        return StreamQualityChoisie(resolution = resolution, bitrateBps = bitrateBps)
    }

    private fun chargerQualite() {
        val prefs = getSharedPreferences("cineflight", MODE_PRIVATE)
        // Resolution memorisee (defaut 1080p).
        val res720 = prefs.getString(PREF_RESOLUTION, "1080") == "720"
        groupeResolution.check(if (res720) R.id.btnRes720 else R.id.btnRes1080)
        // Debit memorise (defaut Auto).
        when (prefs.getInt(PREF_DEBIT_KBPS, 0)) {
            2000 -> groupeDebit.check(R.id.btnDebit2)
            4000 -> groupeDebit.check(R.id.btnDebit4)
            6000 -> groupeDebit.check(R.id.btnDebit6)
            else -> groupeDebit.check(R.id.btnDebitAuto)
        }
    }

    private fun memoriserQualite(quality: StreamQualityChoisie) {
        getSharedPreferences("cineflight", MODE_PRIVATE).edit()
            .putString(PREF_RESOLUTION, if (quality.resolution == StreamQualityChoisie.Resolution.P720) "720" else "1080")
            .putInt(PREF_DEBIT_KBPS, quality.bitrateBps?.div(1000) ?: 0)
            .apply()
    }

    // --- Test de connexion (peut-elle soutenir la qualite choisie ?) ---

    private fun testerConnexion() {
        val quality = lireQualite()
        btnTesterConnexion.isEnabled = false
        txtResultatTest.text = "Test en cours…"
        txtResultatTest.setTextColor(0xFF9E9E9E.toInt())
        lifecycleScope.launch {
            val resultat = TestDebitConnexion.mesurer(quality)
            afficherResultatTest(resultat)
            btnTesterConnexion.isEnabled = true
        }
    }

    private fun afficherResultatTest(resultat: TestDebitConnexion.Resultat) {
        when (resultat) {
            is TestDebitConnexion.Resultat.Mesure -> {
                val mesureMbps = "%.1f".format(resultat.debitMesureBps / 1_000_000.0)
                val besoinMbps = "%.1f".format(resultat.besoinBps / 1_000_000.0)
                if (resultat.suffisant) {
                    txtResultatTest.text =
                        "✓ Connexion suffisante : $mesureMbps Mbps montant (besoin ~$besoinMbps Mbps)"
                    txtResultatTest.setTextColor(0xFF2E7D32.toInt())   // vert
                } else {
                    txtResultatTest.text =
                        "⚠ Connexion juste/insuffisante : $mesureMbps Mbps montant (besoin ~$besoinMbps Mbps). Baisse la resolution ou le debit."
                    txtResultatTest.setTextColor(0xFFC62828.toInt())   // rouge
                }
            }
            is TestDebitConnexion.Resultat.Indetermine -> {
                txtResultatTest.text = "Test impossible (${resultat.raison}). Verifie ta connexion."
                txtResultatTest.setTextColor(0xFFF9A825.toInt())       // ambre
            }
        }
    }

    // --- Direct ---

    private fun demarrer() {
        val serveur = champUrlServeur.text?.toString().orEmpty()
        val quality = lireQualite()
        when (val r = service.demarrer(serveur, quality)) {
            is YouTubeLiveService.Demarrage.Lance -> {
                // Memorise selon la destination : regie (URL + nom) ou YouTube (URL).
                val prefs = getSharedPreferences("cineflight", MODE_PRIVATE).edit()
                if (destinationRegie()) {
                    prefs.putString(PREF_URL_REGIE, serveur.trim())
                    prefs.putString(PREF_NOM_REGIE, champNomDest.text?.toString()?.trim() ?: "")
                } else {
                    prefs.putString(PREF_URL_SERVEUR, serveur.trim())
                }
                prefs.apply()
                memoriserQualite(quality)      // idem pour la qualite retenue
                /* l'etat s'affiche via le flux */
            }
            is YouTubeLiveService.Demarrage.CleAbsente ->
                info("Cle YouTube a saisir de nouveau.")
            is YouTubeLiveService.Demarrage.ServeurInvalide ->
                info(r.raison)
        }
    }

    /**
     * Arrete le direct : coupe le flux DJI ET, en mode YouTube, termine le broadcast cote
     * serveur (stop_live). Sans cela, la page publique /live/{user} continuerait d'afficher
     * l'ancienne video une fois le direct fini (« Video non disponible »). En mode Regie,
     * il n'y a pas de broadcast YouTube a terminer : on se contente d'arreter le flux.
     */
    private fun arreter() {
        service.arreter()   // coupe le flux DJI (bouton reste reactif via le flux d'etat)
        if (!destinationRegie()) {
            // Non bloquant : si non connecte / reseau absent, l'arret DJI a deja eu lieu.
            lifecycleScope.launch {
                ca.cineflight.stage.cine.CineAuth.arreterLiveYoutube(applicationContext)
            }
        }
    }

    // --- Observation du moteur (etat + metriques) ---

    private fun observerMoteur() {
        lifecycleScope.launch {
            service.state.collect { etat -> afficherEtat(etat) }
        }
        lifecycleScope.launch {
            service.metrics.collect { m -> afficherMetriques(m) }
        }
    }

    private fun afficherEtat(etat: LiveStreamState) {
        val libelle = when (etat) {
            LiveStreamState.Idle -> "Arrete"
            LiveStreamState.Starting -> "Demarrage…"
            LiveStreamState.Streaming -> "EN DIRECT"
            LiveStreamState.Stopping -> "Arret…"
            is LiveStreamState.Erreur -> "Echec"
        }
        txtEtat.text = "Etat : $libelle"

        // En cas d'echec, on AFFICHE la raison reelle du SDK (au lieu de rester muet)
        // et on montre une seule fois un dialogue explicite.
        if (etat is LiveStreamState.Erreur) {
            txtResultatTest.text = "⚠ ${etat.raison}"
            txtResultatTest.setTextColor(0xFFC62828.toInt())
            if (etat.raison != derniereErreurAffichee) {
                derniereErreurAffichee = etat.raison
                info("Le direct n'a pas pu demarrer.\n\nRaison : ${etat.raison}")
            }
        } else {
            derniereErreurAffichee = null
        }

        // Idle et Erreur autorisent un (re)demarrage ; les autres etats l'interdisent.
        val enCours = etat is LiveStreamState.Starting ||
            etat is LiveStreamState.Streaming ||
            etat is LiveStreamState.Stopping
        btnDemarrer.isEnabled = !enCours
        btnArreter.isEnabled = etat is LiveStreamState.Streaming || etat is LiveStreamState.Starting
    }

    // Evite de re-afficher le meme dialogue d'erreur a chaque emission du flux.
    private var derniereErreurAffichee: String? = null

    private fun afficherMetriques(m: LiveStreamMetrics) {
        txtResolution.text = "Resolution : ${m.resolution ?: "—"}"
        txtFps.text = "FPS : ${m.fps?.toString() ?: "—"}"
        txtDebit.text = m.bitrateBps
            ?.let { "Debit : ${it / 1000} kb/s" }
            ?: "Debit : —"
    }

    private fun info(message: String) {
        MaterialAlertDialogBuilder(this, R.style.DialogCineFlight)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private companion object {
        const val PREF_URL_SERVEUR = "live_url_serveur"
        const val PREF_RESOLUTION = "live_resolution"   // "1080" | "720"
        const val PREF_DEBIT_KBPS = "live_debit_kbps"    // 0 = Auto, sinon 2000/4000/6000
        const val PREF_DEST_REGIE = "live_dest_regie"    // true = regie, false = YouTube
        const val PREF_URL_REGIE = "live_url_regie"      // URL RTMPS de la regie
        const val PREF_NOM_REGIE = "live_nom_regie"      // nom de la destination regie
    }
}
