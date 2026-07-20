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
 * PHASE 1 — TELEMETRIE DRONE REEL (lecture seule, AUCUN vol).
 *
 * Cet ecran valide que le pont MSDK (PontDjiReel) lit bien les donnees du vrai
 * drone : connexion, modele, batterie, satellites GPS, position, altitude, cap.
 * Il N'ENVOIE AUCUNE COMMANDE au drone — c'est un test de cablage du SDK, sans
 * risque. A utiliser drone allume + telecommande connectee, helices retirees ou
 * drone au sol.
 *
 * Voyant PRET A VOLER (vert) uniquement si : connecte ET gpsValide (>=8 sats)
 * ET batterie > 40 %. Sinon, voyant rouge avec la raison.
 */
class TelemetrieActivity : AppCompatActivity() {

    private val pont = PontDjiReel(ObstacleGateWiring.Off)
    private var listenersOk = false

    private lateinit var voyant: TextView
    private lateinit var txtSdk: TextView
    private lateinit var txtConnexion: TextView
    private lateinit var txtBatterie: TextView
    private lateinit var txtGps: TextView
    private lateinit var txtAltitude: TextView
    private lateinit var txtCap: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fond = 0xFF0D1117.toInt()
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(fond)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        racine.addView(TextView(this).apply {
            text = getString(R.string.te_titre)
            setTextColor(Color.WHITE); textSize = 18f
        })
        racine.addView(TextView(this).apply {
            text = getString(R.string.te_sous)
            setTextColor(0xFF9DA5B4.toInt()); textSize = 12f
            setPadding(0, dp(4), 0, dp(16))
        })

        // grand voyant d'etat
        voyant = TextView(this).apply {
            text = getString(R.string.te_init)
            setTextColor(Color.WHITE); textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), dp(20))
            setBackgroundColor(0xFF7A5900.toInt())
        }
        racine.addView(voyant, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun ligne(): TextView = TextView(this).apply {
            setTextColor(0xFFE6EDF3.toInt()); textSize = 15f
            setPadding(0, dp(10), 0, dp(10))
        }
        txtSdk = ligne(); txtConnexion = ligne(); txtBatterie = ligne()
        txtGps = ligne(); txtAltitude = ligne(); txtCap = ligne()

        val carte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFF161B22.toInt())
        }
        for (t in listOf(txtSdk, txtConnexion, txtBatterie, txtGps, txtAltitude, txtCap)) {
            carte.addView(t)
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(16)
        racine.addView(carte, lp)

        // Bouton pour passer a la Phase 2 (test VirtualStick au sol)
        racine.addView(Button(this).apply {
            text = getString(R.string.te_phase2)
            isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
            }
            setOnClickListener {
                startActivity(android.content.Intent(this@TelemetrieActivity, Phase2Activity::class.java))
            }
        })

        setContentView(ScrollView(this).apply { addView(racine) })

        // Enregistrement SDK (idempotent : MainActivity l'a deja fait au lancement).
        txtSdk.text = getString(R.string.te_sdk_enr)
        EnregistrementSdk.enregistrer(applicationContext) { ok, message ->
            runOnUiThread {
                if (ok) {
                    txtSdk.text = getString(R.string.te_sdk_ok, message)
                    if (!listenersOk) {
                        try { pont.initialiserListeners(); listenersOk = true }
                        catch (e: Exception) {
                            txtSdk.text = getString(R.string.te_sdk_listeners, e.message)
                        }
                    }
                } else {
                    txtSdk.text = getString(R.string.te_sdk_echec, message)
                }
            }
        }

        // boucle d'affichage (500 ms), lecture seule
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                rafraichir()
                delay(500)
            }
        }
    }

    private fun rafraichir() {
        val connecte = try { pont.estConnecte() } catch (_: Throwable) { false }
        val batt = try { pont.batteriePourcent() } catch (_: Throwable) { -1 }
        val lat = try { pont.latitudeDrone() } catch (_: Throwable) { Double.NaN }
        val lon = try { pont.longitudeDrone() } catch (_: Throwable) { Double.NaN }
        val alt = try { pont.altitudeDrone() } catch (_: Throwable) { Double.NaN }
        val cap = try { pont.capDroneDeg() } catch (_: Throwable) { Float.NaN }
        val gpsOk = try { pont.gpsValide() } catch (_: Throwable) { false }
        val modele = try { pont.modeleDrone() } catch (_: Throwable) { "?" }

        txtConnexion.text = if (connecte) getString(R.string.te_connecte, modele)
                            else getString(R.string.te_non_connecte)
        txtBatterie.text = if (batt >= 0) getString(R.string.te_batterie, batt) else getString(R.string.te_batterie_vide)
        txtGps.text = if (!lat.isNaN() && !lon.isNaN())
            getString(R.string.te_gps, "%.6f".format(lat), "%.6f".format(lon), (if (gpsOk) getString(R.string.te_fix_ok) else getString(R.string.te_fix_ko)))
        else getString(R.string.te_gps_nofix)
        txtAltitude.text = if (!alt.isNaN()) getString(R.string.te_alt, "%.1f".format(alt)) else getString(R.string.te_alt_vide)
        txtCap.text = if (!cap.isNaN()) getString(R.string.te_cap, "%.0f".format(cap)) else getString(R.string.te_cap_vide)

        // voyant PRET / PAS PRET
        when {
            !connecte -> setVoyant(getString(R.string.te_voyant_deco), 0xFF8B1A1A.toInt())
            !gpsOk -> setVoyant(getString(R.string.te_voyant_gps), 0xFF8B1A1A.toInt())
            batt in 0..40 -> setVoyant(getString(R.string.te_voyant_batt, batt), 0xFF8B1A1A.toInt())
            else -> setVoyant(getString(R.string.te_voyant_pret), 0xFF1B5E20.toInt())
        }
    }

    private fun setVoyant(texte: String, couleur: Int) {
        voyant.text = texte
        voyant.setBackgroundColor(couleur)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

