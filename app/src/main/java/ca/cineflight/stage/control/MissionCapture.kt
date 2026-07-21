package ca.cineflight.stage.control

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * MISSION_CAPTURE — infrastructure d'apprentissage de CineFlight.
 *
 * Principe directeur (decide apres longue reflexion d'architecture) :
 *   "Ne capturer dans la generation que ce qu'on ne pourra JAMAIS reconstruire apres."
 *
 * Cette table ne contient QUE de l'intention figee a l'instant ou la mission est
 * generee : etat du lieu, contexte ephemere, etat de la decision. Tout RESULTAT
 * (export, montage, retour sur le lieu, cadrage reel, batterie...) appartient a
 * une FUTURE table d'execution, PAS ici. Regle d'admission d'un champ :
 *   "Est-il connu et fige a l'instant T de la generation ?" Si non -> pas ici.
 *
 * Flux : ecriture LOCALE immediate (SQLite, source de verite) -> envoi DIFFERE
 * best-effort vers le serveur DO (tolere le hors-ligne : file d'attente qui se
 * vide quand le reseau revient). On ne perd jamais une capture pour un reseau
 * absent — c'est justement dans les lieux isoles (sans reseau) que la donnee
 * est la plus precieuse.
 *
 * Vie privee (loi 25 QC / RGPD) : l'envoi serveur est conditionne a un opt-in
 * explicite. L'identifiant est un PSEUDONYME aleatoire genere a l'installation,
 * jamais relie a un compte/email. La capture locale fonctionne meme sans consentement ;
 * seul l'ENVOI est conditionne. L'app fonctionne pleinement si l'utilisateur refuse.
 */

// =====================================================================
// 1) MODELE — uniquement ce qui est connu a l'instant de generation
// =====================================================================

data class MissionCapture(
    val missionId: String,
    val userId: String,                 // pseudonyme aleatoire (pas un compte)
    val timestamp: Long,                 // epoch ms, instant de generation

    // --- versions (irreconstructibles : on doit savoir QUI a decide) ---
    val versionApp: String,
    val versionGrammaire: String,
    val versionClassification: String,

    // --- LIEU (stable) : features brutes, PAS la categorie derivee ---
    val latitude: Double,
    val longitude: Double,
    val reliefScore: Double,
    val waterScore: Double,
    val builtScore: Double,
    val prominence: Double,

    // --- CONTEXTE (ephemere) : calcule localement, gratuit, hors-ligne ---
    val saison: String,
    val angleSolaireDeg: Double,         // elevation du soleil (deg) a l'instant T

    // --- meteo : best-effort, NULLABLE (jamais bloquant) ---
    val meteoJson: String?,

    // --- DECISION (raisonnement interne, non reconstructible) ---
    val modeDecision: String,            // REGLE | EXPLORATION | UTILISATEUR
    val scoresCandidatsJson: String,     // {"schema_version":1,"scores":{...}} TOUS les candidats
    val recetteChoisie: String,

    // --- contexte utilisateur minimal (pour normaliser, pas pour vendre) ---
    val enjeuVol: String                 // TEST | LOISIR | CLIENT
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mission_id", missionId)
        put("user_id", userId)
        put("timestamp", timestamp)
        put("version_app", versionApp)
        put("version_grammaire", versionGrammaire)
        put("version_classification", versionClassification)
        put("latitude", latitude)
        put("longitude", longitude)
        put("relief_score", reliefScore)
        put("water_score", waterScore)
        put("built_score", builtScore)
        put("prominence", prominence)
        put("saison", saison)
        put("angle_solaire", angleSolaireDeg)
        put("meteo_json", meteoJson ?: JSONObject.NULL)
        put("mode_decision", modeDecision)
        put("scores_candidats_json", scoresCandidatsJson)
        put("recette_choisie", recetteChoisie)
        put("enjeu_vol", enjeuVol)
    }
}

// =====================================================================
// 2) CALCULS LOCAUX — gratuits, hors-ligne, jamais perdus
// =====================================================================

object ContexteLocal {
    /** Saison meteorologique a partir du mois (hemisphere nord). */
    fun saison(timestampMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        return when (cal.get(Calendar.MONTH)) {
            Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> "hiver"
            Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> "printemps"
            Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> "ete"
            else -> "automne"
        }
    }

