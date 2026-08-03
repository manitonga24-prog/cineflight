package ca.cineflight.stage.control

/**
 * CapacitesDrone - deduit les capacites d'un drone DJI a partir de la chaine
 * de modele renvoyee par le SDK (ProductType.toString(), ex. "DJI_MINI_3",
 * "DJI_MINI_4_PRO", "DJI_AIR_3", "MAVIC_3"...).
 *
 * Objectif : adapter le comportement de l'app aux capacites REELLES du drone.
 * Le point critique ici est l'EVITEMENT D'OBSTACLES, en particulier vers
 * l'ARRIERE, car un plan "de face en reculant" envoie le drone a reculons.
 *
 * Classement prudent : si le modele est inconnu, on suppose AUCUN evitement
 * (le plus sur). La detection se fait par mots-cles, insensible a la casse,
 * pour resister aux variations de nommage du SDK.
 */
object CapacitesDrone {

    enum class Evitement {
        AUCUN,      // pas de capteurs (Mini 3, Mini 2, Mini SE, Neo de base...)
        PARTIEL,    // capteurs avant/arriere ou bas seulement (Mini 3 Pro, Air 2S...)
        COMPLET     // omnidirectionnel, arriere couvert (Mini 4 Pro, Air 3, Mavic 3, Mini 5 Pro...)
    }

    /** Capacites deduites d'une chaine de modele. */
    data class Profil(
        val modeleBrut: String,
        val evitement: Evitement,
        val nomLisible: String
    ) {
        /** Le drone peut-il reculer en autonomie avec une marge de securite ? */
        val reculAutonomeSur: Boolean get() = evitement == Evitement.COMPLET
        /** Plafond de vitesse de recul conseille (m/s) selon l'evitement. */
        val plafondReculMps: Float get() = when (evitement) {
            Evitement.COMPLET -> 2.0f   // capteurs arriere : marge plus large
            Evitement.PARTIEL -> 1.2f   // prudence : un peu plus que le minimum
            Evitement.AUCUN   -> 0.8f   // recul a l'aveugle : on garde le garde-fou d'origine
        }
    }

    // Mots-cles -> evitement. Ordre : du plus specifique au plus general.
    // On teste d'abord les modeles a evitement COMPLET, puis PARTIEL, sinon AUCUN.
    private val COMPLET = listOf(
        "MINI_4", "MINI4", "MINI_5", "MINI5",
        "AIR_3", "AIR3", "AIR_2S",   // Air 2S a un evitement haut/bas/avant ; classe ici par prudence haute
        "MAVIC_3", "MAVIC3", "MAVIC_2", "MAVIC2",
        "AVATA",
        "MATRICE", "M30", "M300", "M350", "M400", "M4E", "M4T", "M4D",   // Matrice : omnidirectionnel
        "M3E", "M3T", "M3M"   // Mavic 3 Enterprise : omnidirectionnel
    )
    private val PARTIEL = listOf(
        "MINI_3_PRO", "MINI3PRO", "MINI_3PRO", "MINI3_PRO",
        "AIR_2", "AIR2"
    )
    // Tout le reste (MINI_3 simple, MINI_2, MINI_SE, NEO, FLIP...) -> AUCUN

    fun analyser(modeleBrut: String?): Profil {
        val m = (modeleBrut ?: "").uppercase().replace(" ", "_")
        if (m.isEmpty() || m == "SIMULATEUR" || m == "DRONE_CONNECTE") {
            return Profil(modeleBrut ?: "", Evitement.AUCUN, "Drone")
        }
        // PARTIEL teste AVANT COMPLET pour eviter que "MINI_3_PRO" matche un sous-mot,
        // et car "MINI_3" simple ne doit PAS heriter du Pro.
        val evitement = when {
            PARTIEL.any { m.contains(it) } -> Evitement.PARTIEL
            COMPLET.any { m.contains(it) } -> Evitement.COMPLET
            else -> Evitement.AUCUN
        }
        return Profil(modeleBrut ?: "", evitement, nomLisible(m))
    }

