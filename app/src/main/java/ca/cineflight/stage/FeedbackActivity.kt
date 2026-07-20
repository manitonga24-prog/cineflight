package ca.cineflight.stage

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * FeedbackActivity — formulaire de commentaire pour l'usager (bilingue).
 *
 * L'usager choisit un type (Bug / Idee / Question / Autre), ecrit son message
 * et, s'il le souhaite, laisse son email. Le bouton Envoyer ouvre l'application
 * mail du telephone PRE-REMPLIE (mailto:) vers l'adresse de destination, avec un
 * corps de courriel structure : le message puis un bloc "contexte technique"
 * (version app, telephone, Android, MODELE DU DRONE si connecte, POSITION si
 * disponible) qui rend chaque retour directement exploitable. Aucun serveur,
 * aucun mot de passe : l'usager voit ce qui part et appuie lui-meme sur Envoyer.
 *
 * i18n : tous les textes du formulaire passent par getString(R.string.fb_*)
 * (res/values = anglais, res/values-fr = francais). Le corps du courriel reste
 * en francais : c'est un rapport technique destine au developpeur.
 */
class FeedbackActivity : AppCompatActivity() {

    private val DEST = "manitonga24@gmail.com"

    private val FOND = 0xFFF2F2F7.toInt()
    private val BLANC = 0xFFFFFFFF.toInt()
    private val ACCENT = 0xFF007AFF.toInt()
    private val VERT = 0xFF34C759.toInt()
    private val TXT = 0xFF1C1C1E.toInt()
    private val DOUX = 0xFF8E8E93.toInt()
    private val GRIS = 0xFFE5E5EA.toInt()

