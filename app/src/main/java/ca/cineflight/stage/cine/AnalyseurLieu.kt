package ca.cineflight.stage.cine

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AnalyseurLieu : client LEGER du serveur Explorer (techno "preparer le lieu").
 *
 * Solo n'embarque PAS le moteur Explorer (qui reste en Python sur le serveur).
 * Il envoie lat/lon/rayon a l'endpoint /api/recommandations et recoit des
 * recettes recommandees (deja calculees par le classificateur Explorer).
 *
 * DEPENDANCE RESEAU : l'analyse de lieu a besoin d'Internet (donnees OSM cote
 * serveur). disponible() permet de basculer proprement si pas de reseau.
 *
 * Le serveur ne pilote jamais : il SUGGERE des cles de recettes, qui passent
 * ensuite par Catalogue -> Grammaire -> Validateur, comme un choix utilisateur.
 */

/** Resultat de la verification de degagement d'un point (carte OSM).
 *  etat : "carte_ok" | "obstacles" | "carte_indisponible".
 *  "Une zone non couverte n'est jamais une zone sure" : carte_indisponible
 *  n'est PAS un feu vert. La checklist reste a valider sur place. */
data class ObstacleConnu(val type: String, val distanceM: Int)
data class ResultatDegagement(
    val etat: String,                 // carte_ok | obstacles | carte_indisponible
    val carteDisponible: Boolean,
    val obstacles: List<ObstacleConnu>,
    val message: String,
    val checklist: List<String>,
    val disclaimer: String
)

interface AnalyseurLieu {
    fun disponible(): Boolean
    /** Message de la derniere erreur (diagnostic), ou null si tout va bien. */
    val derniereErreur: String?
    /** Appelle le serveur. Renvoie null en cas d'echec (reseau, timeout...). */
    suspend fun analyser(lat: Double, lon: Double, rayonM: Int = 3000, profil: String? = null): RapportLieu?
    /** Geocode une adresse -> (lat, lon, label) du 1er resultat, ou null. */
    suspend fun geocoder(adresse: String): Triple<Double, Double, String>?
    /** Verifie le degagement d'un point (obstacles connus d'OSM + checklist).
     *  Renvoie null en cas d'echec reseau. */
    suspend fun verifierPoint(lat: Double, lon: Double, rayonM: Int = 40): ResultatDegagement?
    /** Telecharge la mission KMZ complete dans un fichier local. Renvoie le
     *  chemin du fichier ecrit, ou null en cas d'echec. */
    suspend fun telechargerMissionKmz(lat: Double, lon: Double, profil: String?, destination: java.io.File): java.io.File?
    /** Variante avec point de decollage explicite (GPS reel sur place). */
    suspend fun telechargerMissionKmzAvecDepart(lat: Double, lon: Double, profil: String?,
        departLat: Double?, departLon: Double?, destination: java.io.File): java.io.File?
    /** Genere un plan souvenir (personnes) via /api/souvenir. departLat/lon =
     *  point de decollage A L'ECART du groupe (obligatoire si pilote dans la
     *  scene). Renvoie le resultat (plan_id + checklist), ou null si echec/refus. */
    suspend fun genererSouvenir(lat: Double, lon: Double, groupe: String,
        departLat: Double?, departLon: Double?, plan: String = "orbite",
        orientation: String = "auto", capManuelDeg: Double? = null,
        proximite: String = "equilibre"): SouvenirResultat?
    /** Telecharge le KMZ d'un plan deja genere cote serveur, par son plan_id
     *  (via /api/kmz). Reutilisable pour souvenir et top-down. */
    suspend fun telechargerKmzParPlanId(planId: String, destination: java.io.File): java.io.File?
    /** Genere un plan de haut (top-down) via /api/top_down. contexte =
     *  "objet_lieu" ou "sujet_isole_consenti". consentement obligatoire si
     *  sujet isole. Renvoie le resultat (plan_id), ou null si echec/refus. */
    suspend fun genererTopDown(lat: Double, lon: Double, contexte: String,
        variante: String, descendre: Boolean, consentement: Boolean): TopDownResultat?
    /** Liste les missions NOMMEES de l'utilisateur connecte (via /api/missions).
     *  token = jeton JWT obtenu au login. Renvoie null en cas d'echec/reseau. */
    suspend fun listerMesMissions(token: String): List<MissionResumee>?
    /** Telecharge le KMZ WPML d'une mission par son id (via /api/missions/{id}/kmz).
     *  token = jeton JWT. Renvoie le fichier ecrit, ou null en cas d'echec. */
    suspend fun telechargerMissionParId(missionId: Int, token: String,
        destination: java.io.File): java.io.File?
}

