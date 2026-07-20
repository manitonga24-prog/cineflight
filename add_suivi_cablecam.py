# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\CableCam.kt"
s = open(f, encoding="utf-8").read()
if "calculerAvecSuivi" in s:
    print("DEJA present"); raise SystemExit

# ancre : la fin de calculer() = la ligne return floatArrayOf(vx, 0f, vz, yawRate) suivie de } puis } final
anc = """        return floatArrayOf(vx, 0f, vz, yawRate)
    }
}"""

ajout = """        return floatArrayOf(vx, 0f, vz, yawRate)
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
}"""

if anc in s:
    s = s.replace(anc, ajout, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("calculerAvecSuivi ajoute OK")
else:
    print("ANCRE NON TROUVEE")