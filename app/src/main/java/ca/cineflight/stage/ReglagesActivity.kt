package ca.cineflight.stage

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import ca.cineflight.stage.control.Reglages

class ReglagesActivity : AppCompatActivity() {
    private lateinit var reglages: Reglages
    private val FOND = 0xFFF2F2F7.toInt()
    private val CARTE = 0xFFFFFFFF.toInt()
    private val ACCENT = 0xFF007AFF.toInt()
    private val TEXTE = 0xFF1C1C1E.toInt()
    private val TEXTE_DOUX = 0xFF8E8E93.toInt()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reglages = Reglages(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(FOND) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(24)) }
        scroll.addView(col)
        col.addView(TextView(this).apply { text = getString(R.string.rg_titre); textSize = 24f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        col.addView(TextView(this).apply { text = getString(R.string.rg_sous); textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, dp(16)) })
        col.addView(carteSujetASuivre())
        col.addView(carteCamera())
        col.addView(carteEnregistrement())
        col.addView(carteEvitement())
        col.addView(carteSecuriteSuivi())
        col.addView(carteQualiteYolo())
        col.addView(carteVoix())
        col.addView(carteModeVoix())
        col.addView(carteVlos())
        col.addView(carteAltitude())
        for ((nomGroupe, params) in Reglages.GROUPES) {
            val card = CardView(this).apply {
                radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(12); layoutParams = lp
            }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
            box.addView(TextView(this).apply { text = getString(nomGroupe); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(8)) })
            for (p in params) box.addView(ligneParam(p))
            card.addView(box); col.addView(card)
        }
        col.addView(Button(this).apply {
            text = getString(R.string.rg_valeurs_sures)
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF34C759.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)); lp.topMargin = dp(8); layoutParams = lp
            setOnClickListener {
                reglages.appliquerValeursSures()
                Toast.makeText(this@ReglagesActivity, getString(R.string.rg_valeurs_sures_ok), Toast.LENGTH_SHORT).show()
                recreate()
            }
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.rg_config_prudente)
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(dp(4), dp(6), dp(4), dp(4))
        })
        col.addView(Button(this).apply {
            text = getString(R.string.rg_reinit)
            setTextColor(0xFFFF3B30.toInt()); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFE5E3.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)); lp.topMargin = dp(8); layoutParams = lp
            setOnClickListener { for ((_, params) in Reglages.GROUPES) for (p in params) reglages.reset(p); recreate() }
        })
        // Outil developpeur : ecran de test de l'assistant vocal (Phase 1). Isole, sans drone.
        col.addView(Button(this).apply {
            text = "🔊 Test assistant vocal (dev)"
            setTextColor(0xFFB0BEC5.toInt()); textSize = 13f; isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF263238.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)); lp.topMargin = dp(16); layoutParams = lp
            setOnClickListener {
                startActivity(android.content.Intent(this@ReglagesActivity,
                    ca.cineflight.stage.voice.FlightVoiceTestActivity::class.java))
            }
        })
        setContentView(scroll)
    }

    private fun carteAltitude(): androidx.cardview.widget.CardView {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.rg_alt_titre); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_alt_desc)
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, dp(10))
        })
        val prefs = getSharedPreferences("cineflight", MODE_PRIVATE)
        val actuelle = prefs.getInt("alt_limite_m", 122)
        val lblVal = TextView(this).apply {
            text = getString(R.string.rg_alt_limite_fmt, actuelle)
            textSize = 14f; setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, dp(4))
        }
        box.addView(lblVal)
        // Plage 30..150 m par pas de 2 m -> 60 crans.
        val seek = SeekBar(this).apply {
            max = 60
            progress = ((actuelle - 30) / 2).coerceIn(0, 60)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val v = 30 + prog * 2
                    lblVal.text = getString(R.string.rg_alt_limite_fmt, v)
                    if (fromUser) prefs.edit().putInt("alt_limite_m", v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(seek)
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_alt_rappel)
            textSize = 12f; setTextColor(0xFFB25000.toInt()); setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(10), 0, 0)
        })
        card.addView(box)
        return card
    }

    private fun carteVlos(): androidx.cardview.widget.CardView {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.rg_vlos_titre); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_vlos_desc)
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, dp(10))
        })
        val prefs = getSharedPreferences("cineflight", MODE_PRIVATE)
        val limiteActuelle = prefs.getInt("vlos_limite_m", 600)
        val lblVal = TextView(this).apply {
            text = getString(R.string.rg_vlos_limite_fmt, limiteActuelle)
            textSize = 14f; setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, dp(4))
        }
        box.addView(lblVal)
        // Plage 100..2000 m par pas de 50 m -> 38 crans.
        val seek = SeekBar(this).apply {
            max = 38
            progress = ((limiteActuelle - 100) / 50).coerceIn(0, 38)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val v = 100 + prog * 50
                    lblVal.text = getString(R.string.rg_vlos_limite_fmt, v)
                    if (fromUser) prefs.edit().putInt("vlos_limite_m", v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(seek)
        val bornes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bornes.addView(TextView(this).apply { text = "100 m"; textSize = 11f; setTextColor(TEXTE_DOUX); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        bornes.addView(TextView(this).apply { text = "2000 m"; textSize = 11f; setTextColor(TEXTE_DOUX) })
        box.addView(bornes)
        // RAPPEL REGLEMENTAIRE : la limite n'est PAS une garantie de visibilite.
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_vlos_rappel)
            textSize = 12f; setTextColor(0xFFB25000.toInt()); setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(10), 0, 0)
        })
        card.addView(box)
        return card
    }

    private fun carteModeVoix(): androidx.cardview.widget.CardView {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.rg_voixmode_titre); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_voixmode_desc)
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, dp(10))
        })
        val prefs = getSharedPreferences("cineflight", MODE_PRIVATE)
        val libelles = listOf(
            getString(R.string.rg_voixmode_minimal),
            getString(R.string.rg_voixmode_normal),
            getString(R.string.rg_voixmode_detaille)
        )
        val notes = listOf(
            getString(R.string.rg_voixmode_minimal_note),
            getString(R.string.rg_voixmode_normal_note),
            getString(R.string.rg_voixmode_detaille_note)
        )
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val boutons = mutableListOf<Button>()
        val noteTxt = TextView(this).apply {
            textSize = 12f; setTextColor(TEXTE_DOUX); setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(8), 0, 0)
        }
        fun rafraichir() {
            val sel = prefs.getInt("voix_mode", 1)
            boutons.forEachIndexed { i, b ->
                if (i == sel) { b.setBackgroundColor(ACCENT); b.setTextColor(0xFFFFFFFF.toInt()) }
                else { b.setBackgroundColor(0xFFE5E5EA.toInt()); b.setTextColor(TEXTE) }
            }
            noteTxt.text = notes[sel.coerceIn(0, 2)]
        }
        libelles.forEachIndexed { i, lib ->
            val b = Button(this).apply {
                text = lib; textSize = 13f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener { prefs.edit().putInt("voix_mode", i).apply(); rafraichir() }
            }
            boutons.add(b); ligne.addView(b)
        }
        box.addView(ligne)
        box.addView(noteTxt)
        rafraichir()
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_voixmode_rappel)
            textSize = 12f; setTextColor(0xFFB25000.toInt()); setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(10), 0, 0)
        })
        card.addView(box)
        return card
    }

    private fun carteVoix(): androidx.cardview.widget.CardView {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.rg_voix_titre); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        ligne.addView(TextView(this).apply {
            text = getString(R.string.rg_voix_desc)
            textSize = 14f; setTextColor(TEXTE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val prefs = getSharedPreferences("cineflight", MODE_PRIVATE)
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean("voix_active", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("voix_active", checked).apply()
            }
        }
        ligne.addView(sw)
        box.addView(ligne)
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_voix_note)
            textSize = 12f; setTextColor(TEXTE_DOUX); setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(8), 0, 0)
        })
        card.addView(box)
        return card
    }

    private fun carteQualiteYolo(): androidx.cardview.widget.CardView {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.rg_yolo_titre); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_yolo_desc)
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, dp(10))
        })
        val libelles = listOf(getString(R.string.rg_yolo_rapide), getString(R.string.rg_yolo_precis))
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val boutons = mutableListOf<Button>()
        fun rafraichir() {
            val sel = reglages.getQualiteYolo()
            boutons.forEachIndexed { i, b ->
                if (i == sel) { b.setBackgroundColor(ACCENT); b.setTextColor(0xFFFFFFFF.toInt()) }
                else { b.setBackgroundColor(0xFFE5E5EA.toInt()); b.setTextColor(TEXTE) }
            }
        }
        libelles.forEachIndexed { i, lib ->
            val b = Button(this).apply {
                text = lib; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener { reglages.setQualiteYolo(i); rafraichir() }
            }
            boutons.add(b); ligne.addView(b)
        }
        box.addView(ligne)
        rafraichir()
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_yolo_note)
            textSize = 12f; setTextColor(TEXTE_DOUX); setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(8), 0, 0)
        })
        card.addView(box)
        return card
    }

    private fun carteSecuriteSuivi(): androidx.cardview.widget.CardView {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.rg_secsuivi_titre); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply { text = getString(R.string.rg_secsuivi_desc); textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, dp(10)) })
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        ligne.addView(TextView(this).apply { text = getString(R.string.rg_secsuivi_switch); textSize = 14f; setTextColor(TEXTE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val sw = android.widget.Switch(this)
        sw.isChecked = reglages.getDeplacementCamera()
        sw.setOnCheckedChangeListener { btn, v ->
            if (!btn.isPressed) return@setOnCheckedChangeListener
            if (v) {
                android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.rg_secsuivi_avert_titre))
                    .setMessage(getString(R.string.rg_secsuivi_avert_msg))
                    .setPositiveButton(getString(R.string.rg_secsuivi_avert_ok)) { _, _ -> reglages.setDeplacementCamera(true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> reglages.setDeplacementCamera(false); sw.isChecked = false }
                    .setOnCancelListener { reglages.setDeplacementCamera(false); sw.isChecked = false }
                    .show()
            } else reglages.setDeplacementCamera(false)
        }
        ligne.addView(sw)
        box.addView(ligne)
        card.addView(box)
        return card
    }

    private fun carteEvitement(): androidx.cardview.widget.CardView {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.rg_evit_titre); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_evit_desc)
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, dp(10))
        })
        // selecteur 3 etats : OFF / ON / AUTO
        val libelles = listOf("OFF", "ON", "AUTO")
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val boutons = mutableListOf<Button>()
        fun rafraichir() {
            val sel = reglages.getModeEvitement()
            boutons.forEachIndexed { i, b ->
                if (i == sel) {
                    b.setBackgroundColor(ACCENT); b.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    b.setBackgroundColor(0xFFE5E5EA.toInt()); b.setTextColor(TEXTE)
                }
            }
        }
        libelles.forEachIndexed { i, lib ->
            val b = Button(this).apply {
                text = lib; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener { reglages.setModeEvitement(i); rafraichir() }
            }
            boutons.add(b); ligne.addView(b)
        }
        box.addView(ligne)
        rafraichir()
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_evit_note)
            textSize = 12f; setTextColor(TEXTE_DOUX); setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(8), 0, 0)
        })
        card.addView(box)
        return card
    }

    private fun carteEnregistrement(): androidx.cardview.widget.CardView {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.rg_enreg_titre); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        ligne.addView(TextView(this).apply {
            text = getString(R.string.rg_enreg_desc)
            textSize = 14f; setTextColor(TEXTE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(this).apply {
            isChecked = reglages.getEnregAuto()
            setOnCheckedChangeListener { _, checked -> reglages.setEnregAuto(checked) }
        }
        ligne.addView(sw)
        box.addView(ligne)
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_enreg_note)
            textSize = 12f; setTextColor(TEXTE_DOUX); setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(8), 0, 0)
        })
        card.addView(box)
        return card
    }

    private fun carteCamera(): androidx.cardview.widget.CardView {
        val ACCENT = 0xFF007AFF.toInt()
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.rg_cam_titre); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_cam_desc)
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, dp(10))
        })
        // Resolution
        box.addView(TextView(this).apply { text = getString(R.string.rg_cam_resolution); textSize = 13f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(2), 0, dp(4)) })
        val resLbl = listOf("FHD 1080p", "2.7K", "4K")
        val resBtns = ArrayList<Button>()
        fun majRes() { for ((i,b) in resBtns.withIndex()) { if (i==reglages.getResolution()){b.setBackgroundColor(ACCENT);b.setTextColor(0xFFFFFFFF.toInt())} else {b.setBackgroundColor(0xFFE5E5EA.toInt());b.setTextColor(TEXTE)} } }
        val ligneRes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i,lbl) in resLbl.withIndex()) {
            val b = Button(this).apply { text = lbl; textSize = 12f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2),0,dp(2),0) }
                setOnClickListener { reglages.setResolution(i); majRes() } }
            resBtns.add(b); ligneRes.addView(b)
        }
        majRes(); box.addView(ligneRes)
        // FPS
        box.addView(TextView(this).apply { text = getString(R.string.rg_cam_fps); textSize = 13f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, dp(4)) })
        // 100 et 200 = RALENTI. Sur Mini 4 Pro, 100 i/s existe en 4K ET en 1080p — c'est la
        // combinaison 4K/100 qui impose le codec H.265, pas le 100 i/s en soi. Le 200 i/s,
        // lui, est bien limité au 1080p. Le pont choisit ensuite la valeur SUPPORTÉE la
        // plus proche : une demande impossible retombe sans refus ni surprise.
        // ⚠ La cadence ne double pas le poids des fichiers : c'est le DÉBIT et le codec qui
        // décident. Ne pas réintroduire ce raccourci dans les libellés.
        // ⚠ Le ralenti ne sert PAS qu'aux sujets rapides : c'est aussi ce qui donne aux
        // paysages le mouvement lent et spectaculaire des économiseurs Aerial d'Apple TV.
        // Mais l'effet tient surtout à un déplacement de drone TRÈS lent et bien stabilisé —
        // Apple ne publie pas la cadence de tournage de ces séquences, et une cadence
        // élevée seule ne suffirait pas.
        val fpsVals = listOf(24, 30, 60, 100, 200)
        val fpsBtns = ArrayList<Button>()
        // Une explication PAR VALEUR, affichée sous les boutons et remplacée au clic.
        // Un réglage numérique sans conséquence énoncée ne se choisit pas, il se subit :
        // « 60 » ne dit rien, « très fluide, deux fois plus de fichiers » se décide.
        val fpsExplic = mapOf(
            24 to R.string.rg_fps_24, 30 to R.string.rg_fps_30, 60 to R.string.rg_fps_60,
            100 to R.string.rg_fps_100, 200 to R.string.rg_fps_200)
        val txtExplicFps = TextView(this).apply {
            textSize = 12f; setTextColor(TEXTE); setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.2f)
        }
        fun majFps() {
            for ((i,b) in fpsBtns.withIndex()) { if (fpsVals[i]==reglages.getFps()){b.setBackgroundColor(ACCENT);b.setTextColor(0xFFFFFFFF.toInt())} else {b.setBackgroundColor(0xFFE5E5EA.toInt());b.setTextColor(TEXTE)} }
            txtExplicFps.text = getString(fpsExplic[reglages.getFps()] ?: R.string.rg_fps_30)
        }
        val ligneFps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i,v) in fpsVals.withIndex()) {
            val b = Button(this).apply { text = "$v"; textSize = 12f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2),0,dp(2),0) }
                setOnClickListener { reglages.setFps(v); majFps() } }
            fpsBtns.add(b); ligneFps.addView(b)
        }
        majFps(); box.addView(ligneFps); box.addView(txtExplicFps)
        box.addView(TextView(this).apply { text = getString(R.string.rg_cam_fps_note); textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(8), 0, 0) })

        // --- QUALITÉ PANORAMA : JPEG / DNG / les deux ---
        box.addView(TextView(this).apply { text = getString(R.string.rg_qual_pano); textSize = 13f
            setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(4)) })
        val fmtVals = listOf(0, 1, 2)
        val fmtLibelles = listOf(getString(R.string.rg_qual_rapide), getString(R.string.rg_qual_haute),
            getString(R.string.rg_qual_reco))
        val fmtExplic = listOf(R.string.rg_qual_rapide_d, R.string.rg_qual_haute_d, R.string.rg_qual_reco_d)
        val fmtBtns = ArrayList<Button>()
        val txtExplicFmt = TextView(this).apply {
            textSize = 12f; setTextColor(TEXTE); setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.2f)
        }
        fun majFmt() {
            val cur = reglages.getFormatPhoto()
            for ((i,b) in fmtBtns.withIndex()) {
                if (fmtVals[i] == cur) { b.setBackgroundColor(ACCENT); b.setTextColor(0xFFFFFFFF.toInt()) }
                else { b.setBackgroundColor(0xFFE5E5EA.toInt()); b.setTextColor(TEXTE) }
            }
            txtExplicFmt.text = getString(fmtExplic[cur.coerceIn(0, 2)])
        }
        val ligneFmt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i,v) in fmtVals.withIndex()) {
            val b = Button(this).apply { text = fmtLibelles[i]; textSize = 11f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2),0,dp(2),0) }
                setOnClickListener { reglages.setFormatPhoto(v); majFmt() } }
            fmtBtns.add(b); ligneFmt.addView(b)
        }
        majFmt(); box.addView(ligneFmt); box.addView(txtExplicFmt)
        // --- Distance du rail "vers le sujet" (appui long sur Rail A) ---
        val lblDist = TextView(this).apply {
            text = getString(R.string.rg_rail_dist_fmt, reglages.getRailSujetDist())
            textSize = 13f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, dp(4))
        }
        box.addView(lblDist)
        val seekDist = SeekBar(this).apply {
            tag = "rail_sujet_dist_seek"
            max = 27
            progress = reglages.getRailSujetDist() - 3
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val d = prog + 3
                    lblDist.text = getString(R.string.rg_rail_dist_fmt, d)
                    if (fromUser) reglages.setRailSujetDist(d)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(seekDist)
        box.addView(TextView(this).apply { text = getString(R.string.rg_rail_note); textSize = 11f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, 0) })
        card.addView(box)
        return card
    }

    private fun carteSujetASuivre(): androidx.cardview.widget.CardView {
        val card = androidx.cardview.widget.CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        box.addView(TextView(this).apply { text = getString(R.string.rg_sujet_titre); textSize = 16f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply {
            text = getString(R.string.rg_sujet_desc)
            textSize = 12f; setTextColor(TEXTE_DOUX); setPadding(0, dp(4), 0, dp(10))
        })
        // Cases a cocher organisees par CATEGORIE, en 3 COLONNES pour scroller moins.
        for ((nomCategorie, classes) in Reglages.CLASSES_GROUPES) {
            box.addView(TextView(this).apply {
                text = getString(nomCategorie).uppercase()
                textSize = 11f; setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(2))
            })
            // grille 3 colonnes : on remplit ligne par ligne
            var ligne: LinearLayout? = null
            for ((i, paire) in classes.withIndex()) {
                if (i % 3 == 0) {
                    ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    box.addView(ligne)
                }
                val id = paire.first
                val cb = android.widget.CheckBox(this).apply {
                    text = getString(paire.second); textSize = 13f; setTextColor(TEXTE)
                    isChecked = reglages.getClassesPerso().contains(id)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { reglages.toggleClassePerso(id) }
                }
                ligne!!.addView(cb)
            }
            // completer la derniere ligne pour garder l'alignement des colonnes
            val reste = classes.size % 3
            if (reste != 0) {
                for (k in 0 until (3 - reste)) {
                    ligne!!.addView(android.view.View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                }
            }
        }
        card.addView(box)
        return card
    }

    private fun ligneParam(p: Reglages.Param): LinearLayout {
        val bloc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val haut = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        haut.addView(TextView(this).apply { text = getString(p.nomRes); textSize = 14f; setTextColor(TEXTE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        val valTxt = TextView(this).apply { text = "%.1f %s".format(reglages.get(p), p.unite); textSize = 14f; setTextColor(ACCENT); setTypeface(typeface, Typeface.BOLD) }
        haut.addView(valTxt); bloc.addView(haut)
        val nbCrans = ((p.max - p.min) / p.pas).toInt()
        val seek = SeekBar(this).apply {
            max = nbCrans
            progress = ((reglages.get(p) - p.min) / p.pas).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val v = p.min + prog * p.pas
                    valTxt.text = "%.1f %s".format(v, p.unite)
                    if (fromUser) reglages.set(p, v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        bloc.addView(seek)
        val bornes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bornes.addView(TextView(this).apply { text = "min %.1f".format(p.min); textSize = 11f; setTextColor(TEXTE_DOUX); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        bornes.addView(TextView(this).apply { text = "max %.1f".format(p.max); textSize = 11f; setTextColor(TEXTE_DOUX) })
        bloc.addView(bornes)
        if (p.aideRes != 0) {
            bloc.addView(TextView(this).apply {
                text = getString(p.aideRes); textSize = 12f; setTextColor(TEXTE_DOUX)
                setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(4), 0, dp(2))
            })
        }
        bloc.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(8) }
            setBackgroundColor(0xFFEFEFEF.toInt())
        })
        return bloc
    }
}