/** Resultat d'un plan top-down genere par le serveur (/api/top_down). */
data class TopDownResultat(
    val planId: String,
    val variante: String,
    val contexte: String
)

/** Resume d'une mission NOMMEE sauvegardee par l'utilisateur sur le web
 *  (Explorer). Recupere par Solo via /api/missions (compte connecte). */
data class MissionResumee(
    val id: Int,
    val nom: String,
    val lieuLabel: String?,
    val intention: String?,
    val duree: String,
    val mode: String,
    val lat: Double,
    val lon: Double,
    val creeLe: String = ""
)

/** Resultat d'un plan souvenir genere par le serveur (/api/souvenir). */
data class SouvenirResultat(
    val planId: String,
    val groupe: String,
    val enfantsPresents: Boolean,
    val distanceSecuriteM: Double,
    val secondAdulteRecommande: Boolean,
    val checklist: List<String>,
    val intention: String
)

/** Une recette recommandee par le serveur (deja traduite en grand public). */
data class RecetteLieu(
    val cle: String,
    val emoji: String,
    val nom: String,
    val justification: String
)

/** Un point d'interet detecte (pour la carte). */
data class PointLieu(
    val type: String,
    val label: String,
    val nom: String,
    val lat: Double,
    val lon: Double,
    val score: Int,
    val distanceM: Int = 0,
    val volTotalMin: Double = 0.0,
    val volTransitAllerS: Int = 0,
    val volFilmageS: Int = 0,
    val volTransitRetourS: Int = 0,
    val volDepasseAutonomie: Boolean = false
)

/** Suggestion de deplacement vers un meilleur site (cercle voiture). */
/** Statut d'un plan dans le pipeline de preparation. */
enum class StatutPlan {
    BROUILLON,            // intention creee, pas encore prete
    PRET_SIMULATION,      // parametres complets, simulable
    NON_VALIDE_VOL_REEL   // jamais teste en vol reel
}

/** Intention de vol structuree, issue d'un plan du storyboard.
 *  "Preparer ce plan" cree ceci ; le vol reel viendra plus tard. */
data class PlanExecutable(
    val typePlan: String,
    val sujet: String,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double?,
    val rayonM: Double?,
    val distanceM: Double?,
    val dureeSuggereeS: Int?,
    val consigne: String,
    val securite: String,
    val recetteCle: String = "",
    val statut: StatutPlan = StatutPlan.BROUILLON
)

/** Convertit un plan du storyboard en intention de vol executable. */
fun PlanStoryboard.toPlanExecutable(): PlanExecutable = PlanExecutable(
    typePlan = plan,
    sujet = sujet,
    latitude = if (lat != 0.0) lat else null,
    longitude = if (lon != 0.0) lon else null,
    altitudeM = if (altitudeM > 0) altitudeM.toDouble() else null,
    rayonM = if (rayonM > 0) rayonM.toDouble() else null,
    distanceM = if (distanceM > 0) distanceM.toDouble() else null,
    dureeSuggereeS = if (dureeSuggereeS > 0) dureeSuggereeS else null,
    consigne = consigne,
    securite = securite,
    recetteCle = recetteCle,
    statut = StatutPlan.PRET_SIMULATION   // parametres complets -> simulable
)

data class MissionBrouillon(
    val statut: String,
    val executable: Boolean,
    val avertissement: String,
    val nbPlans: Int,
    val totalFilmageS: Int,
    val totalTransitS: Int,
    val totalTransitM: Int,
    val totalDureeMin: Double
)

