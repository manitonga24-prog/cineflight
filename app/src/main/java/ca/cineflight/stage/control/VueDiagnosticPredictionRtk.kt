package ca.cineflight.stage.control

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Vue Android du diagnostic RTK.
 *
 * La methode mettreAJour est sure meme si la boucle RTK tourne hors du thread
 * principal: toute modification de l'interface est automatiquement redirigee
 * vers le UI thread.
 */
class VueDiagnosticPredictionRtk(
    private val activity: Activity,
    private val carte: MapView?
) {
    private lateinit var panneau: TextView
    // AUDIT-DEBUG-MENU-2026-07 : le panneau de diagnostic RTK est masque par
    // defaut (page principale propre). Une pastille bascule l'affiche a la demande.
    private lateinit var boutonBascule: TextView
    @Volatile private var panneauVisible = false
    private var marqueurActuel: Marker? = null
    private var marqueurPredit: Marker? = null
    private var lignePrediction: Polyline? = null
    @Volatile private var fermee = false
    private val handlerUi = Handler(Looper.getMainLooper())
    private val watchdog = Runnable {
        if (fermee) return@Runnable
        panneau.text = "PREDICTION BLOQUEE  [SILENCE_RTK]\nAucune mise a jour RTK recente"
        panneau.background = fond(0xDD202124.toInt(), 0xFFE53935.toInt())
        marqueurPredit?.setEnabled(false)
        lignePrediction?.setEnabled(false)
        lignePrediction?.setPoints(emptyList())
        carte?.invalidate()
    }

    init {
        val racine = activity.findViewById<FrameLayout>(android.R.id.content)
        panneau = TextView(activity).apply {
            text = "PREDICTION : attente RTK"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = fond(0xDD202124.toInt(), 0xFF757575.toInt())
            // AUDIT-DEBUG-MENU-2026-07 : masque par defaut. La logique de mise a
            // jour continue de tourner ; seule la visibilite est controlee ici.
            visibility = android.view.View.GONE
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(58)
            rightMargin = dp(10)
        }
        racine?.addView(panneau, params)

        // AUDIT-DEBUG-MENU-2026-07 : petite pastille « 🐞 » toujours visible en haut
        // a droite. Un tap affiche/masque le panneau de diagnostic RTK complet.
        boutonBascule = TextView(activity).apply {
            text = "🐞"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = fond(0xCC202124.toInt(), 0xFF555B62.toInt())
            val s = dp(34)
            minWidth = s; minHeight = s
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { basculerPanneau() }
        }
        val paramsBtn = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(16)
            rightMargin = dp(10)
        }
        // 🐞 MASQUE : cette pastille de diagnostic (debug) recouvrait le menu
        // du haut a droite. Retiree pour l'utilisateur normal. Le suivi et les
        // reperes carte continuent de fonctionner ; seul ce bouton n'est plus affiche.
        // racine?.addView(boutonBascule, paramsBtn)

        initialiserCarte()
    }

    /** Affiche ou masque le panneau de diagnostic (page propre par defaut). */
    private fun basculerPanneau() {
        panneauVisible = !panneauVisible
        panneau.visibility = if (panneauVisible) android.view.View.VISIBLE
                             else android.view.View.GONE
        // legere mise en evidence de la pastille quand le panneau est ouvert.
        boutonBascule.background = fond(
            0xCC202124.toInt(),
            if (panneauVisible) 0xFF43A047.toInt() else 0xFF555B62.toInt())
    }

    /** Acces public : ouvre/ferme le panneau de diagnostic (appele depuis le menu). */
    fun basculer() { executerUi { if (!fermee) basculerPanneau() } }

    fun mettreAJour(etat: DiagnosticPredictionRtk.EtatDiagnostic) {
        executerUi {
            if (fermee) return@executerUi
            handlerUi.removeCallbacks(watchdog)
            handlerUi.postDelayed(watchdog, 3_000L)
            panneau.text = etat.textePanneau()
            val bordure = when {
                etat.pret -> 0xFF43A047.toInt()
                etat.raison == "SAUT_POSITION" ||
                    etat.raison == "RTK_PERDU" ||
                    etat.raison == "POSITION_INVALIDE" -> 0xFFE53935.toInt()
                else -> 0xFFFFA000.toInt()
            }
            panneau.background = fond(0xDD202124.toInt(), bordure)
            mettreAJourCarte(etat)
        }
    }

    /** Retire proprement le panneau et les overlays, par exemple dans onDestroy. */
    fun fermer() {
        executerUi {
            if (fermee) return@executerUi
            fermee = true
            handlerUi.removeCallbacks(watchdog)
            (panneau.parent as? ViewGroup)?.removeView(panneau)
            // AUDIT-DEBUG-MENU-2026-07 : retirer aussi la pastille bascule.
            (boutonBascule.parent as? ViewGroup)?.removeView(boutonBascule)
            val c = carte
            if (c != null) {
                marqueurActuel?.let { c.overlays.remove(it) }
                marqueurPredit?.let { c.overlays.remove(it) }
                lignePrediction?.let { c.overlays.remove(it) }
                c.invalidate()
            }
            marqueurActuel = null
            marqueurPredit = null
            lignePrediction = null
        }
    }

    private fun executerUi(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            activity.runOnUiThread(action)
        }
    }

    private fun initialiserCarte() {
        val c = carte ?: return
        val actuel = Marker(c).apply {
            title = "Sujet RTK - position actuelle"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setEnabled(false)
        }
        val predit = Marker(c).apply {
            title = "Sujet RTK - position predite"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setEnabled(false)
        }
        val ligne = Polyline(c).apply {
            title = "Avance de prediction"
            outlinePaint.strokeWidth = 5f
            outlinePaint.color = 0xFF43A047.toInt()
            setEnabled(false)
        }
        marqueurActuel = actuel
        marqueurPredit = predit
        lignePrediction = ligne
        c.overlays.add(actuel)
        c.overlays.add(ligne)
        c.overlays.add(predit)
    }

    private fun mettreAJourCarte(etat: DiagnosticPredictionRtk.EtatDiagnostic) {
        val c = carte ?: return
        val lat = etat.latActuelle
        val lon = etat.lonActuelle
        val actuelleValide = lat != null && lon != null && lat.isFinite() && lon.isFinite()

        marqueurActuel?.setEnabled(actuelleValide)
        if (actuelleValide) {
            marqueurActuel?.position = GeoPoint(lat!!, lon!!)
            marqueurActuel?.snippet =
                "RTK ${etat.qualiteRtk} | age ${"%.2f".format(etat.ageS)} s | ${etat.statutMesure}"
        }

        val plat = etat.latPredite
        val plon = etat.lonPredite
        val prediteValide = etat.pret && plat != null && plon != null && plat.isFinite() && plon.isFinite()
        marqueurPredit?.setEnabled(prediteValide)
        lignePrediction?.setEnabled(prediteValide && actuelleValide)

        if (prediteValide) {
            marqueurPredit?.position = GeoPoint(plat!!, plon!!)
            marqueurPredit?.snippet =
                "${etat.modele.code} | horizon ${"%.2f".format(etat.horizonS)} s | " +
                    "${"%.2f".format(etat.distancePredictionM ?: 0.0)} m | " +
                    "incertitude ${"%.2f".format(etat.incertitudeM ?: 0.0)} m"
            if (actuelleValide) {
                lignePrediction?.setPoints(listOf(GeoPoint(lat!!, lon!!), GeoPoint(plat, plon)))
            }
        } else {
            lignePrediction?.setPoints(emptyList())
        }
        c.invalidate()
    }

    private fun fond(remplissage: Int, bordure: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(7).toFloat()
        setColor(remplissage)
        setStroke(dp(2), bordure)
    }

    private fun dp(valeur: Int): Int =
        (valeur * activity.resources.displayMetrics.density).toInt()
}