    // cle = valeur ecrite dans le courriel (FR, cote developpeur) ; res = libelle affiche (localise)
    private data class TypeFb(val cle: String, val emoji: String, val res: Int)
    private val types by lazy {
        listOf(
            TypeFb("Bug", "🐞", R.string.fb_type_bug),
            TypeFb("Idee", "💡", R.string.fb_type_idea),
            TypeFb("Question", "❓", R.string.fb_type_question),
            TypeFb("Autre", "✏️", R.string.fb_type_other))
    }
    private var typeChoisi = "Bug"
    private val typeBtns = LinkedHashMap<String, Button>()
    private lateinit var champMessage: EditText
    private lateinit var champEmail: EditText

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FOND)
        }

        // --- Barre de titre ---
        racine.addView(LinearLayout(this).apply {
            setBackgroundColor(BLANC)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(TextView(this@FeedbackActivity).apply {
                text = getString(R.string.fb_titre)
                textSize = 18f; setTextColor(TXT)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.fb_intro)
            textSize = 14f; setTextColor(DOUX); setPadding(0, 0, 0, dp(14))
        })

        // --- Type de commentaire (2 rangees de 2) ---
        col.addView(label(getString(R.string.fb_type_label)))
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8); layoutParams = lp
        }
        types.forEachIndexed { i, t ->
            (if (i < 2) r1 else r2).addView(chip(t.emoji + "  " + getString(t.res), t.cle))
        }
        col.addView(r1); col.addView(r2)
        majTypes()

        // --- Message ---
        col.addView(label(getString(R.string.fb_message_label)))
        champMessage = EditText(this).apply {
            hint = getString(R.string.fb_message_hint)
            setHintTextColor(DOUX); setTextColor(TXT)
            gravity = Gravity.TOP or Gravity.START
            background = null
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minLines = 5; maxLines = 12
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        col.addView(carte(champMessage))

        // --- Email optionnel ---
        col.addView(label(getString(R.string.fb_email_label)))
        champEmail = EditText(this).apply {
            hint = getString(R.string.fb_email_hint)
            setHintTextColor(DOUX); setTextColor(TXT)
            background = null
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        col.addView(carte(champEmail))

        // --- Note contexte ---
        col.addView(TextView(this).apply {
            text = getString(R.string.fb_note)
            textSize = 12f; setTextColor(DOUX); setPadding(dp(2), dp(10), dp(2), dp(16))
        })

        // --- Bouton envoyer ---
        col.addView(Button(this).apply {
            text = getString(R.string.fb_send)
            isAllCaps = false; textSize = 16f; setTextColor(BLANC)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(VERT)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
            lp.topMargin = dp(4); layoutParams = lp
            setOnClickListener { envoyer() }
        })

        racine.addView(ScrollView(this).apply { addView(col) })
        setContentView(racine)
    }

    private fun label(t: String): TextView = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(ACCENT)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(2), dp(14), 0, dp(6))
    }

    private fun chip(lbl: String, cle: String): Button {
        val b = Button(this).apply {
            text = lbl; isAllCaps = false; textSize = 14f
            val lp = LinearLayout.LayoutParams(0, dp(46), 1f)
            lp.rightMargin = dp(8); layoutParams = lp
            setOnClickListener { typeChoisi = cle; majTypes() }
        }
        typeBtns[cle] = b
        return b
    }

    private fun majTypes() {
        for ((cle, b) in typeBtns) {
            val sel = cle == typeChoisi
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(if (sel) ACCENT else GRIS)
            b.setTextColor(if (sel) BLANC else TXT)
        }
    }

    private fun carte(vue: android.view.View): androidx.cardview.widget.CardView {
        return androidx.cardview.widget.CardView(this).apply {
            radius = dp(12).toFloat(); cardElevation = 0f; setCardBackgroundColor(BLANC)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6); layoutParams = lp
            addView(vue)
        }
    }

    private fun envoyer() {
        val message = champMessage.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(this, getString(R.string.fb_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEST))
            putExtra(Intent.EXTRA_SUBJECT, construireSujet(message))
            putExtra(Intent.EXTRA_TEXT, construireCorps(message))
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.fb_send)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.fb_no_mail), Toast.LENGTH_LONG).show()
        }
    }

    /** Modele du drone connecte, lu en best-effort via le SDK (jamais bloquant). */
    private fun modeleDrone(): String {
        return try {
            val km = dji.v5.manager.KeyManager.getInstance() ?: return "non connecte"
            val cle = dji.sdk.keyvalue.key.KeyTools.createKey(
                dji.sdk.keyvalue.key.ProductKey.KeyProductType)
            val mg = km.javaClass.methods.firstOrNull {
                it.name == "getValue" && it.parameterTypes.size == 1
            }
            val brut = mg?.invoke(km, cle)?.toString()
            if (brut.isNullOrBlank() || brut.contains("UNKNOWN", true)) "non connecte"
            else brut.removePrefix("DJI_").replace("_", " ")
        } catch (_: Throwable) { "non detecte" }
    }

    /** Derniere position connue du telephone (best-effort, sans demander de permission). */
    private fun dernierePosition(): android.location.Location? {
        return try {
            val ok = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED ||
                     checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!ok) return null
            val lm = getSystemService(android.content.Context.LOCATION_SERVICE)
                as android.location.LocationManager
            var best: android.location.Location? = null
            for (p in listOf(
                    android.location.LocationManager.GPS_PROVIDER,
                    android.location.LocationManager.NETWORK_PROVIDER,
                    android.location.LocationManager.PASSIVE_PROVIDER)) {
                val loc = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
                if (loc != null && (best == null || loc.time > best!!.time)) best = loc
            }
            best
        } catch (_: Exception) { null }
    }

    private fun construireSujet(message: String): String {
        val extrait = message.replace("\n", " ").trim().take(60)
        return "[CineFlight Solo] $typeChoisi — $extrait"
    }

    private fun construireCorps(message: String): String {
        val pi = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val version = pi?.versionName ?: "?"
        val build = when {
            pi == null -> "?"
            Build.VERSION.SDK_INT >= 28 -> pi.longVersionCode.toString()
            else -> @Suppress("DEPRECATION") pi.versionCode.toString()
        }
        val lang = try { LangueManager.langueActuelle(this) } catch (_: Exception) { "fr" }
        val email = champEmail.text.toString().trim()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val sep = "─".repeat(34)

        val sb = StringBuilder()
        sb.append(message).append("\n\n")
        sb.append(sep).append("\n")
        sb.append("CONTEXTE TECHNIQUE (joint automatiquement)\n")
        sb.append("• Type          : ").append(typeChoisi).append("\n")
        sb.append("• Application   : CineFlight Solo v").append(version)
          .append(" (build ").append(build).append(")\n")
        sb.append("• Telephone     : ").append(Build.MANUFACTURER).append(" ")
          .append(Build.MODEL).append("\n")
        sb.append("• Android       : ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("• Drone         : ").append(modeleDrone()).append("\n")
        sb.append("• Langue appli  : ").append(lang).append("\n")
        sb.append("• Date          : ").append(date).append("\n")
        val pos = dernierePosition()
        if (pos != null) {
            sb.append("• Position      : ")
              .append(String.format(java.util.Locale.US, "%.5f, %.5f", pos.latitude, pos.longitude))
              .append(String.format(java.util.Locale.US, "  (±%.0f m, il y a %d min)",
                  pos.accuracy, (System.currentTimeMillis() - pos.time) / 60000)).append("\n")
            sb.append("• Carte         : https://maps.google.com/?q=")
              .append(String.format(java.util.Locale.US, "%.5f,%.5f", pos.latitude, pos.longitude)).append("\n")
        } else {
            sb.append("• Position      : non disponible\n")
        }
        if (email.isNotEmpty())
            sb.append("• Repondre a    : ").append(email).append("\n")
        sb.append(sep).append("\n")
        sb.append("Envoye depuis CineFlight Solo")
        return sb.toString()
    }
}