data class PlanStoryboard(
    val ordre: Int,
    val role: String,
    val plan: String,
    val planLibelle: String,
    val sujet: String,
    val description: String,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val recetteCle: String = "",
    val altitudeM: Int = 0,
    val distanceM: Int = 0,
    val rayonM: Int = 0,
    val consigne: String = "",
    val securite: String = "",
    val noteFiche: String = "",
    val dureeSuggereeS: Int = 0,
    val dureeMinS: Int = 0,
    val dureeMaxS: Int = 0
)

data class SuggestionDeplacement(
    val nom: String,
    val type: String,
    val label: String,
    val distanceKm: Double,
    val lat: Double,
    val lon: Double,
    val trajetVoitureMin: Double = 0.0,
    val volSurPlaceMin: Double = 0.0
)

/** Le rapport renvoye par le serveur Explorer. */
data class RapportLieu(
    val sceneType: String,
    val dominant: String,
    val confiance: Float,
    val recettes: List<RecetteLieu>,
    val ingredientsResume: List<String>,
    val niveau: String = "",
    val message: String = "",
    val suggestion: SuggestionDeplacement? = null,
    val suggestions: List<SuggestionDeplacement> = emptyList(),
    val points: List<PointLieu> = emptyList(),
    val storyboard: List<PlanStoryboard> = emptyList(),
    val storyboardDureeS: Int = 0,
    val missionBrouillon: MissionBrouillon? = null,
    val centreLat: Double = 0.0,
    val centreLon: Double = 0.0
) {
    fun resume(): String {
        val d = if (dominant.isNotBlank()) dominant else sceneType
        return "Lieu analyse : $d."
    }
}

/**
 * Implementation HTTP reelle. Appelle l'endpoint Explorer.
 * baseUrl par defaut = serveur DigitalOcean. Modifiable si l'adresse change.
 */
