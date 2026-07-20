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
 * RecepteurBridge — écoute les commandes Virtual Stick envoyées par le bridge
 * CineFlight sur le port UDP 9100.
 *
 * Format JSON attendu (émis par stage_bridge.CommandeVS.to_json) :
 *   {"t":..., "vx":..., "vy":..., "vz":..., "yaw_rate":..., "mode":"actif"|"stop",
 *    "gimbal_pitch":..., "gimbal_yaw":..., "cadrage":bool, "rec":bool}
 *
 * Conçu pour la sécurité : chaque paquet est horodaté à sa réception. Si plus
 * aucun paquet n'arrive (timeout), le pilote passe en hover — voir PiloteDrone.
 * On accepte la perte de paquets (UDP) : le bridge émet à 15 Hz, bien au-delà
 * du minimum Virtual Stick (~10 Hz).
 */
class RecepteurBridge(
    private val port: Int = 9100,
    private val onCommande: (CommandeBridge) -> Unit
) {
    /** Une commande reçue du bridge (repère scène OpenVR). */
    data class CommandeBridge(
        val t: Double,
        val vx: Float,
        val vy: Float,
        val vz: Float,
        val yawRate: Float,
        val mode: String,            // "actif" ou "stop"
        val recuA: Long,             // horodatage réception (ms, monotone)
        // --- cadrage caméra/nacelle ---
        val gimbalPitch: Float = 0f,
        val gimbalYaw: Float = 0f,
        val cadrage: Boolean = false,
        val rec: Boolean = false
    )
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    @Volatile var derniereReception: Long = 0L
        private set
    @Volatile var nbRecus: Long = 0L
        private set
    // Dernière commande reçue, exposée pour l'affichage cockpit (suivi YOLO).
    @Volatile var derniereCommande: CommandeBridge? = null
        private set
    fun demarrer(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val s = DatagramSocket(port).apply { soTimeout = 500 }
            socket = s
            val buf = ByteArray(1024)
            while (isActive) {
                val paquet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(paquet)
                    val texte = String(paquet.data, 0, paquet.length, Charsets.UTF_8)
                    val cmd = parser(texte) ?: continue
                    derniereReception = cmd.recuA
                    nbRecus++
                    derniereCommande = cmd
                    onCommande(cmd)
                } catch (e: SocketTimeoutException) {
                    // aucun paquet pendant 500 ms : la boucle continue ; c'est le
                    // PiloteDrone qui détecte le timeout et passe en hover.
                } catch (e: Exception) {
                    // paquet malformé / socket : on ignore et on continue
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
    private fun parser(texte: String): CommandeBridge? {
        return try {
            val j = JSONObject(texte)
            CommandeBridge(
                t = j.optDouble("t", 0.0),
                vx = j.optDouble("vx", 0.0).toFloat(),
                vy = j.optDouble("vy", 0.0).toFloat(),
                vz = j.optDouble("vz", 0.0).toFloat(),
                yawRate = j.optDouble("yaw_rate", 0.0).toFloat(),
                mode = j.optString("mode", "stop"),
                recuA = System.currentTimeMillis(),
                gimbalPitch = j.optDouble("gimbal_pitch", 0.0).toFloat(),
                gimbalYaw = j.optDouble("gimbal_yaw", 0.0).toFloat(),
                cadrage = j.optBoolean("cadrage", false),
                rec = j.optBoolean("rec", false)
            )
        } catch (e: Exception) {
            null
        }
    }
}