    /**
     * MISSIONS WAYLINE NATIVES (WaypointMissionManager / pushKMZFileToAircraft) :
     * réservées aux drones ENTERPRISE par le MSDK v5. Les drones GRAND PUBLIC
     * (Mini/Air/Mavic consommateur, dont le Mini 4 Pro) NE les supportent PAS —
     * l'upload échoue et la mission « ne peut pas être exécutée » (audit 2026-07-25).
     * Pour eux, l'app joue le KMZ elle-même en Virtual Stick (LecteurMissionKmz).
     * Fail-closed : modèle inconnu -> false (chemin Virtual Stick, toujours disponible).
     */
    fun supporteWaylinesNatives(modeleBrut: String?): Boolean {
        val m = (modeleBrut ?: "").uppercase().replace(" ", "_")
        return listOf("MATRICE", "M30", "M300", "M350", "M400",
                      "M3E", "M3T", "M3M", "M3D", "M4E", "M4T", "M4D")
            .any { m.contains(it) }
    }

    /** Code drone attendu par la meteo serveur (SEUILS_VENT) : mini3 | mini4 | air3 | mavic3.
     *  Repli prudent sur "mini3" (seuils les plus bas) pour tout modele inconnu. */
    fun codeMeteo(modeleBrut: String?): String {
        val m = (modeleBrut ?: "").uppercase().replace(" ", "_")
        return when {
            // Gros porteurs / entreprise : meilleure tenue au vent.
            m.contains("MATRICE") || m.contains("M30") || m.contains("M300") || m.contains("M350") || m.contains("M400") || m.contains("M4E") || m.contains("M4T") || m.contains("M4D") || m.contains("MAVIC") || m.contains("M3E") || m.contains("M3T") || m.contains("M3M") -> "mavic3"
            m.contains("AIR") -> "air3"
            m.contains("MINI_4") || m.contains("MINI4") || m.contains("MINI_5") || m.contains("MINI5") || m.contains("FLIP") -> "mini4"
            // Mini 3/2/SE, Neo, Avata, FPV, ou modele inconnu -> seuils les plus prudents.
            else -> "mini3"
        }
    }

    /** Code profil attendu par la generation de mission serveur : mini4pro | air3 | m30.
     *  Conservateur : Mini ou inconnu -> "mini4pro" (profil le plus prudent, = defaut serveur). */
    fun codeMission(modeleBrut: String?): String {
        val m = (modeleBrut ?: "").uppercase().replace(" ", "_")
        return when {
            m.contains("MATRICE") || m.contains("M30") || m.contains("M300") || m.contains("M350") || m.contains("M400") || m.contains("M4E") || m.contains("M4T") || m.contains("M4D") -> "m30"
            m.contains("AIR") || m.contains("MAVIC") || m.contains("M3E") || m.contains("M3T") || m.contains("M3M") -> "air3"
            // Mini*, Neo, Flip, Avata, FPV, ou modele inconnu -> profil le plus prudent.
            else -> "mini4pro"
        }
    }

    /** Plage de temperature de fonctionnement (min, max en C) selon le fabricant DJI.
     *  Grosses plateformes Matrice 30/300/350/400 : -20 a 50 ; tout le reste : -10 a 40. */
    fun plageTemp(modeleBrut: String?): Pair<Int, Int> {
        val m = (modeleBrut ?: "").uppercase().replace(" ", "_")
        val lourd = m.contains("M30") || m.contains("M300") || m.contains("M350") || m.contains("M400") ||
            m.contains("MATRICE_30") || m.contains("MATRICE_300") ||
            m.contains("MATRICE_350") || m.contains("MATRICE_400")
        return if (lourd) Pair(-20, 50) else Pair(-10, 40)
    }

    private fun nomLisible(m: String): String {
        // Rend la chaine SDK un peu plus lisible pour l'affichage.
        return m.removePrefix("DJI_").replace("_", " ").trim().ifEmpty { "Drone" }
    }
}

