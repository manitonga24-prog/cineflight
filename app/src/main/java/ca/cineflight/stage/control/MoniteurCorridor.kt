package ca.cineflight.stage.control

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * MoniteurCorridor — surveille si le sujet mobile (RTK) reste dans le CORRIDOR
 * VALIDE de la mission (doctrine "chorégraphie dans une enveloppe de vol sûre").
 *
 * >>> DOCTRINE (confirmée) <<<
 * Le drone NE poursuit PAS le sujet hors de la zone analysée. Le sujet peut sortir
 * du plan ; le drone reste dans le corridor validé. Ce moniteur PROTEGE LE VOL
 * avant le cadrage.
 *
 * Ce module est PUR : il compare "position réelle RTK" vs "trajectoire prévue",
 * produit un STATUT + une action recommandée. Il NE commande jamais le drone :
 * la décision finale (blocage, pause, RTH) reste au NoyauSecurite, qui reçoit le
 * statut via la couche appelante (comme rtkSujetOk).
 *
 * Trajectoire prévue = polyligne (V1 : 2 points = ligne droite ; N points ensuite).
 * ECART LATERAL = distance perpendiculaire du sujet a la trajectoire = CRITERE DE
 * SECURITE PRINCIPAL. Ecart longitudinal (avance/retard le long de la ligne) =
 * secondaire (cadence/cadrage), n'arrete pas la mission a lui seul.
 *
 * Testable seul en JVM (aucune dépendance Android/DJI).
 */
class MoniteurCorridor(private val cfg: Config = Config()) {

    // seuils effectifs (peuvent être remplacés par ceux du serveur via appliquerSeuils).
    private var seuilNormal = cfg.seuilNormalM
    private var seuilTolerance = cfg.seuilToleranceM
    private var seuilPrudence = cfg.seuilPrudenceM

    /** Applique les seuils fournis par le serveur (source unique : mêmes valeurs
     *  que le corridor validé dans preview3d). Ignore les valeurs incohérentes. */
    fun appliquerSeuils(normalM: Double, toleranceM: Double, prudenceM: Double) {
        if (normalM > 0 && toleranceM > normalM && prudenceM > toleranceM) {
            seuilNormal = normalM
            seuilTolerance = toleranceM
            seuilPrudence = prudenceM
        }
    }

    /** Seuils d'ecart LATERAL (m). Paramétrables : dependent de la vitesse, du lieu,
     *  de l'eau, des obstacles, de la distance drone-sujet. Valeurs par defaut =
     *  point de depart raisonnable, a calibrer terrain. */
    data class Config(
        val seuilNormalM: Double = 10.0,       // 0..10  : NORMAL
        val seuilToleranceM: Double = 25.0,    // 10..25 : TOLERANCE (cadrage)
        val seuilPrudenceM: Double = 40.0,     // 25..40 : PRUDENCE (ne pas poursuivre)
        // > seuilPrudence : HORS_CORRIDOR (interrompre / regenerer)
        /** Ecart LONGITUDINAL (avance/retard, m) au-dela duquel on signale un
         *  probleme de CADENCE (avertissement cadrage, PAS un arret). */
        val seuilCadenceM: Double = 30.0,
        /** Age max de la position RTK avant RTK_PERDU (s). */
        val ageRtkMaxS: Double = 3.0
    )

    /** Statut de securite du corridor (du plus sûr au plus critique). */
    enum class Statut {
        NORMAL,          // bateau dans le corridor : mission continue
        TOLERANCE,       // leger ecart : cadrage possiblement moins bon
        PRUDENCE,        // ecart important : garder mission, NE PAS poursuivre hors corridor
        HORS_CORRIDOR,   // sujet hors enveloppe : interrompre / mettre en attente / regenerer
        RTK_PERDU        // position sujet perdue : statut securite, pas de poursuite auto
    }

