package ca.cineflight.stage

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.cineflight.stage.cine.CineAuth
import kotlinx.coroutines.launch

/**
 * LoginActivity — connexion au compte CineFlight (serveur Explorer).
 *
 * Layout defilant (ScrollView) pour rester entierement visible meme en
 * paysage / petit ecran. Case "Afficher le mot de passe". En cas de succes,
 * lance MainActivity ; le jeton est conserve par CineAuth.
 */
class LoginActivity : AppCompatActivity() {

    private val BLEU = Color.parseColor("#4FC3F7")
    private val BG = Color.parseColor("#0A1E3D")
    private val FOND_CHAMP = Color.parseColor("#0E131B")
    private val TEXTE_DOUX = Color.parseColor("#8A97A8")
    private val ROUGE = Color.parseColor("#E06B6B")

    private lateinit var champUser: EditText
    private lateinit var champPass: EditText
    private lateinit var bouton: Button
    private lateinit var erreur: TextView
    private lateinit var spinner: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        envoyerAckAndroidTestLogin()
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) SoloRtkPrediction.startContinuous()
        // ScrollView racine : tout reste accessible meme en paysage
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }

        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(20), dp(32), dp(20))
        }

        val titre = TextView(this).apply {
            text = "CineFlight"
            setTextColor(BLEU); textSize = 26f
            gravity = Gravity.CENTER
        }
        val sousTitre = TextView(this).apply {
            text = getString(R.string.login_sous_titre)
            setTextColor(TEXTE_DOUX); textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(18))
        }

        champUser = EditText(this).apply {
            hint = getString(R.string.login_hint_id)
            setHintTextColor(TEXTE_DOUX); setTextColor(Color.WHITE)
            // clavier epure : pas de suggestions/autocorrection (touches plus grandes)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // ne PAS afficher le clavier en plein ecran en paysage (garde les champs visibles)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            setBackgroundColor(FOND_CHAMP)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val espace1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
        }
        champPass = EditText(this).apply {
            hint = getString(R.string.login_hint_mdp)
            setHintTextColor(TEXTE_DOUX); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            // pas de clavier plein ecran en paysage (garde le bouton visible)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            setBackgroundColor(FOND_CHAMP)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // case "Afficher le mot de passe"
        val voirMdp = CheckBox(this).apply {
            text = getString(R.string.login_afficher_mdp)
            setTextColor(TEXTE_DOUX); textSize = 13f
            setPadding(dp(4), dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, coche ->
                val pos = champPass.selectionEnd
                champPass.inputType = if (coche)
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                champPass.setTextColor(Color.WHITE)
                try { champPass.setSelection(pos) } catch (_: Exception) {}
            }
        }

        val espace2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14))
        }

        bouton = Button(this).apply {
            text = getString(R.string.login_connecter)
            isAllCaps = false; textSize = 16f
            setTextColor(Color.parseColor("#0A0E14"))
            setBackgroundColor(BLEU)
            setOnClickListener { tenterConnexion() }
        }

        spinner = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }

        erreur = TextView(this).apply {
            setTextColor(ROUGE); textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }

        val largeur = LinearLayout.LayoutParams(dp(320),
            LinearLayout.LayoutParams.WRAP_CONTENT)
        colonne.addView(titre, largeur)
        colonne.addView(sousTitre, largeur)
        colonne.addView(champUser, largeur)
        colonne.addView(espace1)
        colonne.addView(champPass, largeur)
        colonne.addView(voirMdp, largeur)
        colonne.addView(espace2)
        colonne.addView(bouton, largeur)
        colonne.addView(spinner, largeur)
        colonne.addView(erreur, largeur)

        scroll.addView(colonne)
        setContentView(scroll)
    }

    private fun tenterConnexion() {
        val u = champUser.text.toString().trim()
        val p = champPass.text.toString()
        erreur.text = ""
        if (u.isEmpty() || p.isEmpty()) {
            erreur.text = getString(R.string.login_erreur_requis)
            return
        }
        bouton.isEnabled = false
        spinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val res = CineAuth.login(this@LoginActivity, u, p)
            spinner.visibility = View.GONE
            bouton.isEnabled = true
            if (res.succes) {
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                overridePendingTransition(
                    android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            } else {
                erreur.text = res.message
            }
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun envoyerAckAndroidTestLogin() {
        Thread {
            try {
                val url = java.net.URL("https://cineflight.ca/api/mouvement/ack")
                val connection = url.openConnection() as java.net.HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")

                val payload = """
                    {
                      "source": "android",
                      "device_id": "samsung_SM_A546W",
                      "ok": true,
                      "android_status": "login_activity_ack",
                      "message": "ACK Android depuis LoginActivity"
                    }
                """.trimIndent()

                connection.outputStream.use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                    output.flush()
                }

                val code = connection.responseCode
                val response = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Erreur HTTP $code"
                }

                connection.disconnect()

                android.util.Log.i("CineFlightACK", "ACK LOGIN envoye HTTP=$code reponse=$response")

            } catch (e: Exception) {
                android.util.Log.e("CineFlightACK", "Erreur ACK LOGIN Android", e)
            }
        }.start()
    }

    private fun testerLectureRtkDepuisAndroidLogin() {
        Thread {
            try {
                // 1) Lire la position RTK du sujet depuis le serveur
                val rtkUrl = java.net.URL("https://cineflight.ca/api/rtk/sujet")
                val rtkConnection = rtkUrl.openConnection() as java.net.HttpURLConnection
                rtkConnection.requestMethod = "GET"
                rtkConnection.connectTimeout = 5000
                rtkConnection.readTimeout = 5000

                val rtkCode = rtkConnection.responseCode
                val rtkResponse = if (rtkCode in 200..299) {
                    rtkConnection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    rtkConnection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Erreur HTTP $rtkCode"
                }

                rtkConnection.disconnect()

                android.util.Log.i("CineFlightRTK", "RTK lu HTTP=$rtkCode reponse=$rtkResponse")

                val rtkJson = org.json.JSONObject(rtkResponse)
                val lat = rtkJson.optDouble("lat")
                val lon = rtkJson.optDouble("lon")
                val altM = rtkJson.optDouble("alt_m")
                val rtk = rtkJson.optString("rtk")
                val ageS = rtkJson.optDouble("age_s")
                val hz = rtkJson.optDouble("hz")
                val hdg = rtkJson.optDouble("hdg")

                val present = rtkJson.optBoolean("present", false)
                val rtkNorm = rtk.trim().uppercase()

                val subjectReady =
                    present &&
                    !ageS.isNaN() &&
                    ageS < 2.0 &&
                    (rtkNorm.contains("FIX") || rtkNorm.contains("FLOAT"))

                val reason = when {
                    !present -> "MISSING"
                    ageS.isNaN() -> "AGE_MISSING"
                    ageS >= 2.0 -> "STALE"
                    rtkNorm.contains("FIX") -> "RTK_FIX"
                    rtkNorm.contains("FLOAT") -> "RTK_FLOAT"
                    rtkNorm == "GPS" -> "GPS_ONLY"
                    else -> "LOW_QUALITY"
                }

                // 2) Renvoyer un ACK au serveur pour confirmer que Android a lu le RTK
                val ackUrl = java.net.URL("https://cineflight.ca/api/mouvement/ack")
                val ackConnection = ackUrl.openConnection() as java.net.HttpURLConnection

                ackConnection.requestMethod = "POST"
                ackConnection.connectTimeout = 5000
                ackConnection.readTimeout = 5000
                ackConnection.doOutput = true
                ackConnection.setRequestProperty("Content-Type", "application/json")

                val payload = org.json.JSONObject().apply {
                    put("source", "android")
                    put("device_id", "samsung_SM_A546W")
                    put("ok", rtkCode in 200..299)
                    put("android_status", if (rtkCode in 200..299 && subjectReady) "subject_ready" else if (rtkCode in 200..299) "subject_not_ready" else "rtk_get_error")
                    put("message", "Verdict sujet Android: $reason HTTP=$rtkCode")
                    put("lat", lat)
                    put("lon", lon)
                    put("alt_m", altM)
                    put("rtk", rtk)
                    put("age_s", ageS)
                    put("hz", hz)
                    put("hdg", hdg)
                    put("present", present)
                    put("subject_ready", subjectReady)
                    put("reason", reason)
                }.toString()

                ackConnection.outputStream.use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                    output.flush()
                }

                val ackCode = ackConnection.responseCode
                val ackResponse = if (ackCode in 200..299) {
                    ackConnection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    ackConnection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Erreur HTTP $ackCode"
                }

                ackConnection.disconnect()

                android.util.Log.i("CineFlightRTK", "ACK RTK envoye HTTP=$ackCode reponse=$ackResponse")

            } catch (e: Exception) {
                android.util.Log.e("CineFlightRTK", "Erreur lecture RTK Android", e)
            }
        }.start()
    }

    private fun demarrerPollingRtkLogin() {
        Thread {
            for (i in 1..30) {
                lireRtkEtEnvoyerVerdictPolling(i)
                Thread.sleep(1000)
            }
        }.start()
    }

    private fun lireRtkEtEnvoyerVerdictPolling(iteration: Int) {
        try {
            val rtkUrl = java.net.URL("https://cineflight.ca/api/rtk/sujet")
            val rtkConnection = rtkUrl.openConnection() as java.net.HttpURLConnection
            rtkConnection.requestMethod = "GET"
            rtkConnection.connectTimeout = 5000
            rtkConnection.readTimeout = 5000

            val rtkCode = rtkConnection.responseCode
            val rtkResponse = if (rtkCode in 200..299) {
                rtkConnection.inputStream.bufferedReader().use { it.readText() }
            } else {
                rtkConnection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Erreur HTTP $rtkCode"
            }

            rtkConnection.disconnect()

            val rtkJson = if (rtkCode in 200..299) org.json.JSONObject(rtkResponse) else org.json.JSONObject()

            val present = rtkJson.optBoolean("present", false)
            val lat = rtkJson.optDouble("lat", Double.NaN)
            val lon = rtkJson.optDouble("lon", Double.NaN)
            val altM = rtkJson.optDouble("alt_m", Double.NaN)
            val rtk = rtkJson.optString("rtk", "")
            val ageS = rtkJson.optDouble("age_s", Double.NaN)
            val hz = rtkJson.optDouble("hz", Double.NaN)
            val hdg = rtkJson.optDouble("hdg", Double.NaN)

            val rtkNorm = rtk.trim().uppercase()

            val subjectReady =
                present &&
                !ageS.isNaN() &&
                ageS < 2.0 &&
                (rtkNorm.contains("FIX") || rtkNorm.contains("FLOAT"))

            val reason = when {
                rtkCode !in 200..299 -> "HTTP_ERROR"
                !present -> "MISSING"
                ageS.isNaN() -> "AGE_MISSING"
                ageS >= 2.0 -> "STALE"
                rtkNorm.contains("FIX") -> "RTK_FIX"
                rtkNorm.contains("FLOAT") -> "RTK_FLOAT"
                rtkNorm == "GPS" -> "GPS_ONLY"
                else -> "LOW_QUALITY"
            }

            val ackUrl = java.net.URL("https://cineflight.ca/api/mouvement/ack")
            val ackConnection = ackUrl.openConnection() as java.net.HttpURLConnection

            ackConnection.requestMethod = "POST"
            ackConnection.connectTimeout = 5000
            ackConnection.readTimeout = 5000
            ackConnection.doOutput = true
            ackConnection.setRequestProperty("Content-Type", "application/json")

            val payload = org.json.JSONObject().apply {
                put("source", "android")
                put("device_id", "samsung_SM_A546W")
                put("ok", rtkCode in 200..299)
                put("android_status", if (subjectReady) "rtk_poll_ready" else "rtk_poll_not_ready")
                put("message", "Polling RTK Android #$iteration: $reason HTTP=$rtkCode")
                put("iteration", iteration)
                put("lat", lat)
                put("lon", lon)
                put("alt_m", altM)
                put("rtk", rtk)
                put("age_s", ageS)
                put("hz", hz)
                put("hdg", hdg)
                put("present", present)
                put("subject_ready", subjectReady)
                put("reason", reason)
            }.toString()

            ackConnection.outputStream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            ackConnection.responseCode
            ackConnection.disconnect()

            android.util.Log.i("CineFlightRTK", "Polling RTK #$iteration reason=$reason ready=$subjectReady")

        } catch (e: Exception) {
            android.util.Log.e("CineFlightRTK", "Erreur polling RTK Android", e)
        }
    }

    private fun testerPreviewMouvement4mLogin() {
        Thread {
            try {
                val rtkUrl = java.net.URL("https://cineflight.ca/api/rtk/sujet")
                val rtkConnection = rtkUrl.openConnection() as java.net.HttpURLConnection
                rtkConnection.requestMethod = "GET"
                rtkConnection.connectTimeout = 5000
                rtkConnection.readTimeout = 5000

                val rtkCode = rtkConnection.responseCode
                val rtkResponse = if (rtkCode in 200..299) {
                    rtkConnection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    rtkConnection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Erreur HTTP $rtkCode"
                }

                rtkConnection.disconnect()

                val rtkJson = if (rtkCode in 200..299) org.json.JSONObject(rtkResponse) else org.json.JSONObject()

                val present = rtkJson.optBoolean("present", false)
                val subjectLat = rtkJson.optDouble("lat", Double.NaN)
                val subjectLon = rtkJson.optDouble("lon", Double.NaN)
                val subjectAltM = rtkJson.optDouble("alt_m", Double.NaN)
                val rtk = rtkJson.optString("rtk", "")
                val ageS = rtkJson.optDouble("age_s", Double.NaN)
                val hz = rtkJson.optDouble("hz", Double.NaN)
                val hdg = rtkJson.optDouble("hdg", 0.0)

                val rtkNorm = rtk.trim().uppercase()

                val subjectReady =
                    present &&
                    !ageS.isNaN() &&
                    ageS < 2.0 &&
                    (rtkNorm.contains("FIX") || rtkNorm.contains("FLOAT"))

                val reason = when {
                    rtkCode !in 200..299 -> "HTTP_ERROR"
                    !present -> "MISSING"
                    ageS.isNaN() -> "AGE_MISSING"
                    ageS >= 2.0 -> "STALE"
                    rtkNorm.contains("FIX") -> "RTK_FIX"
                    rtkNorm.contains("FLOAT") -> "RTK_FLOAT"
                    rtkNorm == "GPS" -> "GPS_ONLY"
                    else -> "LOW_QUALITY"
                }

                // Simulation : drone 4 m derrière le sujet selon son heading
                val distanceM = 4.0
                val behindBearingDeg = (hdg + 180.0) % 360.0
                val bearingRad = Math.toRadians(behindBearingDeg)

                val metersPerDegLat = 111111.0
                val metersPerDegLon = 111111.0 * kotlin.math.cos(Math.toRadians(subjectLat))

                val droneLat = subjectLat + (distanceM * kotlin.math.cos(bearingRad)) / metersPerDegLat
                val droneLon = subjectLon + (distanceM * kotlin.math.sin(bearingRad)) / metersPerDegLon
                val droneAltM = if (subjectAltM.isNaN()) Double.NaN else subjectAltM + 5.0

                // Yaw caméra : direction du drone vers le sujet
                val dLonRad = Math.toRadians(subjectLon - droneLon)
                val lat1Rad = Math.toRadians(droneLat)
                val lat2Rad = Math.toRadians(subjectLat)

                val y = kotlin.math.sin(dLonRad) * kotlin.math.cos(lat2Rad)
                val x = kotlin.math.cos(lat1Rad) * kotlin.math.sin(lat2Rad) -
                    kotlin.math.sin(lat1Rad) * kotlin.math.cos(lat2Rad) * kotlin.math.cos(dLonRad)

                val yawCameraDeg = (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
                val horizontalDistanceM = distanceM
                val verticalDeltaM = if (subjectAltM.isNaN() || droneAltM.isNaN()) Double.NaN else droneAltM - subjectAltM
                val gimbalPitchDeg = if (verticalDeltaM.isNaN()) {
                    -10.0
                } else {
                    -Math.toDegrees(kotlin.math.atan2(verticalDeltaM, horizontalDistanceM))
                }

                val ackUrl = java.net.URL("https://cineflight.ca/api/mouvement/ack")
                val ackConnection = ackUrl.openConnection() as java.net.HttpURLConnection

                ackConnection.requestMethod = "POST"
                ackConnection.connectTimeout = 5000
                ackConnection.readTimeout = 5000
                ackConnection.doOutput = true
                ackConnection.setRequestProperty("Content-Type", "application/json")

                val payload = org.json.JSONObject().apply {
                    put("source", "android")
                    put("device_id", "samsung_SM_A546W")
                    put("ok", rtkCode in 200..299)
                    put("android_status", "movement_preview_ready")
                    put("message", "Preview mouvement 4m derriere sujet - simulation seulement")
                    put("safety_mode", "simulation_only")
                    put("movement_allowed", false)
                    put("preview_valid", subjectReady)
                    put("preview_valid", subjectReady)

                    put("subject_lat", subjectLat)
                    put("subject_lon", subjectLon)
                    put("subject_alt_m", subjectAltM)
                    put("subject_hdg", hdg)
                    put("rtk", rtk)
                    put("age_s", ageS)
                    put("hz", hz)
                    put("present", present)
                    put("subject_ready", subjectReady)
                    put("reason", reason)

                    put("distance_m", distanceM)
                    put("drone_preview_lat", droneLat)
                    put("drone_preview_lon", droneLon)
                    put("drone_preview_alt_m", droneAltM)
                    put("bearing_deg", behindBearingDeg)
                    put("yaw_camera_deg", yawCameraDeg)
                    put("gimbal_pitch_deg", gimbalPitchDeg)
                    put("horizontal_distance_m", horizontalDistanceM)
                    put("vertical_delta_m", verticalDeltaM)
                }.toString()

                ackConnection.outputStream.use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                    output.flush()
                }

                ackConnection.responseCode
                ackConnection.disconnect()

                android.util.Log.i("CineFlightMove", "Preview 4m envoye reason=$reason ready=$subjectReady")

            } catch (e: Exception) {
                android.util.Log.e("CineFlightMove", "Erreur preview mouvement 4m", e)
            }
        }.start()
    }

    private fun demarrerPreview4mContinuLogin() {
        Thread {
            for (i in 1..30) {
                testerVitesseSujetLogin()
                Thread.sleep(1000)
            }
        }.start()
    }

    private fun testerVitesseSujetLogin() {
        Thread {
            try {
                fun lireRtkJson(): org.json.JSONObject {
                    val url = java.net.URL("https://cineflight.ca/api/rtk/sujet")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    val code = connection.responseCode
                    val response = if (code in 200..299) {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}"
                    }

                    connection.disconnect()
                    return org.json.JSONObject(response)
                }

                fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
                    val r = 6371000.0
                    val dLat = Math.toRadians(lat2 - lat1)
                    val dLon = Math.toRadians(lon2 - lon1)
                    val a =
                        kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                        kotlin.math.cos(Math.toRadians(lat1)) *
                        kotlin.math.cos(Math.toRadians(lat2)) *
                        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
                    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
                    return r * c
                }

                val p1 = lireRtkJson()
                Thread.sleep(1000)
                val p2 = lireRtkJson()

                val lat1 = p1.optDouble("lat", Double.NaN)
                val lon1 = p1.optDouble("lon", Double.NaN)
                val lat2 = p2.optDouble("lat", Double.NaN)
                val lon2 = p2.optDouble("lon", Double.NaN)

                val distanceSujetM = distanceMetres(lat1, lon1, lat2, lon2)
                val speedMps = distanceSujetM / 1.0
                val speedKmh = speedMps * 3.6

                val present = p2.optBoolean("present", false)
                val subjectAltM = p2.optDouble("alt_m", Double.NaN)
                val rtk = p2.optString("rtk", "")
                val ageS = p2.optDouble("age_s", Double.NaN)
                val hz = p2.optDouble("hz", Double.NaN)
                val hdg = p2.optDouble("hdg", 0.0)

                val rtkNorm = rtk.trim().uppercase()
                val subjectReady =
                    present &&
                    !ageS.isNaN() &&
                    ageS < 2.0 &&
                    (rtkNorm.contains("FIX") || rtkNorm.contains("FLOAT"))

                val reason = when {
                    !present -> "MISSING"
                    ageS.isNaN() -> "AGE_MISSING"
                    ageS >= 2.0 -> "STALE"
                    rtkNorm.contains("FIX") -> "RTK_FIX"
                    rtkNorm.contains("FLOAT") -> "RTK_FLOAT"
                    rtkNorm == "GPS" -> "GPS_ONLY"
                    else -> "LOW_QUALITY"
                }

                val ackUrl = java.net.URL("https://cineflight.ca/api/mouvement/ack")
                val ackConnection = ackUrl.openConnection() as java.net.HttpURLConnection
                ackConnection.requestMethod = "POST"
                ackConnection.connectTimeout = 5000
                ackConnection.readTimeout = 5000
                ackConnection.doOutput = true
                ackConnection.setRequestProperty("Content-Type", "application/json")

                val payload = org.json.JSONObject().apply {
                    put("source", "android")
                    put("device_id", "samsung_SM_A546W")
                    put("ok", true)
                    put("android_status", "subject_speed_ready")
                    put("message", "Vitesse sujet calculee Android - simulation seulement")
                    put("safety_mode", "simulation_only")
                    put("movement_allowed", false)
                    put("preview_valid", subjectReady)
                    put("preview_valid", subjectReady)

                    put("subject_ready", subjectReady)
                    put("reason", reason)
                    put("rtk", rtk)
                    put("age_s", ageS)
                    put("hz", hz)

                    put("lat1", lat1)
                    put("lon1", lon1)
                    put("lat2", lat2)
                    put("lon2", lon2)
                    put("subject_alt_m", subjectAltM)
                    put("subject_hdg", hdg)

                    put("delta_t_s", 1.0)
                    put("distance_subject_m", distanceSujetM)
                    put("speed_mps", speedMps)
                    put("speed_kmh", speedKmh)
                }.toString()

                ackConnection.outputStream.use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                    output.flush()
                }

                ackConnection.responseCode
                ackConnection.disconnect()

                android.util.Log.i("CineFlightSpeed", "Speed sujet=${speedMps} m/s ${speedKmh} km/h")

            } catch (e: Exception) {
                android.util.Log.e("CineFlightSpeed", "Erreur vitesse sujet", e)
            }
        }.start()
    }

    private fun testerPredictionSujet4mLogin() {
        Thread {
            try {
                fun lireRtkJson(): org.json.JSONObject {
                    val url = java.net.URL("https://cineflight.ca/api/rtk/sujet")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    val code = connection.responseCode
                    val response = if (code in 200..299) {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}"
                    }

                    connection.disconnect()
                    return org.json.JSONObject(response)
                }

                fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
                    val r = 6371000.0
                    val dLat = Math.toRadians(lat2 - lat1)
                    val dLon = Math.toRadians(lon2 - lon1)
                    val a =
                        kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                        kotlin.math.cos(Math.toRadians(lat1)) *
                        kotlin.math.cos(Math.toRadians(lat2)) *
                        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
                    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
                    return r * c
                }

                fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
                    val dLonRad = Math.toRadians(lon2 - lon1)
                    val lat1Rad = Math.toRadians(lat1)
                    val lat2Rad = Math.toRadians(lat2)

                    val y = kotlin.math.sin(dLonRad) * kotlin.math.cos(lat2Rad)
                    val x = kotlin.math.cos(lat1Rad) * kotlin.math.sin(lat2Rad) -
                        kotlin.math.sin(lat1Rad) * kotlin.math.cos(lat2Rad) * kotlin.math.cos(dLonRad)

                    return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
                }

                val p1 = lireRtkJson()
                Thread.sleep(1000)
                val p2 = lireRtkJson()

                val lat1 = p1.optDouble("lat", Double.NaN)
                val lon1 = p1.optDouble("lon", Double.NaN)
                val lat2 = p2.optDouble("lat", Double.NaN)
                val lon2 = p2.optDouble("lon", Double.NaN)

                val distanceSubjectM = distanceMetres(lat1, lon1, lat2, lon2)
                val deltaTS = 1.0
                val speedMps = distanceSubjectM / deltaTS
                val speedKmh = speedMps * 3.6

                val predictionTimeS = 2.0

                val predictedSubjectLat = lat2 + ((lat2 - lat1) / deltaTS) * predictionTimeS
                val predictedSubjectLon = lon2 + ((lon2 - lon1) / deltaTS) * predictionTimeS

                val present = p2.optBoolean("present", false)
                val subjectAltM = p2.optDouble("alt_m", Double.NaN)
                val rtk = p2.optString("rtk", "")
                val ageS = p2.optDouble("age_s", Double.NaN)
                val hz = p2.optDouble("hz", Double.NaN)
                val gpsHdg = p2.optDouble("hdg", 0.0)

                val movementHeadingDeg = if (distanceSubjectM > 0.20) {
                    bearingDeg(lat1, lon1, lat2, lon2)
                } else {
                    gpsHdg
                }

                val headingSource = if (distanceSubjectM > 0.20) "movement_vector" else "gps_hdg"

                val rtkNorm = rtk.trim().uppercase()
                val subjectReady =
                    present &&
                    !ageS.isNaN() &&
                    ageS < 2.0 &&
                    (rtkNorm.contains("FIX") || rtkNorm.contains("FLOAT"))

                val reason = when {
                    !present -> "MISSING"
                    ageS.isNaN() -> "AGE_MISSING"
                    ageS >= 2.0 -> "STALE"
                    rtkNorm.contains("FIX") -> "RTK_FIX"
                    rtkNorm.contains("FLOAT") -> "RTK_FLOAT"
                    rtkNorm == "GPS" -> "GPS_ONLY"
                    else -> "LOW_QUALITY"
                }

                val distanceM = 4.0
                val behindBearingDeg = (movementHeadingDeg + 180.0) % 360.0
                val bearingRad = Math.toRadians(behindBearingDeg)

                val metersPerDegLat = 111111.0
                val metersPerDegLon = 111111.0 * kotlin.math.cos(Math.toRadians(predictedSubjectLat))

                val droneLat = predictedSubjectLat + (distanceM * kotlin.math.cos(bearingRad)) / metersPerDegLat
                val droneLon = predictedSubjectLon + (distanceM * kotlin.math.sin(bearingRad)) / metersPerDegLon
                val droneAltM = if (subjectAltM.isNaN()) Double.NaN else subjectAltM + 5.0

                val yawCameraDeg = bearingDeg(droneLat, droneLon, predictedSubjectLat, predictedSubjectLon)

                val horizontalDistanceM = distanceM
                val verticalDeltaM = if (subjectAltM.isNaN() || droneAltM.isNaN()) Double.NaN else droneAltM - subjectAltM
                val gimbalPitchDeg = if (verticalDeltaM.isNaN()) {
                    -10.0
                } else {
                    -Math.toDegrees(kotlin.math.atan2(verticalDeltaM, horizontalDistanceM))
                }

                val ackUrl = java.net.URL("https://cineflight.ca/api/mouvement/ack")
                val ackConnection = ackUrl.openConnection() as java.net.HttpURLConnection
                ackConnection.requestMethod = "POST"
                ackConnection.connectTimeout = 5000
                ackConnection.readTimeout = 5000
                ackConnection.doOutput = true
                ackConnection.setRequestProperty("Content-Type", "application/json")

                val payload = org.json.JSONObject().apply {
                    put("source", "android")
                    put("device_id", "samsung_SM_A546W")
                    put("ok", true)
                    put("android_status", if (subjectReady) "prediction_preview_ready" else "prediction_blocked")
                    put("message", if (subjectReady) "RESTORE_NORMAL_001 - Prediction sujet 2s + preview drone 4m - simulation seulement" else "Prediction bloquee: sujet non pret")
                    put("safety_mode", "simulation_only")
                    put("movement_allowed", false)
                    put("preview_valid", subjectReady)
                    put("preview_valid", subjectReady)

                    put("subject_ready", subjectReady)
                    put("reason", reason)
                    put("rtk", rtk)
                    put("age_s", ageS)
                    put("hz", hz)

                    put("lat1", lat1)
                    put("lon1", lon1)
                    put("lat2", lat2)
                    put("lon2", lon2)

                    put("distance_subject_m", distanceSubjectM)
                    put("speed_mps", speedMps)
                    put("speed_kmh", speedKmh)
                    put("prediction_time_s", predictionTimeS)

                    put("predicted_subject_lat", predictedSubjectLat)
                    put("predicted_subject_lon", predictedSubjectLon)
                    put("subject_alt_m", subjectAltM)

                    put("movement_heading_deg", movementHeadingDeg)
                    put("heading_source", headingSource)
                    put("behind_bearing_deg", behindBearingDeg)

                    put("distance_m", distanceM)
                    put("drone_preview_lat", droneLat)
                    put("drone_preview_lon", droneLon)
                    put("drone_preview_alt_m", droneAltM)

                    put("yaw_camera_deg", yawCameraDeg)
                    put("gimbal_pitch_deg", gimbalPitchDeg)
                    put("horizontal_distance_m", horizontalDistanceM)
                    put("vertical_delta_m", verticalDeltaM)
                }.toString()

                ackConnection.outputStream.use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                    output.flush()
                }

                ackConnection.responseCode
                ackConnection.disconnect()

                android.util.Log.i("CineFlightPredict", "Prediction preview speed=${speedKmh} km/h ready=$subjectReady")

            } catch (e: Exception) {
                android.util.Log.e("CineFlightPredict", "Erreur prediction preview", e)
            }
        }.start()
    }

    private fun demarrerPredictionContinue4mLogin() {
        Thread {
            for (i in 1..15) {
                testerPredictionSujet4mLogin()
                Thread.sleep(2000)
            }
        }.start()
    }

    
}





























