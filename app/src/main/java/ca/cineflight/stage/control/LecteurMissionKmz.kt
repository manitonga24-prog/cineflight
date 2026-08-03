package ca.cineflight.stage.control

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * LecteurMissionKmz — joue une mission KMZ en VirtualStick, waypoint par waypoint.
 *
 * PHILOSOPHIE SECURITE :
 *  - Reutilise PiloteDrone (bornage vitesse + timeout hover deja en place).
 *  - L'utilisateur GARDE LE CONTROLE : pause/stop a tout moment ; si les sticks
 *    RC envoient une commande, elle est plus recente et reprend la priorite.
 *  - Vitesses MODEREES. Le Mini 3 n'a pas d'evitement d'obstacles.
 *  - A TESTER EN SIMULATEUR (PontDjiSimuleCockpit) avant tout vol reel.
 *
 * Le sequenceur lit la position courante du drone (lat/lon/alt via le pont),
 * calcule la direction vers le waypoint courant, et produit des CommandeBridge
 * que PiloteDrone execute. Quand le drone est proche du waypoint, il passe au
 * suivant (apres le hover eventuel).
 */
class LecteurMissionKmz(
    private val pilote: PiloteDrone,
    private val pont: PiloteDrone.PontDji,
    private val onProgression: (indice: Int, total: Int, etat: String) -> Unit = { _, _, _ -> },
    private val onTermine: () -> Unit = {}
) {
    data class WpMission(
        val lat: Double, val lon: Double, val altAgl: Double,
        val gimbalPitch: Float, val gimbalYaw: Float, val hoverSec: Float,
        val vitesse: Float, val type: String = ""
    )

    @Volatile private var enCours = false
    @Volatile private var enPause = false
    private var boucle: Job? = null
    private var waypoints: List<WpMission> = emptyList()
    private var dernierAffichageMs = 0L   // throttle de l'affichage (texte lisible)

    // Parametres de navigation (prudents)
    private val RAYON_ATTEINT_M = 2.5       // waypoint considere atteint
    private val TOL_ALT_M = 2.5
    private val VITESSE_MAX = 8.0           // m/s plafond (transits rapides ; les plans cine gardent leur vitesse douce via wp.vitesse)
    private val VITESSE_APPROCHE = 2.0      // ralentit pres du waypoint
    private val DIST_RALENTISSEMENT_M = 10.0

    val actif: Boolean get() = enCours

    /** Liste des points (lat, lon) du parcours, pour tracer la trajectoire sur une carte. */
    fun pointsParcours(): List<Pair<Double, Double>> = waypoints.map { it.lat to it.lon }
    /** Waypoints complets (avec type, altitude, vitesse) pour le rapport detaille. */
    fun lesWaypoints(): List<WpMission> = waypoints

    companion object {
        /** Lit le 1er waypoint (lat, lon) d'un KMZ, sans instancier le lecteur.
         *  Sert a positionner le drone simule au depart de la mission. null si illisible. */
        fun premierPoint(kmz: File): Pair<Double, Double>? {
            return try {
                java.util.zip.ZipInputStream(kmz.inputStream()).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        if (e.name.endsWith("waylines.wpml")) {
                            val xml = zip.readBytes().toString(Charsets.UTF_8)
                            val m = Regex("<(?:\\w+:)?coordinates>\\s*([-0-9.]+),([-0-9.]+)").find(xml)
                            if (m != null) {
                                val lon = m.groupValues[1].toDoubleOrNull()
                                val lat = m.groupValues[2].toDoubleOrNull()
                                if (lat != null && lon != null) return lat to lon
                            }
                        }
                        e = zip.nextEntry
                    }
                    null
                }
            } catch (ex: Exception) { null }
        }

        /** Lit TOUS les points (lat, lon) d'un KMZ, sans instancier le lecteur.
         *  Sert a tracer la trajectoire complete sur une carte. */
        fun pointsKmz(kmz: File): List<Pair<Double, Double>> {
            val out = ArrayList<Pair<Double, Double>>()
            try {
                java.util.zip.ZipInputStream(kmz.inputStream()).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        if (e.name.endsWith("waylines.wpml")) {
                            val xml = zip.readBytes().toString(Charsets.UTF_8)
                            Regex("<(?:\\w+:)?coordinates>\\s*([-0-9.]+),([-0-9.]+)").findAll(xml).forEach { m ->
                                val lon = m.groupValues[1].toDoubleOrNull()
                                val lat = m.groupValues[2].toDoubleOrNull()
                                if (lat != null && lon != null) out.add(lat to lon)
                            }
                        }
                        e = zip.nextEntry
                    }
                }
            } catch (_: Exception) {}
            return out
        }
    }

    /** Charge une mission depuis un fichier KMZ. Retourne le nb de waypoints. */
    fun charger(kmz: File): Int {
        // Le KMZ est genere en mode 'relativeToStartPoint' : executeHeight EST
        // deja l'altitude AGL (hauteur au-dessus du point de decollage). On la lit
        // directement, aucune conversion necessaire.
        waypoints = parserKmz(kmz)
        return waypoints.size
    }

    /** Lance l'execution de la mission (le drone DOIT deja etre en vol/hover). */
    fun lancer(scope: CoroutineScope) {
        if (enCours || waypoints.isEmpty()) return
        enCours = true
        enPause = false
        // CAPTURE VIDÉO : démarre l'enregistrement quand la mission part (le drone
        // est déjà en vol/hover). Garde-fou : seulement si le pont est un cockpit.
        (pont as? PontCockpit)?.demarrerEnregistrement()
        boucle = scope.launch(Dispatchers.Default) {
            var i = 0
            while (isActive && enCours && i < waypoints.size) {
                if (enPause) { hover(); delay(200); continue }
                val wp = waypoints[i]
                // timeout de securite par waypoint, PROPORTIONNEL a la distance a
                // parcourir (un long transit a vitesse lente prend du temps). On laisse
                // le temps theorique + large marge, puis on passe pour ne jamais bloquer.
                val distInit = run {
                    val la = pont.latitudeDrone(); val lo = pont.longitudeDrone()
                    if (la.isNaN() || lo.isNaN()) 0.0
                    else {
                        val mLat = 111_320.0
                        val mLon = 111_320.0 * cos(Math.toRadians(la))
                        hypot((wp.lat - la) * mLat, (wp.lon - lo) * mLon)
                    }
                }
                val vEstimee = (if (wp.vitesse > 0.5f) wp.vitesse.toDouble() else 2.0)
                // temps theorique = dist/vitesse, x3 de marge, plancher 15 s, plafond 180 s
                val timeoutWpMs = ((distInit / vEstimee) * 3000.0).toLong()
                    .coerceIn(15_000L, 180_000L)
                val tDebutWp = System.currentTimeMillis()
                var arrive = false
                while (isActive && enCours && !enPause && !arrive) {
                    arrive = naviguerVers(wp, i)
                    if (arrive) break
                    if (System.currentTimeMillis() - tDebutWp > timeoutWpMs) break  // anti-blocage
                    delay(66)   // ~15 Hz
                }
                // hover sur place le temps demande, gimbal oriente
                onProgression(i + 1, waypoints.size, "waypoint ${i + 1} atteint")
                val tHover = (wp.hoverSec * 1000).toLong().coerceAtLeast(300L)
                val t0 = System.currentTimeMillis()
                while (isActive && enCours && !enPause &&
                       System.currentTimeMillis() - t0 < tHover) {
                    soumettreCadrage(wp)   // hover + gimbal maintenu
                    delay(100)
                }
                i++
            }
            // ATTERRISSAGE FINAL : descente verticale jusqu'au sol (~0 m) au point
            // courant. Sinon le drone resterait en l'air a l'altitude du dernier wp.
            descendreAuSol()
            hover()
            enCours = false
            // CAPTURE VIDÉO : arrête l'enregistrement à la fin normale de la mission.
            (pont as? PontCockpit)?.arreterEnregistrement()
            onProgression(waypoints.size, waypoints.size, "mission terminee")
            onTermine()
        }
    }

    /** Descend verticalement jusqu'au sol (altitude ~0) au point courant, puis pose. */
    private suspend fun descendreAuSol() {
        val tDebut = System.currentTimeMillis()
        while (enCours) {
            val alt = pont.altitudeDrone()
            if (alt.isNaN() || alt <= 0.5) break          // pose
            if (System.currentTimeMillis() - tDebut > 30_000L) break  // securite
            // commande : pas de deplacement horizontal, descente ferme (-2 m/s)
            val cmd = RecepteurBridge.CommandeBridge(
                t = System.currentTimeMillis() / 1000.0,
                vx = 0f, vy = (-2.0).toFloat(), vz = 0f, yawRate = 0f,
                mode = "actif", recuA = System.currentTimeMillis(),
                gimbalPitch = 0f, gimbalYaw = 0f, cadrage = false, rec = false
            )
            pilote.soumettre(cmd)
            onProgression(waypoints.size, waypoints.size, "atterrissage : ${alt.toInt()}m")
            delay(100)
        }
        // pose finale : on coupe les vitesses (le drone est au sol)
        hover()
    }

    fun pause() { enPause = true }
    fun reprendre() { enPause = false }
    fun arreter() {
        enCours = false
        boucle?.cancel()
        hover()
        // CAPTURE VIDÉO : arrête l'enregistrement si la mission est interrompue
        // (annulation, RTH) — ne jamais laisser l'enregistrement tourner.
        (pont as? PontCockpit)?.arreterEnregistrement()
        onProgression(0, waypoints.size, "mission arretee")
    }

    /** Produit une commande de vol vers le waypoint. Retourne true si atteint.
     *  ATTEINTE = proche HORIZONTALEMENT (le critere de suivi). L'altitude est
     *  geree en parallele (montee/descente) mais ne BLOQUE PAS l'atteinte : sinon
     *  une cible d'altitude jamais atteinte exactement bloquerait la mission. */
    private fun naviguerVers(wp: WpMission, indice: Int): Boolean {
        val lat = pont.latitudeDrone()
        val lon = pont.longitudeDrone()
        val alt = pont.altitudeDrone()
        if (lat.isNaN() || lon.isNaN()) { hover(); return false }

        // distance horizontale (m) vers le waypoint
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(lat))
        val dN = (wp.lat - lat) * mLat       // nord (+)
        val dE = (wp.lon - lon) * mLon       // est (+)
        val distH = hypot(dN, dE)
        val dAlt = wp.altAgl - alt

        // ATTEINTE = proche horizontalement ET altitude proche (tolerance large).
        // Le timeout dans la boucle 'lancer' garantit qu'on ne bloque jamais si
        // l'altitude converge lentement.
        if (distH <= RAYON_ATTEINT_M && kotlin.math.abs(dAlt) <= 5.0) {
            return true
        }

        // Vitesse horizontale adaptative :
        //  - LOIN (> zone de filmage) : vitesse de CROISIERE rapide (transit), peu
        //    importe la vitesse fine du waypoint -> les longs deplacements sont rapides.
        //  - PROCHE (< zone de filmage) : on respecte la vitesse du waypoint (lente
        //    pour le cine), puis on ralentit encore tout pres pour se poser dessus.
        val ZONE_FILMAGE_M = 30.0
        val vitesseCible = when {
            distH < DIST_RALENTISSEMENT_M -> VITESSE_APPROCHE
            distH < ZONE_FILMAGE_M -> minOf(wp.vitesse.toDouble(), VITESSE_MAX)
            else -> VITESSE_MAX   // croisiere : on fonce vers le waypoint
        }
        val norme = if (distH > 0.01) distH else 1.0
        val vNord = (dN / norme) * vitesseCible
        val vEst = (dE / norme) * vitesseCible
        // vitesse verticale : ferme (3 m/s) tant qu'on est loin de l'altitude, douce a l'approche
        val vVert = when {
            kotlin.math.abs(dAlt) > 5.0 -> if (dAlt > 0) 3.0 else -3.0
            else -> dAlt * 0.6
        }.coerceIn(-3.0, 3.0)

        // CONVENTION TraductionAxes (verifiee) : vx=Est, vz=Nord, vy=vertical.
        val cmd = RecepteurBridge.CommandeBridge(
            t = System.currentTimeMillis() / 1000.0,
            vx = vEst.toFloat(),
            vy = vVert.toFloat(),
            vz = vNord.toFloat(),
            yawRate = 0f,
            mode = "actif",
            recuA = System.currentTimeMillis(),
            gimbalPitch = wp.gimbalPitch,
            gimbalYaw = wp.gimbalYaw,
            cadrage = true,
            rec = true               // enregistre pendant la mission
        )
        pilote.soumettre(cmd)
        // Affichage ralenti (~1/s) pour rester lisible — le pilotage reste a 15 Hz.
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierAffichageMs > 800L) {
            dernierAffichageMs = maintenant
            onProgression(indice + 1, waypoints.size,
                "vers wp ${indice + 1} : ${distH.toInt()}m alt ${alt.toInt()}/${wp.altAgl.toInt()}m")
        }
        return false
    }

    private fun soumettreCadrage(wp: WpMission) {
        pilote.soumettre(RecepteurBridge.CommandeBridge(
            t = System.currentTimeMillis() / 1000.0,
            vx = 0f, vy = 0f, vz = 0f, yawRate = 0f,
            mode = "actif", recuA = System.currentTimeMillis(),
            gimbalPitch = wp.gimbalPitch, gimbalYaw = wp.gimbalYaw,
            cadrage = true, rec = true
        ))
    }

    private fun hover() {
        pilote.soumettre(RecepteurBridge.CommandeBridge(
            t = System.currentTimeMillis() / 1000.0,
            vx = 0f, vy = 0f, vz = 0f, yawRate = 0f,
            mode = "actif", recuA = System.currentTimeMillis()
        ))
    }

    // ── Parsing du KMZ (waylines.wpml dans le zip) ──
    private fun parserKmz(kmz: File): List<WpMission> {
        val xml = lireWaylinesWpml(kmz) ?: return emptyList()
        return parserWpml(xml)
    }

    private fun lireWaylinesWpml(kmz: File): String? {
        return try {
            ZipInputStream(kmz.inputStream()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name.endsWith("waylines.wpml") || e.name.endsWith("template.kml")) {
                        val contenu = zip.readBytes().toString(Charsets.UTF_8)
                        if (e.name.endsWith("waylines.wpml")) return contenu
                    }
                    e = zip.nextEntry
                }
                null
            }
        } catch (ex: Exception) { Log.e("LecteurMissionKmz", "lecture kmz", ex); null }
    }

    /** Extrait les Placemark du WPML : coordinates + executeHeight + gimbal. */
    private fun parserWpml(xml: String): List<WpMission> {
        val wps = ArrayList<WpMission>()
        // chaque <Placemark> ... </Placemark>
        val placemarks = Regex("<(?:\\w+:)?Placemark>(.*?)</(?:\\w+:)?Placemark>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toList()
        for (pm in placemarks) {
            // coordinates : "lon,lat"
            val coord = Regex("<(?:\\w+:)?coordinates>\\s*([-0-9.]+),([-0-9.]+)")
                .find(pm)?.groupValues ?: continue
            val lon = coord[1].toDoubleOrNull() ?: continue
            val lat = coord[2].toDoubleOrNull() ?: continue
            val alt = Regex("<wpml:executeHeight>([-0-9.]+)").find(pm)
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: 50.0
            val vit = Regex("<wpml:waypointSpeed>([-0-9.]+)").find(pm)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: 4f
            // gimbal pitch (depuis l'action gimbalRotate si presente)
            val gp = Regex("<wpml:gimbalPitchRotateAngle>([-0-9.]+)").find(pm)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: -45f
            val gy = Regex("<wpml:aircraftHeading>([-0-9.]+)").find(pm)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            // type de waypoint (decollage/transit/orbite/reveal/.../atterrissage)
            val type = Regex("<wpml:waypointName>([^<]*)</wpml:waypointName>").find(pm)
                ?.groupValues?.get(1) ?: ""
            wps.add(WpMission(lat, lon, alt, gp, gy, 0f, vit, type))
        }
        return wps
    }
}