    /** Action recommandée (informative ; l'action reelle est decidee par le noyau/pilote). */
    enum class Action {
        CONTINUER,           // NORMAL
        AJUSTER_CADRAGE,     // TOLERANCE : yaw/gimbal vers le sujet, avertir pilote
        MAINTENIR_NE_PAS_POURSUIVRE, // PRUDENCE : tenir le corridor, ne pas sortir
        INTERROMPRE_OU_REGENERER,    // HORS_CORRIDOR
        PAUSE_OU_RTH         // RTK_PERDU (si non securitaire de continuer)
    }

    /** Un point WGS84 (lat, lon). */
    data class Point(val lat: Double, val lon: Double)

    /**
     * Resultat d'evaluation a un instant donne.
     *  - ecartLateralM : distance perpendiculaire du sujet a la trajectoire (securite).
     *  - ecartLongitudinalM : avance(+)/retard(-) le long de la trajectoire vs prevu (cadence).
     *  - dansCorridor : true tant que le statut n'est ni HORS_CORRIDOR ni RTK_PERDU.
     *  - avertissementCadence : true si |ecartLongitudinal| depasse le seuil (cadrage).
     */
    data class Resultat(
        val statut: Statut,
        val action: Action,
        val ecartLateralM: Double,
        val ecartLongitudinalM: Double,
        val dansCorridor: Boolean,
        val avertissementCadence: Boolean,
        val message: String
    )

    /**
     * Evalue le statut du corridor.
     *
     * @param trajectoire polyligne prevue (>= 2 points). Ligne droite = 2 points.
     * @param sujet position RTK reelle du sujet (bateau), ou null si indisponible.
     * @param ageRtkS age de la position RTK (s), ou null. > ageRtkMax -> RTK_PERDU.
     * @param positionPrevue position ATTENDUE du sujet a cet instant (depuis
     *        cap/vitesse/duree), ou null si on ne synchronise pas la cadence.
     *        Sert UNIQUEMENT a l'ecart longitudinal (cadence), pas a la securite.
     */
    fun evaluer(
        trajectoire: List<Point>,
        sujet: Point?,
        ageRtkS: Double?,
        positionPrevue: Point? = null
    ): Resultat {
        // 1. RTK perdu = priorite securite (position absente ou trop vieille).
        if (sujet == null || (ageRtkS != null && (!ageRtkS.isFinite() || ageRtkS > cfg.ageRtkMaxS))) {
            return Resultat(
                Statut.RTK_PERDU, Action.PAUSE_OU_RTH,
                Double.NaN, Double.NaN,
                dansCorridor = false, avertissementCadence = false,
                message = "Position RTK du sujet perdue -> statut securite, pas de poursuite automatique."
            )
        }
        if (trajectoire.size < 2) {
            // pas de trajectoire de reference : on ne peut pas juger le corridor.
            return Resultat(
                Statut.RTK_PERDU, Action.PAUSE_OU_RTH,
                Double.NaN, Double.NaN, false, false,
                message = "Trajectoire prevue absente (< 2 points) : corridor non defini."
            )
        }

        // 2. ECART LATERAL = distance perpendiculaire a la polyligne (securite).
        val (latM, longAbsM) = ecartsSurPolyligne(trajectoire, sujet)

        // 3. ECART LONGITUDINAL vs cadence prevue (si positionPrevue fournie).
        //    projection du sujet et du prevu sur la ligne -> avance(+)/retard(-).
        val ecartLong: Double = if (positionPrevue != null) {
            val (_, longSujet) = ecartsSurPolyligne(trajectoire, sujet)
            val (_, longPrevu) = ecartsSurPolyligne(trajectoire, positionPrevue)
            longSujet - longPrevu   // + = sujet en avance, - = en retard
        } else Double.NaN

        val avertCadence = !ecartLong.isNaN() && abs(ecartLong) > cfg.seuilCadenceM

        // 4. STATUT selon l'ecart LATERAL (critere de securite principal).
        val statut = when {
            latM <= seuilNormal -> Statut.NORMAL
            latM <= seuilTolerance -> Statut.TOLERANCE
            latM <= seuilPrudence -> Statut.PRUDENCE
            else -> Statut.HORS_CORRIDOR
        }
        val action = when (statut) {
            Statut.NORMAL -> Action.CONTINUER
            Statut.TOLERANCE -> Action.AJUSTER_CADRAGE
            Statut.PRUDENCE -> Action.MAINTENIR_NE_PAS_POURSUIVRE
            Statut.HORS_CORRIDOR -> Action.INTERROMPRE_OU_REGENERER
            Statut.RTK_PERDU -> Action.PAUSE_OU_RTH
        }
        val dans = statut == Statut.NORMAL || statut == Statut.TOLERANCE || statut == Statut.PRUDENCE

        val msg = when (statut) {
            Statut.NORMAL -> "Sujet dans le corridor (%.0f m). Mission normale.".format(latM)
            Statut.TOLERANCE -> "Ecart lateral %.0f m : cadrage possiblement moins bon.".format(latM) +
                (if (avertCadence) " Cadence : %.0f m %s.".format(abs(ecartLong), if (ecartLong > 0) "d'avance" else "de retard") else "")
            Statut.PRUDENCE -> "Ecart lateral %.0f m : garder la mission, NE PAS poursuivre hors corridor.".format(latM)
            Statut.HORS_CORRIDOR -> "Sujet HORS corridor (%.0f m). Mission non fiable : interrompre / mettre en attente / regenerer.".format(latM)
            Statut.RTK_PERDU -> "Position RTK perdue."
        }

        return Resultat(statut, action, latM, ecartLong, dans, avertCadence, msg)
    }

