package ca.cineflight.stage

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.cineflight.stage.control.PontDjiReel
import ca.cineflight.stage.control.ObstacleGateWiring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PHASE 2a — TEST VIRTUAL STICK AU SOL (aucun moteur arme).
 *
 * Valide que la chaine de commande fonctionne : on active VirtualStick et on
 * envoie des commandes A ZERO (pitch=roll=yaw=throttle=0). Le drone NE DECOLLE
 * PAS, aucun moteur n'est arme — on verifie seulement que le SDK accepte
 * VirtualStick et les commandes (retours dans Logcat tag "PontDjiReel").
 *
 * SECURITE : helices retirees recommandees. AUCUN decollage ici. Le bouton
 * ARRET coupe VirtualStick immediatement.
 *
 * Ce qu'on observe (Logcat filtre "PontDjiReel" + ecran) :
 *   - "Virtual Stick active (mode avance)"  -> activation OK
 *   - "Echec activation Virtual Stick: ..."  -> le SDK refuse (raison affichee)
 * Une fois 2a validee, on passera a 2b (decollage reel basse altitude, dehors).
 */
class Phase2Activity : AppCompatActivity() {

    private val pont = PontDjiReel(ObstacleGateWiring.Off)
    private var vsActif = false
    private var envoiActif = false

    private lateinit var voyant: TextView
    private lateinit var txtConnexion: TextView
    private lateinit var txtVs: TextView
    private lateinit var txtEnvoi: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1117.toInt())
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        racine.addView(TextView(this).apply {
            text = getString(R.string.p2_titre)
            setTextColor(Color.WHITE); textSize = 18f
        })
        racine.addView(TextView(this).apply {
            text = getString(R.string.p2_avert)
            setTextColor(0xFFFFB74D.toInt()); textSize = 12f
            setPadding(0, dp(4), 0, dp(16))
        })

        voyant = TextView(this).apply {
            text = getString(R.string.p2_init)
            setTextColor(Color.WHITE); textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setBackgroundColor(0xFF7A5900.toInt())
        }
        racine.addView(voyant, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun ligne(): TextView = TextView(this).apply {
            setTextColor(0xFFE6EDF3.toInt()); textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        }
        txtConnexion = ligne(); txtVs = ligne(); txtEnvoi = ligne()
        val carte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFF161B22.toInt())
        }
        for (t in listOf(txtConnexion, txtVs, txtEnvoi)) carte.addView(t)
        val lpc = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpc.topMargin = dp(16)
        racine.addView(carte, lpc)

        // boutons de test
        fun bouton(txt: String, couleur: Int, action: () -> Unit) = Button(this).apply {
            text = txt; isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(couleur)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) }
            setOnClickListener { action() }
        }

        racine.addView(bouton(getString(R.string.p2_btn1), 0xFF1565C0.toInt()) {
            pont.activerVirtualStick(true); vsActif = true
            txtVs.text = getString(R.string.p2_vs_demande)
        })
        racine.addView(bouton(getString(R.string.p2_btn2), 0xFF2E7D32.toInt()) {
            envoiActif = true
            txtEnvoi.text = getString(R.string.p2_envoi_encours)
        })
        racine.addView(bouton(getString(R.string.p2_btn3), 0xFF455A64.toInt()) {
            envoiActif = false
            txtEnvoi.text = getString(R.string.p2_envoi_arrete)
        })
        racine.addView(bouton(getString(R.string.p2_btn_arret), 0xFFC62828.toInt()) {
            envoiActif = false
            pont.activerVirtualStick(false); vsActif = false
            txtVs.text = getString(R.string.p2_vs_desactive)
            txtEnvoi.text = getString(R.string.p2_envoi_arrete)
        })

        // Passage a la Phase 2b : test d'axes EN VOL (apres validation de 2a)
        racine.addView(bouton(getString(R.string.p2_btn_2b), 0xFF6A1B9A.toInt()) {
            // securite : couper VS local avant d'ouvrir l'ecran de vol
            envoiActif = false
            if (vsActif) { try { pont.activerVirtualStick(false) } catch (_: Exception) {}; vsActif = false }
            startActivity(android.content.Intent(this@Phase2Activity, Phase2bActivity::class.java))
        })

        // Passage a la Phase 3 : SUIVI RTK de l'auto EN VOL (apres validation de 2b)
        racine.addView(bouton(getString(R.string.p2_btn_3), 0xFF00695C.toInt()) {
            // securite : couper VS local avant d'ouvrir l'ecran de vol
            envoiActif = false
            if (vsActif) { try { pont.activerVirtualStick(false) } catch (_: Exception) {}; vsActif = false }
            startActivity(android.content.Intent(this@Phase2Activity, Phase3Activity::class.java))
        })

        setContentView(ScrollView(this).apply { addView(racine) })

        // SDK (idempotent) + listeners
        EnregistrementSdk.enregistrer(applicationContext) { ok, _ ->
            runOnUiThread { if (ok) try { pont.initialiserListeners() } catch (_: Exception) {} }
        }

        // boucle : affichage etat + envoi des commandes a zero si demande
        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (envoiActif && vsActif) {
                    // commande A ZERO : aucun mouvement. Teste juste l'acceptation SDK.
                    try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.TEST) } catch (_: Exception) {}
                }
                delay(66)   // ~15 Hz
            }
        }
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) { rafraichir(); delay(500) }
        }
    }

    private fun rafraichir() {
        val connecte = try { pont.estConnecte() } catch (_: Throwable) { false }
        val modele = try { pont.modeleDrone() } catch (_: Throwable) { "?" }
        txtConnexion.text = if (connecte) getString(R.string.p2_connecte, modele) else getString(R.string.p2_non_connecte)
        when {
            !connecte -> { voyant.text = getString(R.string.p2_voyant_deco); voyant.setBackgroundColor(0xFF8B1A1A.toInt()) }
            vsActif && envoiActif -> { voyant.text = getString(R.string.p2_voyant_actif_envoi); voyant.setBackgroundColor(0xFF1B5E20.toInt()) }
            vsActif -> { voyant.text = getString(R.string.p2_voyant_actif); voyant.setBackgroundColor(0xFF7A5900.toInt()) }
            else -> { voyant.text = getString(R.string.p2_voyant_pret); voyant.setBackgroundColor(0xFF7A5900.toInt()) }
        }
    }

    override fun onPause() {
        super.onPause()
        // securite : on coupe tout si l'ecran passe en arriere-plan
        envoiActif = false
        if (vsActif) { try { pont.activerVirtualStick(false) } catch (_: Exception) {} ; vsActif = false }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

