package ca.cineflight.stage.control

import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.cos

/**
 * CoucheSujetRtk — overlay carte du MODE SUJET MOBILE (osmdroid).
 *
 * Dessine et met a jour sur la MapView existante :
 *   - le marqueur SUJET (position RTK),
 *   - les CERCLES de securite 3 m (rouge) et 5 m (jaune) autour du sujet,
 *   - la LIGNE drone-sujet avec la distance,
 *   - la TRACE recente du sujet.
 *
 * Ne cree PAS de carte : on lui passe la MapView deja construite par
 * CarteMissionActivity / CarteActivity, et le marqueur drone existant si on veut
 * tracer la ligne. Aucune logique de securite ici (c'est SuiviSujetRtk qui decide) :
 * cette couche ne fait qu'AFFICHER l'EtatSujet fourni. Separation nette
 * affichage / decision -> testable, reutilisable dans les deux cartes.
 *
 * Couleurs coherentes avec la carte existante (trace drone rouge 0xFFFF1744).
 */
class CoucheSujetRtk(private val carte: MapView) {

    private var marqueurSujet: Marker? = null
    private var cercle3m: Polygon? = null
    private var cercle5m: Polygon? = null
    private var ligneDist: Polyline? = null
    private var traceSujet: Polyline? = null

    private val COUL_SUJET = 0xFF2196F3.toInt()      // bleu (sujet)
    private val COUL_DANGER = 0xFFF44336.toInt()     // rouge (3 m)
    private val COUL_PRUDENCE = 0xFFFFC107.toInt()   // jaune (5 m)
    private val COUL_TRACE = 0xFF64B5F6.toInt()      // bleu clair (trace sujet)

    /** Cree les overlays une seule fois et les ajoute a la carte. */
    private fun initialiser() {
        if (marqueurSujet != null) return

        cercle5m = Polygon(carte).apply {
            fillPaint.color = 0x22FFC107               // jaune tres translucide
            outlinePaint.color = COUL_PRUDENCE
            outlinePaint.strokeWidth = 3f
        }
        cercle3m = Polygon(carte).apply {
            fillPaint.color = 0x33F44336               // rouge translucide
            outlinePaint.color = COUL_DANGER
            outlinePaint.strokeWidth = 3f
        }
        traceSujet = Polyline().apply {
            outlinePaint.color = COUL_TRACE
            outlinePaint.strokeWidth = 4f
        }
        ligneDist = Polyline().apply {
            outlinePaint.color = COUL_SUJET
            outlinePaint.strokeWidth = 3f
        }
        marqueurSujet = Marker(carte).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Sujet RTK"
        }

        // ordre d'empilement : cercles dessous, puis trace/ligne, puis marqueur.
        carte.overlays.add(cercle5m)
        carte.overlays.add(cercle3m)
        carte.overlays.add(traceSujet)
        carte.overlays.add(ligneDist)
        carte.overlays.add(marqueurSujet)
    }

    /**
     * Met a jour l'affichage a partir de l'etat de suivi + position drone.
     * A appeler sur le thread UI (dans la boucle d'animation de la carte).
     *
     * @param etat resultat de SuiviSujetRtk.evaluer(...)
     * @param droneLat / droneLon position drone (pour la ligne), NaN si inconnue
     */
    fun majAffichage(
        etat: SuiviSujetRtk.EtatSujet,
        droneLat: Double,
        droneLon: Double
    ) {
        initialiser()

        // sujet absent / RTK perdu : on efface les overlays sujet (mais on garde
        // le marqueur cache) pour ne pas afficher une position perimee.
        if (!etat.sujetPresent || etat.sujetLat.isNaN() || etat.sujetLon.isNaN()) {
            marqueurSujet?.position = null
            cercle3m?.points = emptyList()
            cercle5m?.points = emptyList()
            ligneDist?.setPoints(emptyList())
            traceSujet?.setPoints(emptyList())
            carte.invalidate()
            return
        }

        val posSujet = GeoPoint(etat.sujetLat, etat.sujetLon)
        marqueurSujet?.position = posSujet
        // titre = badge RTK + age, visible au tap
        marqueurSujet?.title = "Sujet — ${etat.rtk}" +
            (etat.ageS?.let { " (%.1fs)".format(it) } ?: "")

        // cercles de securite (3 m rouge, 5 m jaune)
        cercle3m?.points = Polygon.pointsAsCircle(posSujet, 3.0)
        cercle5m?.points = Polygon.pointsAsCircle(posSujet, 5.0)

        // trace recente du sujet
        traceSujet?.setPoints(etat.trace.map { GeoPoint(it[0], it[1]) })

        // ligne drone-sujet (si position drone connue)
        if (!droneLat.isNaN() && !droneLon.isNaN()) {
            ligneDist?.setPoints(listOf(GeoPoint(droneLat, droneLon), posSujet))
            // couleur de la ligne selon la zone (danger = rouge)
            ligneDist?.outlinePaint?.color = when (etat.zone) {
                SuiviSujetRtk.ZoneDistance.DANGER -> COUL_DANGER
                SuiviSujetRtk.ZoneDistance.PRUDENCE -> COUL_PRUDENCE
                else -> COUL_SUJET
            }
        } else {
            ligneDist?.setPoints(emptyList())
        }

        carte.invalidate()
    }

    /** Retire tous les overlays sujet de la carte (a l'arret du mode). */
    fun retirer() {
        listOfNotNull(cercle3m, cercle5m, traceSujet, ligneDist, marqueurSujet)
            .forEach { carte.overlays.remove(it) }
        marqueurSujet = null; cercle3m = null; cercle5m = null
        ligneDist = null; traceSujet = null
        carte.invalidate()
    }
}

