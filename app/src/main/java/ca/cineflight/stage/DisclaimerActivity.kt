package ca.cineflight.stage

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class DisclaimerActivity : AppCompatActivity() {

    private val ACCENT = 0xFF007AFF.toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = true
            settings.domStorageEnabled = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        try {
            wv.loadUrl("file:///android_asset/disclaimer.html")
        } catch (e: Throwable) {
            wv.loadData("<html><body style='padding:20px;font-family:sans-serif'><h2>Avis de responsabilite</h2><p>Le pilote est seul responsable du pilotage de son drone, de la securite des personnes et des biens, et du respect des lois.</p></body></html>", "text/html", "UTF-8")
        }
        racine.addView(wv)

        // bas : case J'accepte + bouton
        val bas = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
            setBackgroundColor(0xFFF2F2F7.toInt())
        }
        val check = CheckBox(this).apply {
            text = getString(R.string.disc_accepte)
            textSize = 14f
            setTextColor(0xFF1C1C1E.toInt())
        }
        bas.addView(check)

        val btn = Button(this).apply {
            text = getString(R.string.disc_continuer)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isEnabled = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFB0B0B0.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)); lp.topMargin = dp(10); layoutParams = lp
            setOnClickListener {
                getSharedPreferences("cineflight", Context.MODE_PRIVATE).edit()
                    .putBoolean("disclaimer_accepte", true).apply()
                startActivity(Intent(this@DisclaimerActivity, MainActivity::class.java))
                finish()
            }
        }
        check.setOnCheckedChangeListener { _, coche ->
            btn.isEnabled = coche
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (coche) ACCENT else 0xFFB0B0B0.toInt())
        }
        bas.addView(btn)
        racine.addView(bas)

        setContentView(racine)
    }

    companion object {
        fun dejaAccepte(ctx: Context): Boolean =
            ctx.getSharedPreferences("cineflight", Context.MODE_PRIVATE).getBoolean("disclaimer_accepte", false)
    }
}
