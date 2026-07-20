package ca.cineflight.stage

import android.content.Context
import android.os.Bundle
import android.print.PrintManager
import android.view.Gravity
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * GuideActivity — affiche le guide utilisateur (WebView sur un asset HTML).
 *
 * ECRAN DE REFERENCE i18n : tous les textes visibles passent par
 * getString(R.string.*) (voir res/values/ = anglais, res/values-fr/ = francais).
 * Un bouton globe permet de changer la langue de toute l'app (LangueManager).
 * Le guide lui-meme est choisi selon la langue : guide-<lang>.html si present,
 * sinon guide.html (francais, toujours present).
 */
class GuideActivity : AppCompatActivity() {

    private val ACCENT = 0xFF007AFF.toInt()
    private val FOND = 0xFFF2F2F7.toInt()
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FOND)
        }

        val barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        barre.addView(TextView(this).apply {
            text = intent.getStringExtra("titre") ?: getString(R.string.guide_titre)
            textSize = 18f
            setTextColor(0xFF1C1C1E.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // Bouton LANGUE (globe) : change la langue de TOUTE l'app.
        barre.addView(Button(this).apply {
            text = "🌐"
            textSize = 16f
            setTextColor(0xFF1C1C1E.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE5E5EA.toInt())
            setOnClickListener { LangueManager.choisir(this@GuideActivity) }
        })
        barre.addView(Button(this).apply {
            text = getString(R.string.guide_imprimer)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setOnClickListener { imprimerGuide() }
        })
        racine.addView(barre)

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
                    val u = req.url
                    if (u.scheme == "http" || u.scheme == "https") {
                        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                        return true
                    }
                    return false
                }
            }
            loadUrl("file:///android_asset/${fichierGuide()}")
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        webView = wv
        racine.addView(wv)

        setContentView(racine)
    }

    /** Choisit le guide selon la langue : guide-<lang>.html si present, sinon guide.html (FR). */
    private fun fichierGuide(): String {
        val base = intent.getStringExtra("asset") ?: "guide"
        val lang = LangueManager.langueActuelle(this)
        val candidat = "$base-$lang.html"
        return if (assetExiste(candidat)) candidat else "$base.html"
    }

    private fun assetExiste(nom: String): Boolean =
        try { assets.open(nom).close(); true } catch (_: Exception) { false }

    private fun imprimerGuide() {
        val wv = webView ?: return
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = wv.createPrintDocumentAdapter("CineFlight_Solo_Guide")
        printManager.print("CineFlight Solo - Guide", adapter, android.print.PrintAttributes.Builder().build())
    }
}
