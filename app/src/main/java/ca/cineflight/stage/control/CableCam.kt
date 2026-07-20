package ca.cineflight.stage.control

/**
 * CableCam - "rail virtuel" : le drone glisse en ligne droite d'un point A a un point B.
 * On memorise A (position courante), on pilote jusqu'a B, on memorise B, puis "go".
 * Produit les vitesses (comme retourAncre) pour avancer le long du rail a vitesse reguliere.
 *
 * N'utilise PAS YOLO : navigation GPS pure (mariage avec le suivi possible plus tard).
 */
class CableCam {

    data class Point(val lat: Double, val lon: Double, val alt: Double, val cap: Float)

    @Volatile var pointA: Point? = null; private set
    @Volatile var pointB: Point? = null; private set
    @Volatile var enCours = false; private set

    /** Vitesse de croisiere sur le rail (m/s "normalise" cockpit, 0..1). */
    var vitesse = 0.4f

    fun memoriserA(lat: Double, lon: Double, alt: Double, cap: Float) {
        pointA = Point(lat, lon, alt, cap)
    }
    fun memoriserB(lat: Double, lon: Double, alt: Double, cap: Float) {
        pointB = Point(lat, lon, alt, cap)
    }
    fun pret(): Boolean = pointA != null && pointB != null

    fun demarrer() { if (pret()) enCours = true }
    fun arreter() { enCours = false }
    fun reinitialiser() { pointA = null; pointB = null; enCours = false }

    /**
     * Calcule les vitesses pour glisser vers B. Renvoie null quand arrive (= fin du rail).
     * @return floatArray [vx, vy, vz, yawRate] ou null si arrive/pas pret
     */
    fun calculer(latActuel: Double, lonActuel: Double, altActuel: Double, capActuel: Float): FloatArray? {
        val b = pointB ?: return null
        if (!enCours) return null

        // distance et bearing vers B (meme math que retourAncre)
        val lat1 = Math.toRadians(latActuel)
        val lon1 = Math.toRadians(lonActuel)
        val lat2 = Math.toRadians(b.lat)
        val lon2 = Math.toRadians(b.lon)
        val dLon = lon2 - lon1
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val bearing = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
        val R = 6371000.0
        val dx = dLon * Math.cos((lat1 + lat2) / 2)
        val dy = lat2 - lat1
        val dist = Math.sqrt(dx * dx + dy * dy) * R

        // arrive ?
        if (dist < 1.5) { enCours = false; return null }

        // ecart de cap drone -> direction de B
        val capD = if (capActuel.isNaN()) 0.0 else capActuel.toDouble()
        var ecartCap = bearing - capD
        while (ecartCap > 180) ecartCap -= 360
        while (ecartCap < -180) ecartCap += 360

        // yaw doux pour pointer vers B
        val yawRate = (ecartCap * 0.5).coerceIn(-15.0, 15.0).toFloat()

        // vitesse avant : pleine si oriente vers B, ralentit a l'approche (derniers 4 m)
        val facteurApproche = (dist / 4.0).coerceIn(0.25, 1.0).toFloat()
        val vx = if (Math.abs(ecartCap) < 50) vitesse * facteurApproche else 0f

        // altitude : rejoindre celle de B en douceur
        val dAlt = b.alt - altActuel
        val vz = (dAlt * 0.3).coerceIn(-0.4, 0.4).toFloat()

        return floatArrayOf(vx, 0f, vz, yawRate)
    }

    /**
     * Comme calculer(), mais garde le SUJET cadre pendant le glissement vers B.
     * Le drone glisse vers B (translation), mais son NEZ pivote vers le sujet (yaw via errX YOLO).
     * La translation est decomposee en vx/vy selon l'ecart entre le cap (vers sujet) et la direction de B.
     * @param cx position horizontale du sujet 0..1 (du YOLO) ; 0.5 = centre
     * @param cy position verticale du sujet 0..1 ; 0.5 = centre
     * @return [vx, vy, vz, yawRate, gimbalPitch] ou null si arrive/pas pret
     */
    fun calculerAvecSuivi(latActuel: Double, lonActuel: Double, altActuel: Double,
                          capActuel: Float, cx: Float, cy: Float): FloatArray? {
        val b = pointB ?: return null
        if (!enCours) return null
        // distance et bearing vers B (meme math que calculer)
        val lat1 = Math.toRadians(latActuel)
        val lon1 = Math.toRadians(lonActuel)
        val lat2 = Math.toRadians(b.lat)
        val lon2 = Math.toRadians(b.lon)
        val dLon = lon2 - lon1
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val bearing = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
        val R = 6371000.0
        val dx = dLon * Math.cos((lat1 + lat2) / 2)
        val dy = lat2 - lat1
        val dist = Math.sqrt(dx * dx + dy * dy) * R
        if (dist < 1.5) { enCours = false; return null }

        // YAW = suivre le sujet (meme logique que calculerSuivi : errX -> yawRate)
        val errX = cx - 0.5f
        val yawRate = (errX * 40f).coerceIn(-25f, 25f)
        // GIMBAL pitch = suivre le sujet en hauteur (errY)
        val errY = cy - 0.5f
        val gimbalPitch = (-errY * 25f).coerceIn(-20f, 20f)

        // TRANSLATION vers B, decomposee dans le repere du drone (nez = cap actuel vers sujet).
        // vitesse globale : pleine, ralentit a l'approche (derniers 4 m)
        val facteurApproche = (dist / 4.0).coerceIn(0.25, 1.0).toFloat()
        val v = vitesse * facteurApproche
        // angle entre le nez du drone et la direction de B
        val capD = if (capActuel.isNaN()) 0.0 else capActuel.toDouble()
        var angle = bearing - capD
        while (angle > 180) angle -= 360
        while (angle < -180) angle += 360
        val angleRad = Math.toRadians(angle)
        // vx = composante avant, vy = composante laterale (droite +)
        val vx = (v * Math.cos(angleRad)).toFloat()
        val vy = (v * Math.sin(angleRad)).toFloat()
        // altitude : rejoindre celle de B en douceur
        val dAlt = b.alt - altActuel
        val vz = (dAlt * 0.3).coerceIn(-0.4, 0.4).toFloat()

        return floatArrayOf(vx, vy, vz, yawRate, gimbalPitch)
    }

    /** Distance restante (m) jusqu'a B depuis la position courante. -1 si pas de B. */
    fun distanceVersB(latActuel: Double, lonActuel: Double): Double {
        val b = pointB ?: return -1.0
        val lat1 = Math.toRadians(latActuel)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - lonActuel)
        val R = 6371000.0
        val dx = dLon * Math.cos((lat1 + lat2) / 2)
        val dy = lat2 - lat1
        return Math.sqrt(dx * dx + dy * dy) * R
    }
}
