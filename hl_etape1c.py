# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "fun ouvrirPanneauHorsLigne" in s:
    print("DEJA present"); raise SystemExit

# inserer les methodes avant placerDecollage
anc = "    private fun placerDecollage() {"
methodes = '''    private fun dpx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun ouvrirPanneauHorsLigne() {
        suiviAuto = false
        panneauHL.visibility = View.VISIBLE
        majCercleEtEstimation()
    }

    private fun fermerPanneauHorsLigne() {
        panneauHL.visibility = View.GONE
        cercleHorsLigne?.let { carte.overlays.remove(it) }
        cercleHorsLigne = null
        carte.invalidate()
    }

    /** Redessine le cercle centre sur la vue + met a jour l'estimation (tuiles/Mo/temps). */
    private fun majCercleEtEstimation() {
        val centre = carte.mapCenter as GeoPoint
        // cercle
        cercleHorsLigne?.let { carte.overlays.remove(it) }
        val pts = Polygon.pointsAsCircle(centre, rayonHorsLigneM)
        cercleHorsLigne = Polygon().apply {
            points = pts
            fillPaint.color = 0x224FC3F7
            outlinePaint.color = 0xFF4FC3F7.toInt()
            outlinePaint.strokeWidth = 5f
        }
        carte.overlays.add(cercleHorsLigne)
        carte.invalidate()
        // estimation : nb tuiles pour la bounding box du cercle, niveaux 14..18
        val bb = boundingBoxDuCercle(centre, rayonHorsLigneM)
        var totalTuiles = 0L
        for (z in 14..18) totalTuiles += tuilesPourBox(bb, z)
        val mo = totalTuiles * 14.0 / 1024.0   // ~14 Ko par tuile
        val minutes = Math.ceil(totalTuiles / 15.0 / 60.0).toInt()   // ~15 tuiles/s
        val rayonKm = rayonHorsLigneM / 1000.0
        lblEstimation.text = String.format("Rayon %.1f km  -  ~%d Mo  -  ~%d min  (%d tuiles)",
            rayonKm, Math.round(mo), Math.max(1, minutes), totalTuiles)
    }

    private fun boundingBoxDuCercle(centre: GeoPoint, rayonM: Double): BoundingBox {
        val dLat = Math.toDegrees(rayonM / 6371000.0)
        val dLon = Math.toDegrees(rayonM / (6371000.0 * Math.cos(Math.toRadians(centre.latitude))))
        return BoundingBox(centre.latitude + dLat, centre.longitude + dLon,
            centre.latitude - dLat, centre.longitude - dLon)
    }

    private fun tuilesPourBox(bb: BoundingBox, zoom: Int): Long {
        val n = Math.pow(2.0, zoom.toDouble())
        fun xTile(lon: Double) = ((lon + 180.0) / 360.0 * n).toLong()
        fun yTile(lat: Double): Long {
            val r = Math.toRadians(lat)
            return ((1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * n).toLong()
        }
        val x1 = xTile(bb.lonWest); val x2 = xTile(bb.lonEast)
        val y1 = yTile(bb.latNorth); val y2 = yTile(bb.latSouth)
        return (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1)
    }

    private fun lancerTelechargementHorsLigne() {
        // (etape 2 : telechargement reel via CacheManager)
        android.widget.Toast.makeText(this, "Telechargement a venir (etape 2)", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun placerDecollage() {'''

if anc in s:
    s = s.replace(anc, methodes, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("methodes hors ligne (cercle + estimation) OK")
else:
    print("ANCRE NON TROUVEE")