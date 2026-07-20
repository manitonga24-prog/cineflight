# -*- coding: utf-8 -*-
f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\CarteActivity.kt"
s = open(f, encoding="utf-8").read()

vieux = '''    /** Esri World Imagery (satellite) - autorise le telechargement, couvre le Canada. Format Z/Y/X. */
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
    }'''

neuf = '''    /** Esri World Imagery (satellite) - autorise le telechargement, couvre le Canada. Format Z/Y/X. */
    private fun sourceEsriSatellite(): org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase {
        return object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
            "EsriWorldImagery", 0, 19, 256, "",
            arrayOf("https://services.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/tile/"),
            "(c) Esri, Maxar, Earthstar Geographics"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return baseUrl + z + "/" + y + "/" + x
            }
        }
    }'''

if vieux in s:
    s = s.replace(vieux, neuf, 1)
    # retirer l'import TileSourcePolicy devenu inutile
    s = s.replace("import org.osmdroid.tileprovider.tilesource.TileSourcePolicy\n", "", 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("source Esri simplifiee (OnlineTileSourceBase) OK")
else:
    print("ANCRE NON TROUVEE")