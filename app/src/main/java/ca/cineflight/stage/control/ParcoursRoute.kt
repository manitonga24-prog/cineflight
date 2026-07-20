package ca.cineflight.stage.control

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * ParcoursRoute — RAIL de suivi pour un sujet CONTRAINT PAR LA ROUTE (vehicule).
 * PHASE 1 — ARCHITECTURE FIGEE.
 *
 * >>> DOCTRINE <<<
 * Une personne a pied se deplace librement -> corridor de suivi a bandes larges.
 * Un vehicule SUIT LA ROUTE -> centerline OSM validee sans obstacle (preview3d)
 * devient un RAIL. Le RTK ne sert qu'a situer le sujet SUR le rail (abscisse
 * curviligne s, 1 dimension). Le drone ANTICIPE (s + v*tau) au lieu de poursuivre.
 *
 * >>> ROLE ET LIMITES (grave) <<<
 * Ce module SUGGERE une cible ; il N'AUTORISE JAMAIS le mouvement. Le dernier mot
 * reste au MouvementSafetyValidator (serveur) et au NoyauSecurite. calculerCible()
 * produit un VERDICT SECURITAIRE informatif (raison de blocage), mais ne commande
 * rien. Module PUR : aucune dependance Android/DJI, deterministe, testable en JVM.
 *
 * >>> SOURCES DE VITESSE (ordre fige) <<<
 *   1. ground_speed_mps du serveur RTK (principale) ;
 *   2. derivation Android filtree (secours) ;
 *   3. 0.0 (aucune source valide).
 * Le cap GNSS ne sert QU'A confirmer le sens de progression (avant/arriere) sur le
 * rail. Il n'est JAMAIS utilise pour sortir de la centerline.
 *
 * Repere : projection plane locale (est, nord en metres) autour du 1er point de la
 * route, valable aux distances d'un tournage.
 */