    /**
     * Elevation solaire (degres au-dessus de l'horizon) pour une date/heure/position.
     * Algorithme astronomique simplifie (precision ~0.5 deg, largement suffisant
     * pour categoriser la lumiere : rasante, basse, haute). 100% local, hors-ligne.
     */
    fun angleSolaireDeg(timestampMs: Long, latitude: Double, longitude: Double): Double {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timestampMs }
        val jour = cal.get(Calendar.DAY_OF_YEAR)
        val heureUtc = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0

        // declinaison du soleil
        val gamma = 2.0 * PI / 365.0 * (jour - 1 + (heureUtc - 12) / 24.0)
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
                0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
                0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        // equation du temps (minutes)
        val eqTemps = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        // angle horaire
        val tempsSolaire = heureUtc * 60 + eqTemps + 4 * longitude
        val angleHoraire = Math.toRadians(tempsSolaire / 4.0 - 180.0)
        val latRad = Math.toRadians(latitude)
        val cosZenith = sin(latRad) * sin(decl) + cos(latRad) * cos(decl) * cos(angleHoraire)
        val zenith = acos(cosZenith.coerceIn(-1.0, 1.0))
        return 90.0 - Math.toDegrees(zenith)   // elevation
    }
}

// =====================================================================
// 3) CONSENTEMENT + PSEUDONYME (loi 25)
// =====================================================================

object ConsentementCapture {
    private const val PREFS = "cineflight_capture"
    private const val CLE_CONSENTEMENT = "envoi_consenti"
    private const val CLE_CONSENTEMENT_DEMANDE = "consentement_demande"
    private const val CLE_USER_ID = "pseudonyme"

    /** true si l'utilisateur a accepte l'envoi serveur (la capture locale, elle, est toujours active). */
    fun envoiConsenti(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLE_CONSENTEMENT, false)

    fun consentementDejaDemande(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLE_CONSENTEMENT_DEMANDE, false)

    fun definirConsentement(ctx: Context, accepte: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CLE_CONSENTEMENT, accepte)
            .putBoolean(CLE_CONSENTEMENT_DEMANDE, true)
            .apply()
    }

    /** Pseudonyme aleatoire stable, genere une fois a l'installation. Jamais relie a un compte. */
    fun userId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString(CLE_USER_ID, null)
        if (id == null) {
            id = "anon-" + UUID.randomUUID().toString()
            prefs.edit().putString(CLE_USER_ID, id).apply()
        }
        return id
    }
}

// =====================================================================
// 4) STOCKAGE LOCAL (SQLite) — source de verite
// =====================================================================

