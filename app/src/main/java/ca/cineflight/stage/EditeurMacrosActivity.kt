package ca.cineflight.stage

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import ca.cineflight.stage.control.Macros

class EditeurMacrosActivity : AppCompatActivity() {

    private lateinit var macros: Macros
    private var tagChoisi = 11
    private var actionChoisie = ""   // vide = aucun plan choisi (force le choix)
    private var champNom: android.widget.EditText? = null
    private var champDuree: android.widget.EditText? = null
    private val etapes = ArrayList<Macros.Etape>()
    private lateinit var listeEtapes: LinearLayout

    private val FOND = 0xFFF2F2F7.toInt()
    private val CARTE = 0xFFFFFFFF.toInt()
    private val ACCENT = 0xFF007AFF.toInt()
    private val ROUGE = 0xFFFF3B30.toInt()
    private val TEXTE = 0xFF1C1C1E.toInt()
    private val TEXTE_DOUX = 0xFF8E8E93.toInt()

    private val nomsActions by lazy { linkedMapOf(
        "statique" to getString(R.string.em_statique), "orbite" to getString(R.string.em_orbite), "travelling" to getString(R.string.em_travelling),
        "revelation" to getString(R.string.em_revelation), "approche" to getString(R.string.em_approche),
        "recul" to getString(R.string.em_recul), "poursuite" to getString(R.string.em_poursuite),
        "plan_gros" to getString(R.string.em_plan_gros), "plan_americain" to getString(R.string.em_plan_americain),
        "plan_pied" to getString(R.string.em_plan_pied), "plan_ensemble" to getString(R.string.em_plan_ensemble),
        "suivi" to getString(R.string.em_suivi), "pause" to getString(R.string.em_pause), "stop" to getString(R.string.em_stop)
    ) }