class ParcoursRoute(
    /** Polyligne de la route (centerline validee). >= 2 points. */
    private val polyligne: List<GeoBarriere.Point>,
    private val cfg: Config = Config()
) {

    // ------------------------------------------------------------------
    //  Configuration (defauts = comportement conservateur)
    // ------------------------------------------------------------------
    data class Config(
        /** Longueur (m) de lissage de la tangente autour du point projete, pour
         *  eviter un saut de direction/normale a chaque sommet. 0 = pas de lissage. */
        val lissageTangenteM: Double = 12.0,
        /** Table vitesse(km/h) -> temps d'avance tau(s). Interpolation lineaire.
         *  Conforme spec : 0->0.0 ; 30->0.4 ; 60->0.7 ; 100->1.0. Plafond tauMaxS. */
        val tableTauSParVitesseKmh: List<Pair<Double, Double>> = listOf(
            0.0 to 0.0, 30.0 to 0.4, 60.0 to 0.7, 100.0 to 1.0
        ),
        /** Plafond tau (s). 1.2 en operation ; 1.5 reserve SIMULATEUR (override). */
        val tauMaxS: Double = 1.2,

        // ---- GESTION DES VIRAGES (courbure locale) ----
        // Dans un virage, anticiper trop loin (s + v*tau) enverrait la cible en
        // travers de la courbe. On REDUIT tau quand la courbure augmente : le drone
        // ne coupe pas le virage, reste dans le corridor, garde un mouvement fluide.
        // En ligne droite (courbure ~0) -> facteur 1.0 (anticipation pleine).
        /** Active la modulation de tau par la courbure (defaut OFF = comportement
         *  historique strictement inchange). */
        val virageAdaptatif: Boolean = false,
        /** Longueur (m) de la fenetre de mesure de la courbure autour du point.
         *  Plus grand = mesure plus lissee (moins sensible au bruit de la polyligne). */
        val fenetreCourbureM: Double = 20.0,
        /** Courbure (rad/m) au-dela de laquelle on applique la reduction MAXIMALE.
         *  Repere : un virage de rayon R a une courbure 1/R. 0.05 rad/m = rayon 20 m
         *  (virage urbain serre). En dessous, reduction proportionnelle. */
        val courbureMaxRadParM: Double = 0.05,
        /** Facteur tau MINIMAL dans les virages les plus serres (0..1). 0.35 = on
         *  garde 35% de l'anticipation dans un virage tres serre (pas 0, sinon le
         *  drone perd toute avance et devient purement reactif). */
        val facteurTauVirageMin: Double = 0.35,

        // ---- fenetre de validite pour la vitesse derivee Android (secours) ----
        val intervalleDeriveMinS: Double = 0.2,   // < 200 ms : rejete (bruit)
        val intervalleDeriveMaxS: Double = 2.0,   // > 2 s : rejete (trop espace)
        val vitesseMaxPlausibleMps: Double = 40.0, // ~144 km/h : au-dela = saut GNSS
        val sautMaxPlausibleM: Double = 60.0,      // deplacement max plausible entre 2 echantillons

        // ---- erreur de position estimee (coupure vitesse-dependante) ----
        val accelerationMaxMs2: Double = 3.0,      // terme 0.5*a*age^2
        val incertitudeFixM: Double = 0.1,
        val incertitudeFloatM: Double = 0.5,
        val incertitudeGpsM: Double = 2.0,
        /** Tolerance de position (m) : erreur estimee au-dela = verdict BLOQUE. */
        val tolerancePositionM: Double = 40.0,

        // ---- age RTK ----
        val ageRtkMaxStatiqueS: Double = 2.5,      // coherent avec MouvementSafetyValidator
        /** Active le age-max DYNAMIQUE (baisse quand la vitesse monte). */
        val ageRtkDynamique: Boolean = true,
        val ageRtkMinS: Double = 0.4,
        val ageRtkMaxS: Double = 2.5,
        val vitesseMinPourAgeMps: Double = 0.5     // plancher anti division par ~0
    )

    // ------------------------------------------------------------------
    //  Historique (pour la vitesse derivee de secours)
    // ------------------------------------------------------------------
    /** Un echantillon de position horodate (timestamp MONOTONE, ex. System.nanoTime()). */
    data class EchantillonPosition(
        val position: GeoBarriere.Point,
        val timestampMonotoneNs: Long,
        val rtk: String,
        val ageS: Double
    )

    // ------------------------------------------------------------------
    //  Verdict / sortie
    // ------------------------------------------------------------------
    enum class SourceVitesse { SERVEUR, DERIVEE_ANDROID, AUCUNE }

    enum class Verdict {
        OK,                 // cible suggeree utilisable (sous reserve validateur + noyau)
        BLOQUE_RTK_ABSENT,  // pas de position / rtk perdu
        BLOQUE_RTK_VIEUX,   // age RTK > seuil (statique ou dynamique)
        BLOQUE_ERREUR_POSITION, // erreur estimee > tolerance (trop incertain a cette vitesse)
        BLOQUE_ROUTE_INVALIDE   // polyligne < 2 points
    }

    /** Sens de progression du sujet sur le rail (confirme par le cap GNSS). */
    enum class Sens { AVANT, ARRIERE, INDETERMINE }

    /**
     * Resultat de calculerCible(). Contient la geometrie suggeree ET le verdict
     * securitaire (informatif). Aucune commande : le validateur/noyau tranchent.
     */
    data class Resultat(
        val verdict: Verdict,
        val raisonBlocage: String?,           // null si OK
        val projection: Projection?,          // projection du sujet sur le rail
        val positionAnticipee: GeoBarriere.Point?,  // point-sujet vise (s + v*tau) sur le rail
        val cibleDrone: GeoBarriere.Point?,   // position drone suggeree (anticipee + offsets)
        val yawDroneDeg: Double,              // cap drone -> point-sujet vise (0=N)
        val gimbalDeg: Double,                // pitch nacelle (auto vers le sujet)
        val sourceVitesse: SourceVitesse,
        val vitesseUtiliseeMps: Double,
        val sens: Sens,
        val erreurEstimeeM: Double,           // erreur de position estimee a cette vitesse
        val ageRtkMaxEffectifS: Double        // seuil d'age effectivement applique
    )

    data class Projection(
        val s: Double,                // abscisse curviligne (m) clampee [0, L]
        val ecartLateralM: Double,    // distance perpendiculaire au rail (m, >= 0)
        val ecartLateralSigneM: Double // + = a gauche du sens de parcours, - = a droite
    )

    // ------------------------------------------------------------------
    //  Geometrie precalculee (repere local)
    // ------------------------------------------------------------------
    private val latRef: Double = polyligne.first().lat
    private val lonRef: Double = polyligne.first().lon
    private val mLat = 111_320.0
    private val mLon = 111_320.0 * cos(Math.toRadians(latRef))

    private data class Sommet(val x: Double, val y: Double, val s: Double)
    private val sommets: List<Sommet>
    val longueurM: Double
    val routeValide: Boolean

    init {
        if (polyligne.size < 2) {
            sommets = emptyList()
            longueurM = 0.0
            routeValide = false
        } else {
            val lst = ArrayList<Sommet>(polyligne.size)
            var cumul = 0.0
            var prevX = 0.0; var prevY = 0.0
            polyligne.forEachIndexed { i, p ->
                val x = (p.lon - lonRef) * mLon
                val y = (p.lat - latRef) * mLat
                if (i > 0) cumul += hypot(x - prevX, y - prevY)
                lst.add(Sommet(x, y, cumul))
                prevX = x; prevY = y
            }
            sommets = lst
            longueurM = cumul
            routeValide = cumul > 0.0
        }
    }

    private fun local(p: GeoBarriere.Point): Pair<Double, Double> =
        Pair((p.lon - lonRef) * mLon, (p.lat - latRef) * mLat)

    private fun wgs(x: Double, y: Double): GeoBarriere.Point =
        GeoBarriere.Point(latRef + y / mLat, lonRef + x / mLon)

    // ------------------------------------------------------------------
    //  Projection sur le rail (abscisse curviligne + ecart lateral signe)
    // ------------------------------------------------------------------
    fun projeter(sujet: GeoBarriere.Point): Projection {
        val (px, py) = local(sujet)
        var meilleurD = Double.MAX_VALUE
        var meilleurS = 0.0
        var signe = 1.0
        for (i in 0 until sommets.size - 1) {
            val a = sommets[i]; val b = sommets[i + 1]
            val dx = b.x - a.x; val dy = b.y - a.y
            val segLen = hypot(dx, dy)
            if (segLen < 1e-9) continue
            var t = ((px - a.x) * dx + (py - a.y) * dy) / (segLen * segLen)
            t = max(0.0, min(1.0, t))
            val cx = a.x + t * dx; val cy = a.y + t * dy
            val d = hypot(px - cx, py - cy)
            if (d < meilleurD) {
                meilleurD = d
                meilleurS = a.s + t * segLen
                val ux = dx / segLen; val uy = dy / segLen
                val cross = ux * (py - cy) - uy * (px - cx)  // + = a gauche
                signe = if (cross >= 0) 1.0 else -1.0
            }
        }
        val sC = meilleurS.coerceIn(0.0, longueurM)
        return Projection(sC, meilleurD, meilleurD * signe)
    }

    /** Point WGS84 a l'abscisse s (clampee). */
    fun pointA(s: Double): GeoBarriere.Point {
        val (x, y) = xyA(s.coerceIn(0.0, longueurM))
        return wgs(x, y)
    }

    private fun xyA(s: Double): Pair<Double, Double> {
        if (sommets.isEmpty()) return Pair(0.0, 0.0)
        if (s <= 0.0) return Pair(sommets.first().x, sommets.first().y)
        if (s >= longueurM) return Pair(sommets.last().x, sommets.last().y)
        for (i in 0 until sommets.size - 1) {
            val a = sommets[i]; val b = sommets[i + 1]
            if (s <= b.s) {
                val segLen = b.s - a.s
                val t = if (segLen < 1e-9) 0.0 else (s - a.s) / segLen
                return Pair(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
            }
        }
        return Pair(sommets.last().x, sommets.last().y)
    }

    /**
     * Tangente unitaire (est, nord) au rail a l'abscisse s, LISSEE sur
     * +/- lissageTangenteM/2. Sens = sens de parcours (s croissant).
     */
    fun tangenteA(s: Double): Pair<Double, Double> {
        if (sommets.size < 2) return Pair(1.0, 0.0)
        val demi = if (cfg.lissageTangenteM > 0) cfg.lissageTangenteM / 2.0 else 0.0
        val s0 = (s - demi).coerceIn(0.0, longueurM)
        val s1 = (s + demi).coerceIn(0.0, longueurM)
        val (x0, y0) = xyA(s0)
        val (x1, y1) = xyA(s1)
        var tx = x1 - x0; var ty = y1 - y0
        var n = hypot(tx, ty)
        if (n < 1e-9) {
            val (bx, by) = tangenteSegmentBrute(s); tx = bx; ty = by; n = hypot(tx, ty)
            if (n < 1e-9) return Pair(1.0, 0.0)
        }
        return Pair(tx / n, ty / n)
    }

    private fun tangenteSegmentBrute(s: Double): Pair<Double, Double> {
        for (i in 0 until sommets.size - 1) {
            val a = sommets[i]; val b = sommets[i + 1]
            if (s <= b.s || i == sommets.size - 2) return Pair(b.x - a.x, b.y - a.y)
        }
        return Pair(1.0, 0.0)
    }

    /** Normale unitaire (est, nord). cote="droite" -> (ty,-tx) ; "gauche" -> (-ty,tx). */
    fun normaleA(s: Double, cote: String): Pair<Double, Double> {
        val (tx, ty) = tangenteA(s)
        return if (cote == "gauche") Pair(-ty, tx) else Pair(ty, -tx)
    }

    // ------------------------------------------------------------------
    //  Courbure locale (gestion des virages)
    // ------------------------------------------------------------------
    /**
     * Courbure locale (rad/m) du rail a l'abscisse s : |Δangle de tangente| / Δs,
     * mesuree sur une fenetre +/- fenetreCourbureM/2. En ligne droite -> ~0. Dans
     * un virage de rayon R -> ~1/R. Toujours >= 0.
     */
    fun courbureA(s: Double): Double {
        if (sommets.size < 3) return 0.0
        val demi = cfg.fenetreCourbureM / 2.0
        val s0 = (s - demi).coerceIn(0.0, longueurM)
        val s1 = (s + demi).coerceIn(0.0, longueurM)
        val ds = s1 - s0
        if (ds < 1e-6) return 0.0
        val (t0x, t0y) = tangenteA(s0)
        val (t1x, t1y) = tangenteA(s1)
        // angle entre les deux tangentes (produit scalaire borne pour acos).
        var dot = t0x * t1x + t0y * t1y
        dot = dot.coerceIn(-1.0, 1.0)
        val dTheta = kotlin.math.acos(dot)   // rad, toujours >= 0
        return dTheta / ds
    }

    /**
     * Facteur (0..1) appliquant a tau la reduction due au virage a l'abscisse s.
     * 1.0 en ligne droite ; descend vers facteurTauVirageMin quand la courbure
     * atteint courbureMaxRadParM. Interpolation lineaire, borne.
     */
    fun facteurVirage(s: Double): Double {
        if (!cfg.virageAdaptatif) return 1.0
        val k = courbureA(s)
        if (cfg.courbureMaxRadParM <= 1e-9) return 1.0
        val f = (k / cfg.courbureMaxRadParM).coerceIn(0.0, 1.0)  // 0 = droit, 1 = virage max
        return (1.0 - f * (1.0 - cfg.facteurTauVirageMin)).coerceIn(cfg.facteurTauVirageMin, 1.0)
    }

    // ------------------------------------------------------------------
    //  Vitesse : selection de source (serveur > derivee Android > 0)
    // ------------------------------------------------------------------
    /**
     * Derive une vitesse (m/s) le long du rail a partir des 2 derniers echantillons
     * valides. Retourne null si aucun couple ne respecte les filtres :
     *   RTK valide, intervalle 200 ms..2 s, distance plausible, vitesse < limite.
     */
    fun vitesseDeriveeAndroid(historique: List<EchantillonPosition>): Double? {
        if (historique.size < 2) return null
        // parcourir du plus recent au plus ancien, prendre le 1er couple valide.
        val rec = historique.last()
        for (i in historique.size - 2 downTo 0) {
            val prec = historique[i]
            if (!rtkValide(rec.rtk) || !rtkValide(prec.rtk)) continue
            val dtS = (rec.timestampMonotoneNs - prec.timestampMonotoneNs) / 1e9
            if (dtS < cfg.intervalleDeriveMinS || dtS > cfg.intervalleDeriveMaxS) continue
            val (rx, ry) = local(rec.position)
            val (px, py) = local(prec.position)
            val dist = hypot(rx - px, ry - py)
            if (dist > cfg.sautMaxPlausibleM) continue            // saut GNSS rejete
            val v = dist / dtS
            if (v > cfg.vitesseMaxPlausibleMps) continue          // vitesse aberrante rejetee
            return v
        }
        return null
    }

    private fun rtkValide(rtk: String?): Boolean {
        val r = rtk?.uppercase() ?: return false
        return r == "FIX" || r == "FLOAT" || r == "GPS"
    }

    // ------------------------------------------------------------------
    //  Sens de progression (cap GNSS vs tangente locale)
    // ------------------------------------------------------------------
    /**
     * Determine le sens sur le rail en comparant le cap GNSS a la tangente locale.
     * Le cap ne sert QU'A ca : il ne modifie jamais la geometrie du rail.
     * @return AVANT si le cap suit la tangente (s croissant), ARRIERE si oppose,
     *         INDETERMINE si cap absent.
     */
    fun sensProgression(sAbscisse: Double, capGnssDeg: Double?): Sens {
        if (capGnssDeg == null) return Sens.INDETERMINE
        val (tx, ty) = tangenteA(sAbscisse)
        // cap 0=N,90=E -> vecteur (est,nord) = (sin,cos)
        val cx = Math.sin(Math.toRadians(capGnssDeg))
        val cy = Math.cos(Math.toRadians(capGnssDeg))
        val dot = tx * cx + ty * cy
        return if (dot >= 0) Sens.AVANT else Sens.ARRIERE
    }

    // ------------------------------------------------------------------
    //  Anticipation
    // ------------------------------------------------------------------
    fun tauPourVitesse(vitesseMps: Double, tauMaxOverrideS: Double = 0.0): Double {
        val vKmh = abs(vitesseMps) * 3.6
        val table = cfg.tableTauSParVitesseKmh.sortedBy { it.first }
        val plafond = if (tauMaxOverrideS > 0) tauMaxOverrideS else cfg.tauMaxS
        val brut = when {
            vKmh <= table.first().first -> table.first().second
            vKmh >= table.last().first -> table.last().second
            else -> {
                var res = table.last().second
                for (i in 0 until table.size - 1) {
                    val (v0, t0) = table[i]; val (v1, t1) = table[i + 1]
                    if (vKmh in v0..v1) {
                        val f = if (v1 - v0 < 1e-9) 0.0 else (vKmh - v0) / (v1 - v0)
                        res = t0 + f * (t1 - t0); break
                    }
                }
                res
            }
        }
        return brut.coerceIn(0.0, plafond)
    }

    // ------------------------------------------------------------------
    //  Erreur de position estimee + age max dynamique
    // ------------------------------------------------------------------
    private fun incertitudePour(rtk: String?): Double = when (rtk?.uppercase()) {
        "FIX" -> cfg.incertitudeFixM
        "FLOAT" -> cfg.incertitudeFloatM
        else -> cfg.incertitudeGpsM
    }

    /** erreurAge = v*age + 0.5*aMax*age^2 + incertitudeRTK. */
    fun erreurPositionEstimee(vitesseMps: Double, ageS: Double, rtk: String?): Double {
        val a = abs(ageS)
        return abs(vitesseMps) * a + 0.5 * cfg.accelerationMaxMs2 * a * a + incertitudePour(rtk)
    }

    /** age max effectif : dynamique (clamp tolerance/vitesse) ou statique. */
    fun ageRtkMaxEffectif(vitesseMps: Double): Double {
        if (!cfg.ageRtkDynamique) return cfg.ageRtkMaxStatiqueS
        val v = max(abs(vitesseMps), cfg.vitesseMinPourAgeMps)
        return (cfg.tolerancePositionM / v).coerceIn(cfg.ageRtkMinS, cfg.ageRtkMaxS)
    }

    // ------------------------------------------------------------------
    //  POINT D'ENTREE : calculerCible() — deterministe, pur, sans commande
    // ------------------------------------------------------------------
    /**
     * @param sujet position RTK du sujet (null = perdu).
     * @param rtk etat RTK ("FIX"/"FLOAT"/"GPS"/"LOST"/null).
     * @param ageRtkS age de la position RTK (s).
     * @param vitesseServeurMps ground_speed_mps du serveur (null si absent). SOURCE PRINCIPALE.
     * @param capGnssDeg cap GNSS (deg, 0=N), pour confirmer le sens. Optionnel.
     * @param historique echantillons horodates (secours pour vitesse derivee).
     * @param decalageLateralM offset lateral drone (m).
     * @param cote "droite"/"gauche".
     * @param decalageLongitudinalM offset le long du rail (m, + = en avant). Souvent 0.
     * @param hauteurAglM altitude AGL pour le gimbal auto.
     * @param anticipation active la projection s + v*tau (defaut false = neutre).
     * @param tauMaxOverrideS plafond tau (>0 : SIMULATEUR 1.5 s).
     */
    fun calculerCible(
        sujet: GeoBarriere.Point?,
        rtk: String?,
        ageRtkS: Double?,
        vitesseServeurMps: Double? = null,
        capGnssDeg: Double? = null,
        historique: List<EchantillonPosition> = emptyList(),
        decalageLateralM: Double = 20.0,
        cote: String = "droite",
        decalageLongitudinalM: Double = 0.0,
        hauteurAglM: Double = 40.0,
        anticipation: Boolean = false,
        tauMaxOverrideS: Double = 0.0
    ): Resultat {
        // 0. ROUTE INVALIDE.
        if (!routeValide) {
            return bloque(Verdict.BLOQUE_ROUTE_INVALIDE,
                "Route invalide (< 2 points ou longueur nulle).",
                SourceVitesse.AUCUNE, 0.0, Sens.INDETERMINE, Double.NaN, Double.NaN)
        }
        // 1. RTK ABSENT / PERDU.
        val rtkUp = rtk?.uppercase()
        if (sujet == null || !sujet.lat.isFinite() || !sujet.lon.isFinite() ||
            rtkUp == null || rtkUp == "LOST") {
            return bloque(Verdict.BLOQUE_RTK_ABSENT,
                "Position RTK du sujet absente ou perdue.",
                SourceVitesse.AUCUNE, 0.0, Sens.INDETERMINE, Double.NaN, Double.NaN)
        }

        // 2. SOURCE DE VITESSE (ordre fige : serveur > derivee > 0).
        val vServeurValide = vitesseServeurMps != null &&
            vitesseServeurMps.isFinite() && vitesseServeurMps >= 0.0 &&
            vitesseServeurMps <= cfg.vitesseMaxPlausibleMps
        val vDerivee = if (!vServeurValide) vitesseDeriveeAndroid(historique) else null
        val (sourceV, vitesse) = when {
            vServeurValide -> SourceVitesse.SERVEUR to vitesseServeurMps!!
            vDerivee != null -> SourceVitesse.DERIVEE_ANDROID to vDerivee
            else -> SourceVitesse.AUCUNE to 0.0
        }

        // 3. AGE RTK (dynamique selon la vitesse).
        val ageMax = ageRtkMaxEffectif(vitesse)
        val age = ageRtkS ?: Double.MAX_VALUE
        if (!age.isFinite() || age > ageMax) {
            return bloque(Verdict.BLOQUE_RTK_VIEUX,
                "Position RTK trop vieille : %.2f s > seuil %.2f s (vitesse %.1f m/s).".format(age, ageMax, vitesse),
                sourceV, vitesse, Sens.INDETERMINE, Double.NaN, ageMax)
        }

        // 4. ERREUR DE POSITION ESTIMEE (coupure vitesse-dependante).
        val erreur = erreurPositionEstimee(vitesse, age, rtkUp)
        if (!erreur.isFinite() || erreur > cfg.tolerancePositionM) {
            return bloque(Verdict.BLOQUE_ERREUR_POSITION,
                "Erreur de position estimee %.1f m > tolerance %.1f m (a cette vitesse/age).".format(erreur, cfg.tolerancePositionM),
                sourceV, vitesse, Sens.INDETERMINE, erreur, ageMax)
        }

        // 5. PROJECTION + SENS + ANTICIPATION.
        val proj = projeter(sujet)
        val sens = sensProgression(proj.s, capGnssDeg)
        // signe de progression : ARRIERE -> on anticipe vers s decroissant.
        val signeSens = if (sens == Sens.ARRIERE) -1.0 else 1.0
        // GESTION DES VIRAGES : on reduit tau selon la courbure locale au point
        // COURANT du sujet (la ou le virage commence), pour ne pas viser en travers
        // de la courbe. facteurVirage=1.0 en ligne droite ou si virageAdaptatif=off.
        val tauBase = if (anticipation) tauPourVitesse(vitesse, tauMaxOverrideS) else 0.0
        val fVirage = facteurVirage(proj.s)
        val tau = tauBase * fVirage
        val sCible = (proj.s + signeSens * vitesse * tau).coerceIn(0.0, longueurM)

        // point-sujet vise sur le rail (sans lateral) + cible drone (avec offsets).
        val (sx, sy) = xyA(sCible)
        val (tx, ty) = tangenteA(sCible)
        val visX = sx + tx * (signeSens * decalageLongitudinalM)
        val visY = sy + ty * (signeSens * decalageLongitudinalM)
        val (nx, ny) = normaleA(sCible, cote)
        val droneX = visX + nx * decalageLateralM
        val droneY = visY + ny * decalageLateralM
        val cibleDrone = wgs(droneX, droneY)
        val sujetVise = wgs(sx, sy)

        // yaw drone -> point-sujet vise
        val vEst = sx - droneX
        val vNord = sy - droneY
        var yaw = Math.toDegrees(kotlin.math.atan2(vEst, vNord))
        if (yaw < 0) yaw += 360.0
        // gimbal auto vers le sujet = -atan(hauteur/distanceHoriz)
        val distHoriz = hypot(vEst, vNord)
        val gimbal = (-Math.toDegrees(kotlin.math.atan2(hauteurAglM, max(1e-3, distHoriz))))
            .coerceIn(-90.0, 0.0)

        return Resultat(
            verdict = Verdict.OK,
            raisonBlocage = null,
            projection = proj,
            positionAnticipee = sujetVise,
            cibleDrone = cibleDrone,
            yawDroneDeg = yaw,
            gimbalDeg = gimbal,
            sourceVitesse = sourceV,
            vitesseUtiliseeMps = vitesse,
            sens = sens,
            erreurEstimeeM = erreur,
            ageRtkMaxEffectifS = ageMax
        )
    }

    private fun bloque(
        v: Verdict, raison: String, src: SourceVitesse, vit: Double,
        sens: Sens, erreur: Double, ageMax: Double
    ) = Resultat(
        verdict = v, raisonBlocage = raison, projection = null,
        positionAnticipee = null, cibleDrone = null, yawDroneDeg = Double.NaN,
        gimbalDeg = Double.NaN, sourceVitesse = src, vitesseUtiliseeMps = vit,
        sens = sens, erreurEstimeeM = erreur, ageRtkMaxEffectifS = ageMax
    )
}