class CaptureStore(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, BD, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE mission_capture (
                mission_id TEXT PRIMARY KEY,
                user_id TEXT,
                timestamp INTEGER,
                version_app TEXT,
                version_grammaire TEXT,
                version_classification TEXT,
                latitude REAL,
                longitude REAL,
                relief_score REAL,
                water_score REAL,
                built_score REAL,
                prominence REAL,
                saison TEXT,
                angle_solaire REAL,
                meteo_json TEXT,
                mode_decision TEXT,
                scores_candidats_json TEXT,
                recette_choisie TEXT,
                enjeu_vol TEXT,
                envoye INTEGER DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, ancienne: Int, nouvelle: Int) {
        // Pour l'instant on ne casse rien : strategie additive future.
    }

    /** Ecrit la capture LOCALEMENT (source de verite). Idempotent sur mission_id. */
    fun enregistrer(mc: MissionCapture) {
        try {
            val v = ContentValues().apply {
                put("mission_id", mc.missionId)
                put("user_id", mc.userId)
                put("timestamp", mc.timestamp)
                put("version_app", mc.versionApp)
                put("version_grammaire", mc.versionGrammaire)
                put("version_classification", mc.versionClassification)
                put("latitude", mc.latitude)
                put("longitude", mc.longitude)
                put("relief_score", mc.reliefScore)
                put("water_score", mc.waterScore)
                put("built_score", mc.builtScore)
                put("prominence", mc.prominence)
                put("saison", mc.saison)
                put("angle_solaire", mc.angleSolaireDeg)
                put("meteo_json", mc.meteoJson)
                put("mode_decision", mc.modeDecision)
                put("scores_candidats_json", mc.scoresCandidatsJson)
                put("recette_choisie", mc.recetteChoisie)
                put("enjeu_vol", mc.enjeuVol)
                put("envoye", 0)
            }
            writableDatabase.insertWithOnConflict(
                "mission_capture", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            Log.i(TAG, "capture enregistree localement: ${mc.missionId}")
        } catch (e: Exception) {
            Log.e(TAG, "enregistrer ex: ${e.message}")
        }
    }

    /** Captures pas encore envoyees (limite pour ne pas saturer un envoi). */
    fun nonEnvoyees(limite: Int = 50): List<MissionCapture> {
        val liste = ArrayList<MissionCapture>()
        try {
            val c = readableDatabase.query(
                "mission_capture", null, "envoye = 0", null, null, null,
                "timestamp ASC", limite.toString())
            c.use {
                while (it.moveToNext()) liste.add(lire(it))
            }
        } catch (e: Exception) { Log.e(TAG, "nonEnvoyees ex: ${e.message}") }
        return liste
    }

    fun marquerEnvoye(missionId: String) {
        try {
            val v = ContentValues().apply { put("envoye", 1) }
            writableDatabase.update("mission_capture", v, "mission_id = ?", arrayOf(missionId))
        } catch (e: Exception) { Log.e(TAG, "marquerEnvoye ex: ${e.message}") }
    }

    private fun lire(c: android.database.Cursor): MissionCapture {
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
        fun d(n: String) = c.getDouble(c.getColumnIndexOrThrow(n))
        fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        val meteoIdx = c.getColumnIndexOrThrow("meteo_json")
        return MissionCapture(
            missionId = s("mission_id"), userId = s("user_id"), timestamp = l("timestamp"),
            versionApp = s("version_app"), versionGrammaire = s("version_grammaire"),
            versionClassification = s("version_classification"),
            latitude = d("latitude"), longitude = d("longitude"),
            reliefScore = d("relief_score"), waterScore = d("water_score"),
            builtScore = d("built_score"), prominence = d("prominence"),
            saison = s("saison"), angleSolaireDeg = d("angle_solaire"),
            meteoJson = if (c.isNull(meteoIdx)) null else c.getString(meteoIdx),
            modeDecision = s("mode_decision"),
            scoresCandidatsJson = s("scores_candidats_json"),
            recetteChoisie = s("recette_choisie"), enjeuVol = s("enjeu_vol")
        )
    }

    companion object {
        private const val TAG = "CaptureStore"
        private const val BD = "cineflight_capture.db"
        private const val VERSION = 1
    }
}

// =====================================================================
// 5) SYNCHRONISATION DIFFEREE — best-effort, tolere le hors-ligne
// =====================================================================

object CaptureSync {
    private const val TAG = "CaptureSync"
    private const val URL_ENVOI = "https://cineflight.ca/api/mission-capture"

    /**
     * Vide la file d'attente vers le serveur, en arriere-plan. Ne fait RIEN si
     * l'utilisateur n'a pas consenti a l'envoi. A appeler au demarrage et apres
     * chaque nouvelle capture : ce qui n'a pas pu partir partira au prochain essai.
     */
    fun synchroniser(ctx: Context) {
        if (!ConsentementCapture.envoiConsenti(ctx)) {
            Log.i(TAG, "envoi non consenti — capture locale conservee, pas d'envoi")
            return
        }
        Thread {
            val store = CaptureStore(ctx)
            val aEnvoyer = store.nonEnvoyees()
            if (aEnvoyer.isEmpty()) return@Thread
            for (mc in aEnvoyer) {
                if (envoyerUne(mc)) store.marquerEnvoye(mc.missionId)
                else break   // reseau coupe : on s'arrete, on reprendra plus tard
            }
        }.start()
    }

    private fun envoyerUne(mc: MissionCapture): Boolean {
        return try {
            val conn = (URL(URL_ENVOI).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(mc.toJson().toString()) }
            val code = conn.responseCode
            conn.disconnect()
            val ok = code in 200..299
            if (!ok) Log.w(TAG, "envoi ${mc.missionId} code=$code")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "envoi ${mc.missionId} echec reseau: ${e.message}")
            false
        }
    }
}

