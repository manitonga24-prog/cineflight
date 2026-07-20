package ca.cineflight.stage

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.cineflight.stage.control.PontDjiReel
import ca.cineflight.stage.control.ObstacleGateWiring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PHASE 2b — TEST D'AXES EN VOL REEL (basse altitude).
 *
 * Valide la convention de pilotage : le drone decolle a ~1,5 m, puis on teste
 * UN axe a la fois avec une commande douce (0,5 m/s pendant 1 s, puis hover).
 *
 * ⚠ SECURITE — a faire OBLIGATOIREMENT :
 *   - grand espace DEGAGE, personne autour, aucun obstacle
 *   - drone A VUE en permanence
 *   - helices remises, batterie chargee, telecommande DJI a portee
 *   - bouton ARRET D'URGENCE rouge toujours accessible
 *
 * Protocole (observer le drone pour chaque bouton) :
 *   - "Avant"     -> le drone doit avancer vers son NEZ. S'il derive de cote,
 *                    basculer INVERSER_ROLL_PITCH dans PontDji.
 *   - "Droite"    -> a sa droite ; "Gauche" -> a sa gauche
 *   - "Monter"    -> il monte ; "Descendre" -> il descend
 *   - "Tourner"   -> il pivote sur place
 * Le decollage est BLOQUE tant que : connecte + GPS valide + batterie > 40 %.
 */
class Phase2bActivity : AppCompatActivity() {

    private val pont = PontDjiReel(ObstacleGateWiring.Off)
    @Volatile private var enVol = false
    @Volatile private var vsActif = false
    // commande de test courante (envoyee en continu a 15 Hz tant que != null)
    @Volatile private var cmdTest: FloatArray? = null   // [pitch, roll, throttle, yaw]
    private var jobTest: Job? = null

    private lateinit var voyant: TextView
    private lateinit var txtEtat: TextView