class AnalyseurLieuServeur(
    private val baseUrl: String = "https://cineflight.ca"
) : AnalyseurLieu {

    /** Derniere erreur rencontree (pour diagnostic a l'ecran). */
    @Volatile override var derniereErreur: String? = null
        private set

    override fun disponible(): Boolean = true  // verifie reellement a l'appel (null si echec)

    override suspend fun analyser(lat: Double, lon: Double, rayonM: Int, profil: String?): RapportLieu? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // IMPORTANT : le reseau DOIT s'executer hors du thread UI (sinon
            // NetworkOnMainThreadException immediate). D'ou le withContext(IO).
            val pParam = if (!profil.isNullOrBlank()) "&profil=$profil" else ""
            val url = URL("$baseUrl/api/recommandations?lat=$lat&lon=$lon&rayon_m=$rayonM$pParam")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 70000   // Overpass peut etre tres lent (45s+ observe)
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code != 200) {
                    derniereErreur = "Le serveur a repondu $code"
                    return@withContext null
                }
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                derniereErreur = null
                parser(texte)
            } catch (e: Exception) {
                derniereErreur = (e.javaClass.simpleName) + " : " + (e.message ?: "?")
                null
            } finally {
                conn?.disconnect()
            }
        }

    private fun parser(json: String): RapportLieu? {
        return try {
            val o = JSONObject(json)
            val recArr = o.optJSONArray("recettes")
            val recettes = ArrayList<RecetteLieu>()
            if (recArr != null) {
                for (i in 0 until recArr.length()) {
                    val r = recArr.getJSONObject(i)
                    recettes.add(RecetteLieu(
                        cle = r.optString("cle", ""),
                        emoji = r.optString("emoji", "🎬"),
                        nom = r.optString("nom", "Recette"),
                        justification = r.optString("justification", "")
                    ))
                }
            }
            val ingArr = o.optJSONArray("ingredients_resume")
            val ingredients = ArrayList<String>()
            if (ingArr != null) for (i in 0 until ingArr.length()) ingredients.add(ingArr.getString(i))

            // points pour la carte
            val ptsArr = o.optJSONArray("points")
            val points = ArrayList<PointLieu>()
            if (ptsArr != null) {
                for (i in 0 until ptsArr.length()) {
                    val p = ptsArr.getJSONObject(i)
                    val dv = p.optJSONObject("duree_vol")
                    points.add(PointLieu(
                        type = p.optString("type", ""),
                        label = p.optString("label", ""),
                        nom = p.optString("nom", ""),
                        lat = p.optDouble("lat", 0.0),
                        lon = p.optDouble("lon", 0.0),
                        score = p.optInt("score", 0),
                        distanceM = p.optInt("distance_m", 0),
                        volTotalMin = dv?.optDouble("total_min", 0.0) ?: 0.0,
                        volTransitAllerS = dv?.optInt("transit_aller_s", 0) ?: 0,
                        volFilmageS = dv?.optInt("filmage_s", 0) ?: 0,
                        volTransitRetourS = dv?.optInt("transit_retour_s", 0) ?: 0,
                        volDepasseAutonomie = dv?.optBoolean("depasse_autonomie", false) ?: false
                    ))
                }
            }
            // suggestion de deplacement (peut etre null)
            var suggestion: SuggestionDeplacement? = null
            val sObj = o.optJSONObject("suggestion_deplacement")
            if (sObj != null) {
                suggestion = SuggestionDeplacement(
                    nom = sObj.optString("nom", ""),
                    type = sObj.optString("type", ""),
                    label = sObj.optString("label", ""),
                    distanceKm = sObj.optDouble("distance_km", 0.0),
                    lat = sObj.optDouble("lat", 0.0),
                    lon = sObj.optDouble("lon", 0.0)
                )
            }
            // liste complete des sites suggeres (jusqu'a 20)
            val suggList = ArrayList<SuggestionDeplacement>()
            val sArr = o.optJSONArray("suggestions_deplacement")
            if (sArr != null) {
                for (i in 0 until sArr.length()) {
                    val s = sArr.getJSONObject(i)
                    val du = s.optJSONObject("duree")
                    suggList.add(SuggestionDeplacement(
                        nom = s.optString("nom", ""),
                        type = s.optString("type", ""),
                        label = s.optString("label", ""),
                        distanceKm = s.optDouble("distance_km", 0.0),
                        lat = s.optDouble("lat", 0.0),
                        lon = s.optDouble("lon", 0.0),
                        trajetVoitureMin = du?.optDouble("trajet_voiture_min", 0.0) ?: 0.0,
                        volSurPlaceMin = du?.optDouble("vol_sur_place_min", 0.0) ?: 0.0
                    ))
                }
            }
            val centre = o.optJSONObject("centre")
            val cLat = centre?.optDouble("lat", 0.0) ?: 0.0
            val cLon = centre?.optDouble("lon", 0.0) ?: 0.0

            // storyboard (sequence cinematographique combinee)
            val sbList = ArrayList<PlanStoryboard>()
            val sbArr = o.optJSONArray("storyboard")
            if (sbArr != null) {
                for (i in 0 until sbArr.length()) {
                    val pl = sbArr.getJSONObject(i)
                    val fiche = pl.optJSONObject("fiche")
                    val dur = pl.optJSONObject("duree")
                    val moyDur = dur?.optInt("moyenne_s", 0) ?: 0
                    val minDur = dur?.optInt("min_s", 0) ?: 0
                    val maxDur = dur?.optInt("max_s", 0) ?: 0
                    sbList.add(PlanStoryboard(
                        ordre = pl.optInt("ordre", i + 1),
                        role = pl.optString("role", ""),
                        plan = pl.optString("plan", ""),
                        planLibelle = pl.optString("plan_libelle", ""),
                        sujet = pl.optString("sujet", ""),
                        description = pl.optString("description", ""),
                        lat = pl.optDouble("lat", 0.0),
                        lon = pl.optDouble("lon", 0.0),
                        recetteCle = pl.optString("recette_cle", ""),
                        altitudeM = fiche?.optInt("altitude_m", 0) ?: 0,
                        distanceM = fiche?.optInt("distance_m", 0) ?: 0,
                        rayonM = fiche?.optInt("rayon_m", 0) ?: 0,
                        consigne = fiche?.optString("consigne", "") ?: "",
                        securite = fiche?.optString("securite", "") ?: "",
                        noteFiche = fiche?.optString("note", "") ?: "",
                        dureeSuggereeS = moyDur,
                        dureeMinS = minDur,
                        dureeMaxS = maxDur
                    ))
                }
            }

            // mission brouillon (architecture etape G, non executable)
            var missionBr: MissionBrouillon? = null
            val mObj = o.optJSONObject("mission_brouillon")
            if (mObj != null) {
                missionBr = MissionBrouillon(
                    statut = mObj.optString("statut", "brouillon"),
                    executable = mObj.optBoolean("executable", false),
                    avertissement = mObj.optString("avertissement", ""),
                    nbPlans = mObj.optInt("nb_plans", 0),
                    totalFilmageS = mObj.optInt("total_filmage_s", 0),
                    totalTransitS = mObj.optInt("total_transit_s", 0),
                    totalTransitM = mObj.optInt("total_transit_m", 0),
                    totalDureeMin = mObj.optDouble("total_duree_min", 0.0)
                )
            }

            RapportLieu(
                sceneType = o.optString("scene_type", ""),
                dominant = o.optString("dominant", ""),
                confiance = o.optDouble("confiance", 0.0).toFloat(),
                recettes = recettes,
                ingredientsResume = ingredients,
                niveau = o.optString("niveau", ""),
                message = o.optString("message", ""),
                suggestion = suggestion,
                suggestions = suggList,
                points = points,
                storyboard = sbList,
                storyboardDureeS = o.optInt("storyboard_duree_s", 0),
                missionBrouillon = missionBr,
                centreLat = cLat,
                centreLon = cLon
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Geocode une adresse via le serveur (/api/geocode?q=...).
     *  Renvoie (lat, lon, label) du 1er resultat, ou null. Reseau sur IO. */
    override suspend fun geocoder(adresse: String): Triple<Double, Double, String>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val q = java.net.URLEncoder.encode(adresse, "UTF-8")
            val url = URL("$baseUrl/api/geocode?q=$q")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 20000
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode != 200) return@withContext null
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(texte)
                val arr = o.optJSONArray("resultats") ?: return@withContext null
                if (arr.length() == 0) return@withContext null
                val r0 = arr.getJSONObject(0)
                Triple(
                    r0.getDouble("lat"),
                    r0.getDouble("lon"),
                    r0.optString("nom", r0.optString("label", adresse))
                )
            } catch (e: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    /** Verifie le degagement d'un point via /api/verifier_point.
     *  Meme pattern reseau que analyser() : IO thread, gestion d'erreur,
     *  parsing JSON. Renvoie null si le reseau echoue. */
    override suspend fun verifierPoint(lat: Double, lon: Double, rayonM: Int): ResultatDegagement? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = URL("$baseUrl/api/verifier_point?lat=$lat&lon=$lon&rayon=$rayonM")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 70000   // Overpass peut etre lent
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code != 200) {
                    derniereErreur = "Le serveur a repondu $code"
                    return@withContext null
                }
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                derniereErreur = null
                val o = JSONObject(texte)
                val obsArr = o.optJSONArray("obstacles_connus")
                val obstacles = ArrayList<ObstacleConnu>()
                if (obsArr != null) for (i in 0 until obsArr.length()) {
                    val ob = obsArr.getJSONObject(i)
                    obstacles.add(ObstacleConnu(
                        type = ob.optString("type", "obstacle"),
                        distanceM = ob.optDouble("distance_m", 0.0).toInt()
                    ))
                }
                val chkArr = o.optJSONArray("checklist_pilote")
                val checklist = ArrayList<String>()
                if (chkArr != null) for (i in 0 until chkArr.length()) checklist.add(chkArr.getString(i))
                ResultatDegagement(
                    etat = o.optString("etat", "carte_indisponible"),
                    carteDisponible = o.optBoolean("carte_disponible", false),
                    obstacles = obstacles,
                    message = o.optString("message", ""),
                    checklist = checklist,
                    disclaimer = o.optString("disclaimer", "")
                )
            } catch (e: Exception) {
                derniereErreur = (e.javaClass.simpleName) + " : " + (e.message ?: "?")
                null
            } finally {
                conn?.disconnect()
            }
        }

    /** Telecharge la mission KMZ complete. depart_lat/lon = point de decollage
     *  reel (GPS sur place) ; si null, la mission decolle du lieu analyse. */
    override suspend fun telechargerMissionKmz(lat: Double, lon: Double, profil: String?,
                                               destination: java.io.File): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            telechargerMissionKmzAvecDepart(lat, lon, profil, null, null, destination)
        }

    /** Variante avec point de decollage explicite (GPS reel). */
    override suspend fun telechargerMissionKmzAvecDepart(lat: Double, lon: Double, profil: String?,
                                                departLat: Double?, departLon: Double?,
                                                destination: java.io.File): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pParam = if (!profil.isNullOrBlank()) "&profil=$profil" else ""
            val dParam = if (departLat != null && departLon != null)
                "&depart_lat=$departLat&depart_lon=$departLon" else ""
            val url = URL("$baseUrl/api/mission_kmz?lat=$lat&lon=$lon$pParam$dParam")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 90000
                }
                if (conn.responseCode != 200) return@withContext null
                conn.inputStream.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                if (destination.length() > 0) destination else null
            } catch (e: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    override suspend fun genererSouvenir(lat: Double, lon: Double, groupe: String,
                                         departLat: Double?, departLon: Double?,
                                         plan: String,
                                         orientation: String, capManuelDeg: Double?,
                                         proximite: String): SouvenirResultat? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = URL("$baseUrl/api/souvenir")
            var conn: HttpURLConnection? = null
            try {
                val corps = JSONObject().apply {
                    put("lat", lat)
                    put("lon", lon)
                    put("groupe", groupe)
                    put("plan", plan)
                    put("proximite", proximite)
                    put("orientation", orientation)
                    if (capManuelDeg != null) put("cap_manuel_deg", capManuelDeg)
                    put("pilote_dans_scene", true)
                    if (departLat != null) put("depart_lat", departLat)
                    if (departLon != null) put("depart_lon", departLon)
                }
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.use { it.write(corps.toString().toByteArray()) }
                val code = conn.responseCode
                if (code != 200) {
                    // 400 = garde serveur (ex. point de decollage manquant)
                    val err = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }
                    } catch (_: Exception) { null }
                    derniereErreur = if (err != null) {
                        try { JSONObject(err).optString("detail", "Erreur $code") }
                        catch (_: Exception) { "Erreur $code" }
                    } else "Le serveur a repondu $code"
                    return@withContext null
                }
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                derniereErreur = null
                val o = JSONObject(texte)
                val planId = o.optString("plan_id", "")
                if (planId.isBlank()) { derniereErreur = "Reponse sans plan_id"; return@withContext null }
                val checklistArr = o.optJSONArray("checklist")
                val checklist = ArrayList<String>()
                if (checklistArr != null) {
                    for (i in 0 until checklistArr.length()) checklist.add(checklistArr.getString(i))
                }
                SouvenirResultat(
                    planId = planId,
                    groupe = o.optString("groupe", groupe),
                    enfantsPresents = o.optBoolean("enfants_presents", false),
                    distanceSecuriteM = o.optDouble("distance_securite_m", 0.0),
                    secondAdulteRecommande = o.optBoolean("second_adulte_recommande", false),
                    checklist = checklist,
                    intention = o.optString("intention", "")
                )
            } catch (e: Exception) {
                derniereErreur = (e.javaClass.simpleName) + " : " + (e.message ?: "?")
                null
            } finally {
                conn?.disconnect()
            }
        }

    override suspend fun telechargerKmzParPlanId(planId: String,
                                                 destination: java.io.File): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = URL("$baseUrl/api/kmz")
            var conn: HttpURLConnection? = null
            try {
                val corps = JSONObject().apply { put("plan_id", planId) }
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 90000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(corps.toString().toByteArray()) }
                val code = conn.responseCode
                if (code != 200) {
                    val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                    derniereErreur = if (err != null) {
                        try { JSONObject(err).optString("detail", "Erreur $code") } catch (_: Exception) { "Erreur $code" }
                    } else "Le serveur a repondu $code"
                    return@withContext null
                }
                conn.inputStream.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                if (destination.length() > 0) { derniereErreur = null; destination }
                else { derniereErreur = "Fichier de mission vide (0 octet)"; null }
            } catch (e: Exception) {
                derniereErreur = (e.javaClass.simpleName) + " : " + (e.message ?: "?")
                null
            } finally {
                conn?.disconnect()
            }
        }

    override suspend fun genererTopDown(lat: Double, lon: Double, contexte: String,
                                        variante: String, descendre: Boolean,
                                        consentement: Boolean): TopDownResultat? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = URL("$baseUrl/api/top_down")
            var conn: HttpURLConnection? = null
            try {
                val corps = JSONObject().apply {
                    put("lat", lat)
                    put("lon", lon)
                    put("contexte", contexte)
                    put("variante", variante)
                    put("descendre", descendre)
                    put("consentement", consentement)
                }
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.use { it.write(corps.toString().toByteArray()) }
                val code = conn.responseCode
                if (code != 200) {
                    val err = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }
                    } catch (_: Exception) { null }
                    derniereErreur = if (err != null) {
                        try { JSONObject(err).optString("detail", "Erreur $code") }
                        catch (_: Exception) { "Erreur $code" }
                    } else "Le serveur a repondu $code"
                    return@withContext null
                }
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                derniereErreur = null
                val o = JSONObject(texte)
                val planId = o.optString("plan_id", "")
                if (planId.isBlank()) { derniereErreur = "Reponse sans plan_id"; return@withContext null }
                TopDownResultat(
                    planId = planId,
                    variante = o.optString("variante", variante),
                    contexte = o.optString("contexte", contexte)
                )
            } catch (e: Exception) {
                derniereErreur = (e.javaClass.simpleName) + " : " + (e.message ?: "?")
                null
            } finally {
                conn?.disconnect()
            }
        }

    override suspend fun listerMesMissions(token: String): List<MissionResumee>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = URL("$baseUrl/api/missions")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code != 200) {
                    derniereErreur = "Liste missions : le serveur a repondu $code"
                    return@withContext null
                }
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                derniereErreur = null
                val o = JSONObject(texte)
                val arr = o.optJSONArray("missions") ?: return@withContext emptyList()
                val liste = ArrayList<MissionResumee>(arr.length())
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    liste.add(MissionResumee(
                        id = m.getInt("id"),
                        nom = m.optString("nom", "(sans nom)"),
                        lieuLabel = if (m.isNull("lieu_label")) null else m.optString("lieu_label"),
                        intention = if (m.isNull("intention")) null else m.optString("intention"),
                        duree = m.optString("duree", "moyenne"),
                        mode = m.optString("mode", "scenarise"),
                        lat = m.optDouble("lat", 0.0),
                        lon = m.optDouble("lon", 0.0),
                        creeLe = if (m.isNull("created_at")) "" else m.optString("created_at", "")
                    ))
                }
                liste
            } catch (e: Exception) {
                derniereErreur = (e.javaClass.simpleName) + " : " + (e.message ?: "?")
                null
            } finally {
                conn?.disconnect()
            }
        }

    override suspend fun telechargerMissionParId(missionId: Int, token: String,
                                                 destination: java.io.File): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = URL("$baseUrl/api/missions/$missionId/kmz")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 90000
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (conn.responseCode != 200) {
                    derniereErreur = "Telechargement de la mission : le serveur a repondu ${conn.responseCode}"
                    return@withContext null
                }
                conn.inputStream.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                if (destination.length() > 0) {
                    derniereErreur = null
                    destination
                } else null
            } catch (e: Exception) {
                derniereErreur = (e.javaClass.simpleName) + " : " + (e.message ?: "?")
                null
            } finally {
                conn?.disconnect()
            }
        }
}

