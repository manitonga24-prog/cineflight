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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * MomentsJourneeActivity — ASSISTANT DE TOURNAGE (preparation, PAS de vol).
 *
 * "Quand filmer ce lieu aujourd'hui ?" Appelle /api/moments_journee et affiche
 * les fenetres de lumiere de la journee (lever, golden hour, midi, coucher,
 * heure bleue) comme un assistant de tournage — pas comme de l'astronomie.
 *
 * Aucun drone, aucune commande, aucune meteo requise. Demontrable immediatement.
 *
 * Entree (Intent extras) :
 *   "lat" (Double), "lon" (Double)  — position du lieu. Defaut : Montreal.
 *   "nomLieu" (String, optionnel)   — libelle affiche.
 *
 * Separation claire :
 *   Telemetrie / cockpit  = VOL
 *   MomentsJourneeActivity = PREPARATION
 */
class MomentsJourneeActivity : AppCompatActivity() {

    private val BASE_URL = "https://cineflight.ca"

    private lateinit var colonne: LinearLayout
    private lateinit var titreLieu: TextView

    // Palette alignee sur la maquette
    private val FOND = 0xFF0D1117.toInt()
    private val CARTE = 0xFF161B22.toInt()
    private val ACCENT = 0xFF4FC3F7.toInt()
    private val TEXTE = 0xFFECEFF1.toInt()
    private val DOUX = 0xFF90A4AE.toInt()

    private var lat = 45.50
    private var lon = -73.56
    private var nomLieu = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lat = intent.getDoubleExtra("lat", 45.50)
        lon = intent.getDoubleExtra("lon", -73.56)
        nomLieu = intent.getStringExtra("nomLieu") ?: getString(R.string.mj_ce_lieu)

        colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FOND)
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        // En-tete
        colonne.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MomentsJourneeActivity).apply { text = "📍 "; textSize = 18f })
            addView(TextView(this@MomentsJourneeActivity).apply {
                text = getString(R.string.mj_titre_header); setTextColor(TEXTE); textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        })
        titreLieu = TextView(this).apply {
            text = getString(R.string.mj_chargement, nomLieu); setTextColor(DOUX); textSize = 13f
            setPadding(0, dp(4), 0, dp(14))
        }
        colonne.addView(titreLieu)

        // Placeholder pendant le chargement
        colonne.addView(TextView(this).apply {
            text = getString(R.string.mj_calcul)
            setTextColor(DOUX); textSize = 14f; setPadding(dp(4), dp(20), 0, 0)
            tag = "placeholder"
        })

        setContentView(ScrollView(this).apply { addView(colonne) })

        charger()
    }

    private fun charger() {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { appelMoments(lat, lon) }
            if (res == null) {
                afficherErreur()
            } else {
                afficher(res)
            }
        }
    }

    /** Appel reseau bloquant (sur IO). Renvoie le JSON ou null si echec. */
    private fun appelMoments(lat: Double, lon: Double): JSONObject? {
        return try {
            val body = JSONObject().apply {
                put("latitude", lat); put("longitude", lon)
                put("timezone", "America/Montreal")
            }
            val conn = (URL("$BASE_URL/api/moments_journee").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 8000; readTimeout = 10000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (code in 200..299) JSONObject(txt) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun afficherErreur() {
        retirerPlaceholder()
        titreLieu.text = "$nomLieu"
        colonne.addView(carte {
            val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            l.addView(TextView(this@MomentsJourneeActivity).apply {
                text = getString(R.string.mj_echec)
                setTextColor(0xFFFF8A65.toInt()); textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            l.addView(TextView(this@MomentsJourneeActivity).apply {
                text = getString(R.string.mj_echec_msg)
                setTextColor(DOUX); textSize = 13f; setPadding(0, dp(4), 0, 0)
            })
            l
        })
        colonne.addView(Button(this).apply {
            text = getString(R.string.mj_reessayer); isAllCaps = false; textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF455A64.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) }
            setOnClickListener { reinitialiser(); charger() }
        })
    }

    private fun afficher(res: JSONObject) {
        retirerPlaceholder()

        val date = res.optString("date", "")
        titreLieu.text = "$nomLieu · $date"

        val moments = res.optJSONArray("moments") ?: return
        val maintenantMin = minutesDepuisMinuit()

        // 1) trouver la prochaine fenetre a venir (en-tete "hero")
        var heroIndex = -1
        for (i in 0 until moments.length()) {
            val m = moments.getJSONObject(i)
            val finMin = hhmmEnMinutes(extraireHeure(m.optString("fin")))
            if (finMin >= maintenantMin) { heroIndex = i; break }
        }
        if (heroIndex >= 0) {
            ajouterHero(moments.getJSONObject(heroIndex), maintenantMin)
        }

        // 2) titre de section
        colonne.addView(TextView(this).apply {
            text = getString(R.string.mj_toutes); textSize = 11f
            setTextColor(DOUX); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(14), 0, dp(8))
        })

        // 3) chaque moment, grise si passe
        for (i in 0 until moments.length()) {
            val m = moments.getJSONObject(i)
            val finMin = hhmmEnMinutes(extraireHeure(m.optString("fin")))
            val passe = finMin < maintenantMin
            colonne.addView(carteMoment(m, passe))
        }

        // note de bas
        colonne.addView(TextView(this).apply {
            text = getString(R.string.mj_heures)
            textSize = 11f; setTextColor(0xFF5B6675.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
    }

    private fun ajouterHero(m: JSONObject, maintenantMin: Int) {
        val info = libelleDe(m.optString("cle"))
        val debut = extraireHeure(m.optString("debut"))
        val fin = extraireHeure(m.optString("fin"))
        val pic = extraireHeure(m.optString("meilleur_moment"))
        val estInstant = debut == fin

        val debutMin = hhmmEnMinutes(debut)
        val dansMin = (debutMin - maintenantMin).coerceAtLeast(0)
        val dansTxt = when {
            dansMin <= 0 -> getString(R.string.mj_en_cours)
            dansMin < 60 -> getString(R.string.mj_dans_min, dansMin)
            else -> getString(R.string.mj_dans_h, dansMin / 60)
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setBackgroundColor(0xFF2A2418.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
        hero.addView(TextView(this).apply {
            text = getString(R.string.mj_prochaine, dansTxt)
            setTextColor(0xFFFFB74D.toInt()); textSize = 11f
        })
        hero.addView(TextView(this).apply {
            text = "${info.emoji} ${info.libelle}"
            setTextColor(TEXTE); textSize = 21f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(2))
        })
        hero.addView(TextView(this).apply {
            text = if (estInstant) debut else getString(R.string.mj_plage, debut, fin, pic)
            setTextColor(TEXTE); textSize = 15f
        })
        if (info.description.isNotEmpty()) {
            hero.addView(TextView(this).apply {
                text = info.description; setTextColor(DOUX); textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
        }
        colonne.addView(hero)
    }

    private fun carteMoment(m: JSONObject, passe: Boolean): LinearLayout {
        val info = libelleDe(m.optString("cle"))
        val debut = extraireHeure(m.optString("debut"))
        val fin = extraireHeure(m.optString("fin"))
        val pic = extraireHeure(m.optString("meilleur_moment"))
        val estInstant = debut == fin

        return carte {
            val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // ligne principale
            val ligne = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            ligne.addView(TextView(this@MomentsJourneeActivity).apply {
                text = "${info.emoji}  "; textSize = 17f
            })
            ligne.addView(TextView(this@MomentsJourneeActivity).apply {
                text = info.libelle; setTextColor(TEXTE); textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            ligne.addView(TextView(this@MomentsJourneeActivity).apply {
                text = if (estInstant) debut else "$debut → $fin"
                setTextColor(if (estInstant) TEXTE else ACCENT); textSize = 14f
            })
            l.addView(ligne)

            // sous-ligne : pic + description
            val sousTxt = buildString {
                if (!estInstant) append(getString(R.string.mj_pic, pic))
                if (info.description.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(info.description)
                }
                if (passe) {
                    if (isNotEmpty()) append(" · ")
                    append(getString(R.string.mj_deja_passe))
                }
            }
            if (sousTxt.isNotEmpty()) {
                l.addView(TextView(this@MomentsJourneeActivity).apply {
                    text = sousTxt; setTextColor(DOUX); textSize = 12f
                    setPadding(dp(28), dp(4), 0, 0)
                })
            }

            // bouton "Préparer un plan" seulement sur les fenetres a venir (pas instants, pas passe)
            if (!passe && !estInstant) {
                l.addView(Button(this@MomentsJourneeActivity).apply {
                    text = getString(R.string.mj_preparer); isAllCaps = false; textSize = 13f
                    setTextColor(0xFFCDEEFF.toInt())
                    backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF15485F.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
                    ).apply { topMargin = dp(10); leftMargin = dp(28) }
                    setOnClickListener { preparerPlan(info, debut, fin, pic, m.optString("cle")) }
                })
                l.addView(TextView(this@MomentsJourneeActivity).apply {
                    text = getString(R.string.mj_choisis)
                    setTextColor(DOUX); textSize = 11f; setPadding(dp(28), dp(6), 0, 0)
                })
            }

            if (passe) l.alpha = 0.4f
            l
        }
    }

    /**
     * "Préparer un plan" : point de branchement vers l'assistant de recettes.
     * En v1 : message. Plus tard : ouvre le PanneauRecettes / Catalogue.suggestions()
     * en transmettant le contexte de lumiere choisi.
     */
    /** Memorise le contexte de lumiere choisi et redonne la main a MainActivity,
     *  qui ouvrira DefinitionSujetActivity. Le KMZ reviendra a MainActivity. */
    private fun preparerPlan(info: InfoMoment, debut: String, fin: String, pic: String, cle: String) {
        val b = android.os.Bundle().apply {
            putDouble("centre_lat", lat)
            putDouble("centre_lon", lon)
            putString("nomLieu", nomLieu)
            putString("momentCle", cle)
            putString("momentNom", info.libelle)
            putString("momentDebut", debut)
            putString("momentFin", fin)
            putString("momentPic", pic)
        }
        ca.cineflight.stage.MainActivity.lumiereEnAttente = b
        finish()   // retour a MainActivity -> onResume consomme l'intention
    }

    // ---------- Mapping cle -> libelle humain (service pur, app habille) ----------
    private data class InfoMoment(val emoji: String, val libelle: String, val description: String)

    private fun libelleDe(cle: String): InfoMoment = when (cle) {
        "lever"         -> InfoMoment("🌄", getString(R.string.mj_lever), "")
        "golden_matin"  -> InfoMoment("🌅", getString(R.string.mj_doree_matin), getString(R.string.mj_chaude_rasante))
        "midi_solaire"  -> InfoMoment("☀️", getString(R.string.mj_midi), getString(R.string.mj_dure))
        "golden_soir"   -> InfoMoment("🌅", getString(R.string.mj_doree_soir), getString(R.string.mj_chaude_rev))
        "coucher"       -> InfoMoment("🌇", getString(R.string.mj_coucher), "")
        "bleue_soir"    -> InfoMoment("🔵", getString(R.string.mj_bleue_soir), getString(R.string.mj_froide))
        "bleue_matin"   -> InfoMoment("🔵", getString(R.string.mj_bleue_matin), getString(R.string.mj_froide))
        else            -> InfoMoment("•", cle, "")
    }

    // ---------- Helpers ----------
    /** Extrait "HH:mm" d'un ISO "2026-06-13T20:00:00-04:00". */
    private fun extraireHeure(iso: String): String {
        val tPos = iso.indexOf('T')
        if (tPos < 0 || iso.length < tPos + 6) return iso
        return iso.substring(tPos + 1, tPos + 6)   // "20:00"
    }

    private fun hhmmEnMinutes(hhmm: String): Int {
        val p = hhmm.split(":")
        return if (p.size == 2) (p[0].toIntOrNull() ?: 0) * 60 + (p[1].toIntOrNull() ?: 0) else 0
    }

    private fun minutesDepuisMinuit(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    private fun carte(contenu: () -> ViewGroup): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            addView(contenu())
        }
    }

    private fun retirerPlaceholder() {
        val ph = colonne.findViewWithTag<TextView>("placeholder")
        if (ph != null) colonne.removeView(ph)
    }

    private fun reinitialiser() {
        // retire tout sauf l'en-tete (2 premieres vues) et remet le placeholder
        while (colonne.childCount > 2) colonne.removeViewAt(2)
        colonne.addView(TextView(this).apply {
            text = getString(R.string.mj_calcul)
            setTextColor(DOUX); textSize = 14f; setPadding(dp(4), dp(20), 0, 0)
            tag = "placeholder"
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

