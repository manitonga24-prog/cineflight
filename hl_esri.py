# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()
if "World_Imagery" in s:
    print("DEJA present"); raise SystemExit

# imports
if "import org.osmdroid.tileprovider.tilesource.XYTileSource" not in s:
    s = s.replace(
        "import org.osmdroid.tileprovider.tilesource.TileSourceFactory",
        "import org.osmdroid.tileprovider.tilesource.TileSourceFactory\n" +
        "import org.osmdroid.tileprovider.tilesource.XYTileSource\n" +
        "import org.osmdroid.tileprovider.tilesource.TileSourcePolicy\n" +
        "import org.osmdroid.util.MapTileIndex",
        1)

# source Esri (format ZYX -> on construit l'URL nous-memes via getTileURLString)
anc = "    private fun lancerTelechargementHorsLigne() {"
helper = '''    /** Esri World Imagery (satellite) - autorise le telechargement, couvre le Canada. Format Z/Y/X. */
    private fun sourceEsriSatellite(): org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase {
        return object : XYTileSource(
            "EsriWorldImagery", 0, 19, 256, "",
            arrayOf("https://services.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/tile/"),
            "(c) Esri, Maxar, Earthstar Geographics",
            TileSourcePolicy(2,
                TileSourcePolicy.FLAG_TILE_SOURCE_PERMITS_REQUEST_MODIFIERS
                    or TileSourcePolicy.FLAG_TILE_SOURCE_PERMITS_USER_AGENT_MODIFIERS
                    or TileSourcePolicy.FLAG_TILE_SOURCE_PERMITS_BULK_DOWNLOAD)
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return baseUrl + z + "/" + y + "/" + x
            }
        }
    }

    private fun lancerTelechargementHorsLigne() {'''
s = s.replace(anc, helper, 1)

# utiliser Esri pour le download (remplace sourceTelechargeable s'il existe, sinon insere)
if "carte.setTileSource(sourceTelechargeable())" in s:
    s = s.replace("carte.setTileSource(sourceTelechargeable())", "carte.setTileSource(sourceEsriSatellite())", 1)
else:
    s = s.replace(
        '''        val cm = CacheManager(carte)
        cm.downloadAreaAsync(this, bb, zMin, zMax,''',
        '''        val sourceAffichage = carte.tileProvider.tileSource
        carte.setTileSource(sourceEsriSatellite())
        val cm = CacheManager(carte)
        cm.downloadAreaAsync(this, bb, zMin, zMax,''', 1)
    s = s.replace(
        '''            override fun onTaskComplete() {
                progress.dismiss()
                sauvegarderCarteNommee''',
        '''            override fun onTaskComplete() {
                progress.dismiss()
                carte.setTileSource(sourceAffichage)
                sauvegarderCarteNommee''', 1)
    s = s.replace(
        '''            override fun onTaskFailed(errors: Int) {
                progress.dismiss()''',
        '''            override fun onTaskFailed(errors: Int) {
                progress.dismiss()
                carte.setTileSource(sourceAffichage)''', 1)

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("source Esri satellite pour telechargement OK")