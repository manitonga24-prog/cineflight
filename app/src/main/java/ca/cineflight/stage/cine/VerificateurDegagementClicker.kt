package ca.cineflight.stage.cine

import ca.cineflight.stage.control.DemandeMouvement
import ca.cineflight.stage.control.RtkSujet
import ca.cineflight.stage.control.ValidationMouvement
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import android.content.Context
import ca.cineflight.stage.R

/**
 * Vérification des obstacles cartographiés avant une commande Clicker simulée.
 *
 * La carte ne prouve jamais qu'une trajectoire est libre. Elle permet seulement
 * de bloquer lorsqu'un obstacle connu est signalé ou lorsque la carte n'est pas
 * disponible. La décision est volontairement "fail closed".
 *
 * Cette classe n'a aucun accès au pilote, au SDK DJI ou aux moteurs.
 */
class VerificateurDegagementClicker(
    private val ctx: Context,
    private val analyseur: AnalyseurLieu
) {

    data class Verdict(
        val validation: ValidationMouvement,
        val rayonM: Int,
        val depuisCache: Boolean
    )

    private data class Cache(
        val lat: Double,
        val lon: Double,
        val rayonM: Int,
        val resultat: ResultatDegagement,
        val horodatageMs: Long
    )

    private var cache: Cache? = null

    suspend fun verifier(
        demande: DemandeMouvement,
        rtk: RtkSujet?,
        distanceVirtuelleM: Double
    ): Verdict {
        val mouvement = demande.movement
        val rayonM = rayonPourMouvement(mouvement, distanceVirtuelleM)

        val lat = rtk?.lat
        val lon = rtk?.lon

        if (
            rtk?.present != true ||
            lat == null ||
            lon == null ||
            !lat.isFinite() ||
            !lon.isFinite()
        ) {
            return Verdict(
                validation = bloque(
                    "BLOCKED_MAP_POSITION_UNKNOWN",
                    "La position du boîtier ne permet pas de vérifier la carte du secteur."
                ),
                rayonM = rayonM,
                depuisCache = false
            )
        }

        val resultatCache = cacheValide(lat, lon, rayonM)
        val resultat = resultatCache?.resultat ?: try {
            analyseur.verifierPoint(lat, lon, rayonM)
        } catch (_: Exception) {
            null
        }

        if (resultat == null) {
            return Verdict(
                validation = bloque(
                    "BLOCKED_MAP_UNAVAILABLE",
                    "La carte du secteur ne peut pas être consultée pour le moment."
                ),
                rayonM = rayonM,
                depuisCache = false
            )
        }

        if (resultatCache == null) {
            cache = Cache(
                lat = lat,
                lon = lon,
                rayonM = rayonM,
                resultat = resultat,
                horodatageMs = System.currentTimeMillis()
            )
        }

        val depuisCache = resultatCache != null

        val validation = when {
            !resultat.carteDisponible ->
                bloque(
                    "BLOCKED_MAP_UNAVAILABLE",
                    "La carte du secteur n'est pas disponible. Une zone non couverte n'est pas considérée comme dégagée."
                )

            resultat.etat == "obstacles" -> {
                val proches = resultat.obstacles
                    .sortedBy { it.distanceM }
                    .take(3)

                val detail = if (proches.isEmpty()) {
                    ctx.getString(R.string.vd_obstacle_zone)
                } else {
                    proches.joinToString(
                        prefix = ctx.getString(R.string.vd_signale_prefix),
                        separator = "; "
                    ) { obstacle ->
                        ctx.getString(R.string.vd_obstacle_item, obstacle.type, obstacle.distanceM)
                    }
                }

                bloque(
                    "BLOCKED_MAP_OBSTACLES",
                    detail
                )
            }

            resultat.etat == "carte_ok" ->
                ValidationMouvement(
                    autorise = true,
                    status = "MAP_KNOWN_OBSTACLES_CLEAR",
                    raison =
                        "Aucun obstacle cartographié n'a été signalé dans un rayon de $rayonM mètres. " +
                        "La carte n'est pas exhaustive et ne remplace pas l'observation du terrain."
                )

            else ->
                bloque(
                    "BLOCKED_MAP_UNAVAILABLE",
                    "Le dégagement ne peut pas être confirmé par la carte du secteur."
                )
        }

        return Verdict(
            validation = validation,
            rayonM = rayonM,
            depuisCache = depuisCache
        )
    }

    private fun cacheValide(
        lat: Double,
        lon: Double,
        rayonM: Int
    ): Cache? {
        val courant = cache ?: return null
        val ageMs = System.currentTimeMillis() - courant.horodatageMs

        if (ageMs !in 0..CACHE_VALIDITE_MS) return null
        if (courant.rayonM < rayonM) return null
        if (distanceMetres(courant.lat, courant.lon, lat, lon) > CACHE_DEPLACEMENT_MAX_M) {
            return null
        }

        return courant
    }

    private fun rayonPourMouvement(
        mouvement: String?,
        distanceVirtuelleM: Double
    ): Int {
        val margeM = when (mouvement) {
            "RAPPROCHE_SUJET",
            "RAPPROCHE_SUJET_PRONONCE" -> 8.0

            "ELOIGNEMENT_SUJET" -> 12.0
            "TRAVELLING_ARRIERE" -> 18.0
            "CHANGER_COTE" -> 15.0
            "ORBITE_LARGE" -> 15.0
            "REPRENDRE_SUIVI" -> 15.0
            else -> 10.0
        }

        return ceil(distanceVirtuelleM + margeM)
            .toInt()
            .coerceIn(RAYON_MIN_M, RAYON_MAX_M)
    }

    private fun bloque(status: String, raison: String) =
        ValidationMouvement(
            autorise = false,
            status = status,
            raison = raison
        )

    private fun distanceMetres(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val rayonTerreM = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a =
            sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(p1) * cos(p2) *
                sin(dLon / 2.0) * sin(dLon / 2.0)

        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return rayonTerreM * c
    }

    companion object {
        const val RAYON_MIN_M = 20
        const val RAYON_MAX_M = 60
        const val CACHE_VALIDITE_MS = 60_000L
        const val CACHE_DEPLACEMENT_MAX_M = 5.0
    }
}