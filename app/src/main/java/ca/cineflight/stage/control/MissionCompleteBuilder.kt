package ca.cineflight.stage.control

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import android.content.Context
import ca.cineflight.stage.R

/**
 * MissionCompleteBuilder — enrobe une reconnaissance (poses autour du sujet)
 * avec HOME / APPROCHE / RETOUR + vérification batterie.
 *
 * Approche B : le HOME est le VRAI point de décollage (position GPS réelle du
 * drone après décollage manuel), pas un point théorique. L'app appelle ce
 * builder au moment du lancement, une fois le Home connu.
 *
 * Mission complète :
 *   1. Home (décollage réel)
 *   2. Montée altitude transit -> APPROCHE vers 1er waypoint reco
 *   3. RECONNAISSANCE (poses autour du sujet)
 *   4. RETOUR : dernier waypoint -> Home
 *   5. Atterrissage (manuel ou RTH au Home)
 *
 * Sécurité : si distance totale > portée utile sûre -> REFUS avant lancement.
 */
object MissionCompleteBuilder {

    // Paramètres sécurité (DJI Mini 3)
    const val ALT_TRANSIT_AGL_M = 60.0
    const val VITESSE_TRANSIT_MPS = 6.0    // ALIGNE sur serveur (cine_reconnaissance.py) : vol reco reel a 6 m/s
    const val AUTONOMIE_S = 1500.0          // ~25 min utile
    const val MARGE_BATTERIE = 0.70         // n'utiliser que 70% (retour sûr)
    val PORTEE_UTILE_M = VITESSE_TRANSIT_MPS * AUTONOMIE_S * MARGE_BATTERIE  // ~6300 m a 6 m/s

    data class WaypointMission(
        val lat: Double, val lon: Double, val heightAglM: Double,
        val phase: String,                  // "approche" | "reconnaissance" | "retour"
        val capDeg: Double = 0.0,
        val pitchDeg: Double = 0.0,
        val prendrePhoto: Boolean = false
    )

    data class MissionComplete(
        val realisable: Boolean,
        val homeLat: Double, val homeLon: Double,
        val waypoints: List<WaypointMission> = emptyList(),
        val distApprocheM: Double = 0.0,
        val distRecoM: Double = 0.0,
        val distRetourM: Double = 0.0,
        val distTotaleM: Double = 0.0,
        val porteeUtileM: Double = PORTEE_UTILE_M,
        val dureeEstimeeS: Double = 0.0,
        val avertissements: List<String> = emptyList(),
        val raisonEchec: String = ""
    )

    private fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val mLat = 111320.0
        val mLon = 111320.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
        return hypot((lat2 - lat1) * mLat, (lon2 - lon1) * mLon)
    }

    private fun capDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val mLat = 111320.0
        val mLon = 111320.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
        val dEst = (lon2 - lon1) * mLon
        val dNord = (lat2 - lat1) * mLat
        return (Math.toDegrees(atan2(dEst, dNord)) + 360.0) % 360.0
    }

    /**
     * Construit la mission complète.
     * @param homeLat/homeLon : position GPS réelle du drone (Home, après décollage).
     * @param posesReco : poses de reconnaissance (lat, lon, heightAglM, cap, pitch).
     */
    fun construire(
        ctx: Context,
        homeLat: Double, homeLon: Double,
        posesReco: List<WaypointMission>,
        altTransitM: Double = ALT_TRANSIT_AGL_M
    ): MissionComplete {
        if (posesReco.isEmpty()) {
            return MissionComplete(false, homeLat, homeLon,
                raisonEchec = ctx.getString(R.string.mcb_no_pose))
        }

        val p0 = posesReco.first()
        val pN = posesReco.last()
        val wps = ArrayList<WaypointMission>()
        val avert = ArrayList<String>()

        // APPROCHE : Home -> premier waypoint, à altitude de transit
        val capApp = capDeg(homeLat, homeLon, p0.lat, p0.lon)
        wps.add(WaypointMission(homeLat, homeLon, altTransitM, "approche", capApp))
        wps.add(WaypointMission(p0.lat, p0.lon, altTransitM, "approche", capApp))
        val distApproche = distM(homeLat, homeLon, p0.lat, p0.lon)

        // RECONNAISSANCE : les poses
        var distReco = 0.0
        var latPrec = p0.lat; var lonPrec = p0.lon
        for (p in posesReco) {
            wps.add(WaypointMission(p.lat, p.lon, p.heightAglM, "reconnaissance",
                p.capDeg, p.pitchDeg, true))
            distReco += distM(latPrec, lonPrec, p.lat, p.lon)
            latPrec = p.lat; lonPrec = p.lon
        }

        // RETOUR : dernier waypoint -> Home
        val capRet = capDeg(pN.lat, pN.lon, homeLat, homeLon)
        wps.add(WaypointMission(pN.lat, pN.lon, altTransitM, "retour", capRet))
        wps.add(WaypointMission(homeLat, homeLon, altTransitM, "retour", capRet))
        val distRetour = distM(pN.lat, pN.lon, homeLat, homeLon)

        val distTotale = distApproche + distReco + distRetour
        val duree = distTotale / VITESSE_TRANSIT_MPS + posesReco.size * 3.0

        // VÉRIFICATION AUTONOMIE — refus si trop long
        if (distTotale > PORTEE_UTILE_M) {
            return MissionComplete(false, homeLat, homeLon, wps,
                distApproche, distReco, distRetour, distTotale, PORTEE_UTILE_M, duree,
                avert,
                ctx.getString(R.string.mcb_trop_long, distTotale.toInt(), PORTEE_UTILE_M.toInt()))
        }

        if (distTotale > PORTEE_UTILE_M * 0.8)
            avert.add(ctx.getString(R.string.mcb_proche, distTotale.toInt(), PORTEE_UTILE_M.toInt()))
        if (distApproche > 500)
            avert.add(ctx.getString(R.string.mcb_approche_longue, distApproche.toInt()))

        return MissionComplete(true, homeLat, homeLon, wps,
            distApproche, distReco, distRetour, distTotale, PORTEE_UTILE_M, duree, avert)
    }

    /** Résumé lisible pour le dialogue de confirmation pilote. */
    fun resume(ctx: Context, m: MissionComplete): String {
        if (!m.realisable) return ctx.getString(R.string.mcb_refusee, m.raisonEchec)
        val sb = StringBuilder()
        sb.append(ctx.getString(R.string.mcb_approche, m.distApprocheM.toInt()))
        sb.append(ctx.getString(R.string.mcb_reco, m.distRecoM.toInt()))
        sb.append(ctx.getString(R.string.mcb_retour, m.distRetourM.toInt()))
        sb.append(ctx.getString(R.string.mcb_distance, m.distTotaleM.toInt(), m.porteeUtileM.toInt()))
        sb.append(ctx.getString(R.string.mcb_duree, (m.dureeEstimeeS / 60).toInt()))
        for (a in m.avertissements) sb.append("\u26A0 $a\n")
        return sb.toString()
    }
}