    // Duree suggeree (secondes) pour que chaque plan se fasse en entier. Ajustable ensuite.
    private val dureeSuggeree = mapOf(
        "statique" to 4f, "orbite" to 10f, "travelling" to 6f, "revelation" to 6f,
        "approche" to 5f, "recul" to 5f, "poursuite" to 8f,
        "plan_gros" to 3f, "plan_americain" to 3f, "plan_pied" to 3f, "plan_ensemble" to 4f,
        "suivi" to 8f, "pause" to 2f, "stop" to 1f
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        macros = Macros(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(FOND) }
        val racine = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(24)) }
        scroll.addView(racine)

        racine.addView(TextView(this).apply { text = getString(R.string.em_titre); textSize = 24f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
        racine.addView(TextView(this).apply { text = getString(R.string.em_soustitre); textSize = 13f; setTextColor(TEXTE_DOUX); setPadding(0, dp(2), 0, dp(14)) })

        // Selecteur : quelle macro (le numero = son declencheur)
        racine.addView(carte {
            it.addView(titreCarte(getString(R.string.em_quelle)))
            it.addView(corps(getString(R.string.em_expl)))
            val champTag = champTapable()
            champTag.text = getString(R.string.em_macro_n, tagChoisi)
            champTag.setOnClickListener {
                android.app.AlertDialog.Builder(this).setTitle(getString(R.string.em_choisir_macro))
                    .setItems((11..30).map { n -> getString(R.string.em_macro_num, n) }.toTypedArray()) { _, which ->
                        tagChoisi = 11 + which; champTag.text = getString(R.string.em_macro_n, tagChoisi); chargerExistant()
                    }.show()
            }
            it.addView(champTag)
        })

        // 1 - Nom
        racine.addView(carte {
            it.addView(titreCarte(getString(R.string.em_nom_seq)))
            val cn = EditText(this).apply {
                hint = getString(R.string.em_nom_hint); setText(""); setTextColor(TEXTE)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                filters = arrayOf(android.text.InputFilter.LengthFilter(24))
            }
            champNom = cn
            it.addView(cn)
        })

        // 2 - Les plans dans l'ordre
        racine.addView(carte {
            it.addView(titreCarte(getString(R.string.em_plans_ordre)))
            it.addView(corps(getString(R.string.em_plans_expl)))
            val cles = nomsActions.keys.toList()
            val champPlan = champTapable()
            champPlan.text = (nomsActions[actionChoisie] ?: getString(R.string.em_choisir_plan)) + "   ▾"
            champPlan.setOnClickListener {
                val labels = cles.map { c -> nomsActions[c] ?: c }.toTypedArray()
                android.app.AlertDialog.Builder(this).setTitle(getString(R.string.em_choisir_plan))
                    .setItems(labels) { _, which ->
                        actionChoisie = cles[which]; champPlan.text = labels[which] + "   ▾"
                        val d = dureeSuggeree[actionChoisie] ?: 4f
                        champDuree?.setText(if (d % 1f == 0f) d.toInt().toString() else d.toString())
                    }.show()
            }
            it.addView(champPlan)
            it.addView(corps(getString(R.string.em_duree_label)).apply { setPadding(0, dp(10), 0, dp(4)) })
            val cd = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(""); hint = getString(R.string.em_secondes); setTextColor(TEXTE) }
            champDuree = cd
            it.addView(cd)
            it.addView(bouton(getString(R.string.em_ajouter), ACCENT, Color.WHITE) {
                if (actionChoisie.isEmpty()) {
                    Toast.makeText(this, getString(R.string.em_choisis_dabord), Toast.LENGTH_SHORT).show()
                } else {
                    val duree = champDuree?.text?.toString()?.toFloatOrNull() ?: (dureeSuggeree[actionChoisie] ?: 4f)
                    etapes.add(Macros.Etape(actionChoisie, duree)); rafraichirListe()
                }
            })
            listeEtapes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
            it.addView(listeEtapes)
        })

        // Actions finales
        racine.addView(bouton(getString(R.string.em_enregistrer), ACCENT, Color.WHITE) {
            val nomSaisi = champNom?.text?.toString()?.trim().orEmpty().take(24)
            val nomFinal = if (nomSaisi.isNotEmpty()) nomSaisi else getString(R.string.em_macro_defaut, tagChoisi)
            macros.sauver(Macros.Macro(tagChoisi, nomFinal, etapes.toList()))
            Toast.makeText(this, getString(R.string.em_enregistree, tagChoisi), Toast.LENGTH_SHORT).show()
        })
        racine.addView(bouton(getString(R.string.em_vider), 0xFFE5E5EA.toInt(), TEXTE) { etapes.clear(); rafraichirListe() })
        racine.addView(bouton(getString(R.string.em_supprimer), 0xFFFFE5E3.toInt(), ROUGE) {
            macros.supprimer(tagChoisi); etapes.clear(); champNom?.setText(""); rafraichirListe()
            Toast.makeText(this, getString(R.string.em_supprimee, tagChoisi), Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
        chargerExistant()
    }

    private fun carte(remplir: (LinearLayout) -> Unit): CardView {
        val card = CardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = 0f; setCardBackgroundColor(CARTE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(12); layoutParams = lp
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        remplir(box); card.addView(box); return card
    }
    private fun titreCarte(t: String) = TextView(this).apply { text = t; textSize = 15f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(6)) }
    private fun corps(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(TEXTE_DOUX); setLineSpacing(dp(3).toFloat(), 1f) }
    private fun bouton(t: String, fond: Int, txt: Int, action: () -> Unit) = Button(this).apply {
        text = t; setTextColor(txt); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
        backgroundTintList = android.content.res.ColorStateList.valueOf(fond)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)); lp.topMargin = dp(8); layoutParams = lp
        setOnClickListener { action() }
    }
    private fun champTapable(): TextView = TextView(this).apply {
        textSize = 15f; setTextColor(TEXTE); setPadding(dp(12), dp(12), dp(12), dp(12))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat(); setColor(0xFFF2F2F7.toInt()); setStroke(dp(1).toInt(), 0xFFD1D1D6.toInt())
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(6); layoutParams = lp
        isClickable = true
    }

    private fun miniBouton(t: String, actif: Boolean, action: () -> Unit) = Button(this).apply {
        text = t; setTextColor(if (actif) ACCENT else 0xFFC7C7CC.toInt()); setTypeface(typeface, Typeface.BOLD); textSize = 16f
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFEFEFF4.toInt())
        val lp = LinearLayout.LayoutParams(dp(42), dp(42)); lp.leftMargin = dp(4); layoutParams = lp
        isEnabled = actif
        setOnClickListener { action() }
    }

    private fun adapterNoir(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent)
                (v as? android.widget.TextView)?.setTextColor(0xFF1C1C1E.toInt())
                return v
            }
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? android.widget.TextView)?.setTextColor(0xFF1C1C1E.toInt())
                (v as? android.widget.TextView)?.setBackgroundColor(0xFFFFFFFF.toInt())
                return v
            }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun chargerExistant() {
        etapes.clear()
        val m = macros.charger(tagChoisi)
        if (m != null) {
            etapes.addAll(m.etapes)
            champNom?.setText(if (m.nom.startsWith("Tag ") || m.nom.startsWith("Macro ")) "" else m.nom)
        } else {
            champNom?.setText("")
        }
        rafraichirListe()
    }

    private fun rafraichirListe() {
        listeEtapes.removeAllViews()
        if (etapes.isEmpty()) { listeEtapes.addView(corps("Aucun plan pour l'instant. Ajoute-en un ci-dessus.").apply { setPadding(0, dp(6), 0, dp(6)) }); return }
        etapes.forEachIndexed { i, e ->
            val card = CardView(this).apply {
                radius = dp(10).toFloat(); cardElevation = 0f; setCardBackgroundColor(0xFFF7F7FA.toInt())
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(8); layoutParams = lp
            }
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(6), dp(8)) }
            l.addView(TextView(this).apply { text = "${i + 1}."; textSize = 14f; setTextColor(TEXTE_DOUX); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, dp(8), 0) })
            l.addView(TextView(this).apply { text = nomsActions[e.action] ?: e.action; textSize = 14f; setTextColor(TEXTE); setTypeface(typeface, Typeface.BOLD) })
            l.addView(TextView(this).apply { text = "  ${e.attenteS}s"; textSize = 13f; setTextColor(TEXTE_DOUX); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            l.addView(miniBouton("↑", i > 0) { val t = etapes.removeAt(i); etapes.add(i - 1, t); rafraichirListe() })
            l.addView(miniBouton("↓", i < etapes.size - 1) { val t = etapes.removeAt(i); etapes.add(i + 1, t); rafraichirListe() })
            l.addView(Button(this).apply {
                text = "✕"; setTextColor(ROUGE); setTypeface(typeface, Typeface.BOLD); textSize = 14f
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFE5E3.toInt())
                val lp = LinearLayout.LayoutParams(dp(42), dp(42)); lp.leftMargin = dp(4); layoutParams = lp
                setOnClickListener { etapes.removeAt(i); rafraichirListe() }
            })
            card.addView(l); listeEtapes.addView(card)
        }
    }
}