    // ------------------------------------------------------------------
    //  Geometrie : ecart lateral (perpendiculaire) + abscisse curviligne
    // ------------------------------------------------------------------
    /**
     * Pour une polyligne, retourne (ecartLateralMin_m, abscisseCurviligne_m) :
     *  - ecartLateral = plus courte distance perpendiculaire a un segment,
     *  - abscisse = distance le long de la polyligne jusqu'au point projete
     *    (sert a l'ecart longitudinal / cadence).
     * Projection locale plane (m/deg), valable aux distances d'un tournage.
     */
    private fun ecartsSurPolyligne(ligne: List<Point>, p: Point): Pair<Double, Double> {
        var meilleurLat = Double.MAX_VALUE
        var abscisseAuMeilleur = 0.0
        var cumul = 0.0   // longueur cumulee le long de la polyligne
        for (i in 0 until ligne.size - 1) {
            val a = ligne[i]
            val b = ligne[i + 1]
            val (lat, t, segLen) = distPointSegment(a, b, p)
            if (lat < meilleurLat) {
                meilleurLat = lat
                abscisseAuMeilleur = cumul + t * segLen   // position le long de la ligne
            }
            cumul += segLen
        }
        return Pair(meilleurLat, abscisseAuMeilleur)
    }

    /**
     * Distance perpendiculaire (m) du point p au segment a-b, plus le parametre t
     * (0..1 = projection sur le segment) et la longueur du segment (m).
     * Repere local plan centre sur a : x = est, y = nord (metres).
     */
    private fun distPointSegment(a: Point, b: Point, p: Point): Triple<Double, Double, Double> {
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(a.lat))
        val ax = 0.0; val ay = 0.0
        val bx = (b.lon - a.lon) * mLon; val by = (b.lat - a.lat) * mLat
        val px = (p.lon - a.lon) * mLon; val py = (p.lat - a.lat) * mLat
        val dx = bx - ax; val dy = by - ay
        val segLen = hypot(dx, dy)
        if (segLen < 1e-9) return Triple(hypot(px - ax, py - ay), 0.0, 0.0)
        var t = ((px - ax) * dx + (py - ay) * dy) / (segLen * segLen)
        t = max(0.0, min(1.0, t))
        val cx = ax + t * dx; val cy = ay + t * dy
        val dist = hypot(px - cx, py - cy)
        return Triple(dist, t, segLen)
    }
}