    private val VITESSE_TEST = 0.5f       // m/s (doux)
    private val DUREE_TEST_MS = 1000L     // 1 s par appui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1117.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        racine.addView(TextView(this).apply {
            text = getString(R.string.p2b_titre)
            setTextColor(Color.WHITE); textSize = 18f
        })
        racine.addView(TextView(this).apply {
            text = getString(R.string.p2b_avert)
            setTextColor(0xFFFF8A65.toInt()); textSize = 12f
            setPadding(0, dp(4), 0, dp(14))
        })

        voyant = TextView(this).apply {
            text = getString(R.string.p2b_verif)
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setBackgroundColor(0xFF7A5900.toInt())
        }
        racine.addView(voyant, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        txtEtat = TextView(this).apply {
            setTextColor(0xFFE6EDF3.toInt()); textSize = 14f
            setPadding(0, dp(10), 0, dp(10))
        }
        racine.addView(txtEtat)

        fun bouton(txt: String, couleur: Int, action: () -> Unit) = Button(this).apply {
            text = txt; isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(couleur)
            setOnClickListener { action() }
        }

        // DECOLLER / ATTERRIR
        racine.addView(bouton(getString(R.string.p2b_decoller_btn), 0xFF1565C0.toInt()) { confirmerVol(getString(R.string.p2b_decoller_titre), getString(R.string.p2b_decoller_msg), getString(R.string.p2b_decoller_oui), false) { decoller() } }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) }
        })
        racine.addView(bouton(getString(R.string.p2b_atterrir_btn), 0xFF455A64.toInt()) { confirmerVol(getString(R.string.p2b_atterrir_titre), getString(R.string.p2b_atterrir_msg), getString(R.string.p2b_atterrir_oui), false) { atterrir() } }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
        })

        // Grille des tests d'axes (un axe a la fois)
        racine.addView(TextView(this).apply {
            text = getString(R.string.p2b_test_axes); textSize = 12f
            setTextColor(0xFF90A4AE.toInt()); setPadding(dp(2), dp(16), 0, dp(6))
        })
        val grille = GridLayout(this).apply { columnCount = 2 }
        fun caseTest(txt: String, p: Float, r: Float, t: Float, y: Float) {
            grille.addView(bouton(txt, 0xFF2E7D32.toInt()) { testAxe(p, r, t, y) }.apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = (resources.displayMetrics.widthPixels - dp(16) * 2 - dp(12)) / 2
                    height = dp(52); setMargins(dp(3), dp(3), dp(3), dp(3))
                }
            })
        }
        caseTest(getString(R.string.p2b_avant), VITESSE_TEST, 0f, 0f, 0f)
        caseTest(getString(R.string.p2b_arriere), -VITESSE_TEST, 0f, 0f, 0f)
        caseTest(getString(R.string.p2b_droite), 0f, VITESSE_TEST, 0f, 0f)
        caseTest(getString(R.string.p2b_gauche), 0f, -VITESSE_TEST, 0f, 0f)
        caseTest(getString(R.string.p2b_monter), 0f, 0f, VITESSE_TEST, 0f)
        caseTest(getString(R.string.p2b_descendre), 0f, 0f, -VITESSE_TEST, 0f)
        caseTest(getString(R.string.p2b_tourner_d), 0f, 0f, 0f, 20f)
        caseTest(getString(R.string.p2b_tourner_g), 0f, 0f, 0f, -20f)
        racine.addView(grille)

        // ARRET D'URGENCE
        racine.addView(bouton(getString(R.string.p2b_arret_btn), 0xFFC62828.toInt()) { confirmerVol(getString(R.string.p2b_arret_titre), getString(R.string.p2b_arret_msg), getString(R.string.p2b_arret_oui), true) { arretUrgence() } }.apply {
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(18) }
        })
        racine.addView(TextView(this).apply {
            text = getString(R.string.p2b_arret_note)
            textSize = 11f; setTextColor(0xFF90A4AE.toInt()); setPadding(dp(2), dp(6), dp(2), 0)
        })

        setContentView(ScrollView(this).apply { addView(racine) })

        EnregistrementSdk.enregistrer(applicationContext) { ok, _ ->
            runOnUiThread { if (ok) try { pont.initialiserListeners() } catch (_: Exception) {} }
        }

        // boucle d'envoi 15 Hz : envoie la commande de test courante (ou hover si null) tant qu'on vole
        jobTest = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (vsActif) {
                    val c = cmdTest
                    if (c != null) {
                        try { pont.envoyerVitesses(c[0], c[1], c[2], c[3], ca.cineflight.stage.control.CommandOrigin.TEST) } catch (_: Exception) {}
                    } else if (enVol) {
                        try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.TEST) } catch (_: Exception) {}  // hover
                    }
                }
                delay(66)
            }
        }
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) { rafraichir(); delay(500) }
        }
    }

    private fun pretAVoler(): Boolean {
        val c = try { pont.estConnecte() } catch (_: Throwable) { false }
        val g = try { pont.gpsValide() } catch (_: Throwable) { false }
        val b = try { pont.batteriePourcent() } catch (_: Throwable) { -1 }
        return c && g && b > 40
    }

    /** Confirmation avant une commande de vol (bouton rouge si "danger"). */
    private fun confirmerVol(titre: String, message: String, labelOui: String, danger: Boolean, onOui: () -> Unit) {
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(titre)
            .setMessage(message)
            .setNegativeButton(getString(R.string.p2b_annuler), null)
            .setPositiveButton(labelOui) { _, _ -> onOui() }
            .show()
        if (danger) dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFD32F2F.toInt())
    }

    private fun decoller() {
        if (enVol) { txtEtat.text = getString(R.string.p2b_deja_vol); return }
        if (!pretAVoler()) {
            txtEtat.text = getString(R.string.p2b_bloque)
            return
        }
        txtEtat.text = getString(R.string.p2b_activation)
        pont.activerVirtualStick(true); vsActif = true
        pont.decoller { ok ->
            runOnUiThread {
                enVol = ok
                txtEtat.text = if (ok) getString(R.string.p2b_en_vol)
                               else getString(R.string.p2b_echec_decollage)
            }
        }
    }

    private fun atterrir() {
        cmdTest = null
        pont.atterrir { ok ->
            runOnUiThread {
                if (ok) { enVol = false; txtEtat.text = getString(R.string.p2b_atterri) }
                else txtEtat.text = getString(R.string.p2b_echec_atterrissage)
            }
        }
        // on laisse VirtualStick actif un court instant pour la descente, puis on coupe
        lifecycleScope.launch { delay(4000); pont.activerVirtualStick(false); vsActif = false }
    }

    /** Envoie une commande sur UN axe pendant DUREE_TEST_MS, puis revient en hover. */
    private fun testAxe(pitch: Float, roll: Float, throttle: Float, yaw: Float) {
        if (!enVol) { txtEtat.text = getString(R.string.p2b_decolle_dabord); return }
        txtEtat.text = getString(R.string.p2b_test_axe, pitch, roll, throttle, yaw)
        cmdTest = floatArrayOf(pitch, roll, throttle, yaw)
        lifecycleScope.launch {
            delay(DUREE_TEST_MS)
            cmdTest = null   // retour hover
            runOnUiThread { if (enVol) txtEtat.text = getString(R.string.p2b_hover) }
        }
    }

    private fun arretUrgence() {
        cmdTest = null
        try { pont.envoyerVitesses(0f, 0f, 0f, 0f, ca.cineflight.stage.control.CommandOrigin.TEST) } catch (_: Exception) {}   // hover immediat
        try { pont.activerVirtualStick(false) } catch (_: Exception) {}        // rend la main a la RC
        vsActif = false
        txtEtat.text = getString(R.string.p2b_arret_fait)
    }

    private fun rafraichir() {
        val c = try { pont.estConnecte() } catch (_: Throwable) { false }
        val g = try { pont.gpsValide() } catch (_: Throwable) { false }
        val b = try { pont.batteriePourcent() } catch (_: Throwable) { -1 }
        val alt = try { pont.altitudeDrone() } catch (_: Throwable) { Double.NaN }
        when {
            !c -> setVoyant(getString(R.string.p2b_voyant_deco), 0xFF8B1A1A.toInt())
            enVol -> setVoyant(getString(R.string.p2b_voyant_envol, (if (alt.isNaN()) "—" else "%.1f".format(alt)), b), 0xFF1B5E20.toInt())
            pretAVoler() -> setVoyant(getString(R.string.p2b_voyant_pret), 0xFF1B5E20.toInt())
            !g -> setVoyant(getString(R.string.p2b_voyant_gps), 0xFF8B1A1A.toInt())
            b in 0..40 -> setVoyant(getString(R.string.p2b_voyant_batt, b), 0xFF8B1A1A.toInt())
            else -> setVoyant(getString(R.string.p2b_voyant_verif), 0xFF7A5900.toInt())
        }
    }

    private fun setVoyant(t: String, c: Int) { voyant.text = t; voyant.setBackgroundColor(c) }

    override fun onPause() {
        super.onPause()
        // securite : si l'ecran passe en arriere-plan, on coupe l'envoi (hover via RC)
        cmdTest = null
        if (vsActif && !enVol) { try { pont.activerVirtualStick(false) } catch (_: Exception) {}; vsActif = false }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

