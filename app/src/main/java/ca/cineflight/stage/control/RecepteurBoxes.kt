package ca.cineflight.stage.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * RecepteurBoxes — ecoute les boites de detection YOLO envoyees par yolo_pose.py
 * sur le port UDP 9103.
 *
 * Format JSON attendu (emis par EmetteurBoxes.emettre) :
 *   {"t": <ms>, "boxes": [{"x":0.3,"y":0.2,"w":0.1,"h":0.3,"conf":0.9,"sel":true}, ...]}
 *   x,y = coin haut-gauche NORMALISE (0-1) ; w,h = largeur/hauteur normalisees.
 *   sel = true pour la personne choisie comme danseur (mise en evidence).
 *
 * Comme RecepteurBridge : UDP, perte de paquets toleree. On expose la derniere
 * liste de boites recue ; l'overlay la lit et se redessine.
 */
class RecepteurBoxes(
    private val port: Int = 9103,
    private val onBoxes: (List<Box>, Long) -> Unit
) {
    /** Une boite de detection en coordonnees normalisees 0-1. */
    data class Box(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val conf: Float, val sel: Boolean
    )

    private var socket: DatagramSocket? = null
    private var job: Job? = null

    @Volatile var derniereReception: Long = 0L
        private set

    fun demarrer(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val s = DatagramSocket(port).apply { soTimeout = 500 }
            socket = s
            val buf = ByteArray(8192)   // plusieurs boites possibles
            while (isActive) {
                val paquet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(paquet)
                    val texte = String(paquet.data, 0, paquet.length, Charsets.UTF_8)
                    val (boxes, tEmis) = parser(texte) ?: continue
                    derniereReception = System.currentTimeMillis()
                    onBoxes(boxes, tEmis)
                } catch (e: SocketTimeoutException) {
                    // aucun paquet : si le silence dure, l'overlay videra les boites
                    onBoxes(emptyList(), 0L)
                } catch (e: Exception) {
                    // paquet malforme : on ignore
                }
            }
            try { s.close() } catch (_: Exception) {}
        }
    }

    fun arreter() {
        job?.cancel()
        job = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun parser(texte: String): Pair<List<Box>, Long>? {
        return try {
            val j = JSONObject(texte)
            val tEmis = j.optLong("t", 0L)
            val arr = j.optJSONArray("boxes") ?: return Pair(emptyList(), tEmis)
            val liste = ArrayList<Box>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                liste.add(
                    Box(
                        x = o.optDouble("x", 0.0).toFloat(),
                        y = o.optDouble("y", 0.0).toFloat(),
                        w = o.optDouble("w", 0.0).toFloat(),
                        h = o.optDouble("h", 0.0).toFloat(),
                        conf = o.optDouble("conf", 0.0).toFloat(),
                        sel = o.optBoolean("sel", false)
                    )
                )
            }
            Pair(liste, tEmis)
        } catch (e: Exception) {
            null
        }
    }
}

