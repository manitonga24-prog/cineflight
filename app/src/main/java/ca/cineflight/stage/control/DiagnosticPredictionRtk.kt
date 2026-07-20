package ca.cineflight.stage.control

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Moteur de prediction RTK V3.1, passif et fail-closed.
 *
 * Deux sorties sont volontairement separees:
 * - [EtatDiagnostic.pret] / display_ready: assez fiable pour l'affichage;
 * - [EtatDiagnostic.controlePret] / control_ready: assez fiable pour alimenter
 *   un controleur, sans toutefois produire ici la moindre commande de vol.
 *
 * Invariants de securite:
 * - control_ready est impossible avec GPS, FLOAT, LOST ou RTK non qualifie;
 * - control_ready est impossible avec confiance/incertitude/residu hors seuil;
 * - une transition RTK, une interruption, un saut ou un horodatage non monotone
 *   remet le moteur en acquisition ou en mode degrade;
 * - les mesures repetees ne sont jamais ajoutees comme nouveaux echantillons;
 * - l'historique est adaptatif: long a l'arret, court en mouvement et en virage.
 */
class DiagnosticPredictionRtk(
    private val configuration: Configuration = Configuration()
) {
    data class Configuration(
        val minEchantillons: Int = 6,
        val maxEchantillons: Int = 200,
        val dureeHistoriqueMinS: Double = 0.75,
        val ageMaxS: Double = 2.20,
        val agePurgeS: Double = 5.00,
        val intervalleMinS: Double = 0.08,
        val jitterHorodatageMaxS: Double = 0.25,
        val interruptionMaxS: Double = 2.75,
        val vitesseMaxMps: Double = 45.0,
        val margeSautM: Double = 1.50,
        val seuilImmobileMps: Double = 0.25,
        val rayonImmobileFixM: Double = 0.35,
        val rayonImmobileFloatM: Double = 0.85,
        val accelerationMaxMps2: Double = 10.0,
        val accelerationModeleMinMps2: Double = 0.35,
        val ecartAccelerationMaxMps2: Double = 3.5,
        val virageModeleMinDegS: Double = 4.0,
        val virageMaxDegS: Double = 90.0,
        val ecartVirageMaxDegS: Double = 18.0,
        val ecartDirectionMaxDeg: Double = 28.0,
        val residuModeleMaxM: Double = 5.00,
        val incertitudeMaxM: Double = 5.00,
        val distancePredictionMaxM: Double = 65.0,
        val delaiPipelineS: Double = 0.25,
        val delaiReactionDroneS: Double = 0.55,
        val horizonMinS: Double = 0.40,
        val horizonMaxS: Double = 1.50,
        val accepterRtkGenerique: Boolean = false,

        // Fenetres adaptatives.
        val fenetreAcquisitionS: Double = 8.0,
        val fenetreImmobileS: Double = 30.0,
        val fenetreDeplacementS: Double = 10.0,
        val fenetreVirageS: Double = 6.0,
        val fenetreDegradeS: Double = 6.0,
        val constanteTempsImmobileS: Double = 10.0,
        val constanteTempsDeplacementS: Double = 4.0,
        val constanteTempsVirageS: Double = 2.0,

        // V3.1: estimation de vitesse robuste sur une vraie base temporelle.
        // Les positions restent ingerees a leur cadence native, mais le cap et
        // la vitesse ne sont plus derives entre deux points voisins.
        val fenetreRegressionVitesseS: Double = 1.00,
        val dureeMinRegressionVitesseS: Double = 0.55,
        val pointsMinRegressionVitesse: Int = 3,
        val pasVecteurMinS: Double = 0.16,
        val ponderationRecenceMin: Double = 0.55,
        val facteurHuberRegression: Double = 1.50,
        val residuHuberPlancherM: Double = 0.04,
        val iterationsRegressionRobuste: Int = 2,

        // Barriere display_ready.
        val confianceAffichageMinPct: Int = 35,
        val incertitudeAffichageMaxM: Double = 5.0,
        val residuAffichageMaxM: Double = 4.0,
        val stabiliteAffichageMinS: Double = 0.50,

        // Barriere control_ready, volontairement stricte.
        val confianceControleMinPct: Int = 70,
        val incertitudeControleMaxM: Double = 1.50,
        val residuControleMaxM: Double = 1.25,
        val ageControleMaxS: Double = 0.90,
        val stabiliteModeleControleMinS: Double = 2.0,
        val stabiliteFixControleMinS: Double = 3.0,
        val echantillonsFixControleMin: Int = 3,
        val probabiliteModeleControleMinPct: Int = 70,

        // Virage: seuils encore plus conservateurs.
        val confianceControleVirageMinPct: Int = 75,
        val incertitudeControleVirageMaxM: Double = 1.25,
        val ageControleVirageMaxS: Double = 0.80,
        val horizonVirageMaxS: Double = 0.80,

        // Hysteresis de la machine d'etat.
        val vitesseEntreeMouvementMps: Double = 0.30,
        val vitesseEntreeImmobileMps: Double = 0.15,
        val confirmationsMouvement: Int = 3,
        val confirmationsImmobile: Int = 4,
        val confirmationsVirage: Int = 2,
        val confirmationsSortieVirage: Int = 3
    ) {
        init {
            require(minEchantillons >= 3)
            require(maxEchantillons >= minEchantillons)
            require(agePurgeS > ageMaxS)
            require(intervalleMinS > 0.0)
            require(jitterHorodatageMaxS >= 0.0)
            require(interruptionMaxS > intervalleMinS)
            require(horizonMaxS >= horizonMinS)
            require(vitesseMaxMps > 0.0)
            require(confianceAffichageMinPct in 1..100)
            require(confianceControleMinPct in confianceAffichageMinPct..100)
            require(incertitudeControleMaxM <= incertitudeAffichageMaxM)
            require(residuControleMaxM <= residuAffichageMaxM)
            require(echantillonsFixControleMin >= 2)
            require(fenetreRegressionVitesseS > dureeMinRegressionVitesseS)
            require(dureeMinRegressionVitesseS > 0.0)
            require(pointsMinRegressionVitesse >= 3)
            require(pasVecteurMinS > 0.0)
            require(ponderationRecenceMin in 0.0..1.0)
            require(facteurHuberRegression > 0.0)
            require(residuHuberPlancherM > 0.0)
            require(iterationsRegressionRobuste in 1..5)
        }
    }

    enum class Modele(val code: String) {
        ACQUISITION("ACQUISITION"),
        IMMOBILE("IMMOBILE"),
        VITESSE_CONSTANTE("VITESSE_CONSTANTE"),
        ACCELERATION_CONSTANTE("ACCELERATION_CONSTANTE"),
        VIRAGE_CONSTANT("VIRAGE_CONSTANT"),
        VIRAGE_ACCELERE("VIRAGE_ACCELERE")
    }

    enum class EtatMouvement(val code: String) {
        ACQUISITION("ACQUISITION"),
        IMMOBILE("IMMOBILE"),
        DEPLACEMENT_DROIT("DEPLACEMENT_DROIT"),
        VIRAGE("VIRAGE"),
        DEGRADE("DEGRADE"),
        PERDU("PERDU")
    }

    data class EtatDiagnostic(
        /** Compatibilite V2: pret signifie maintenant display_ready. */
        val pret: Boolean,
        val controlePret: Boolean,
        val raison: String,
        val raisonControle: String,
        val qualiteRtk: String,
        val etatMouvement: EtatMouvement,
        val modele: Modele,
        val probabiliteModelePct: Int?,
        val stabiliteModeleS: Double,
        val stabiliteQualiteS: Double,
        val transitionRtkRecente: Boolean,
        val echantillonAccepte: Boolean,
        val statutMesure: String,
        val echantillons: Int,
        val echantillonsFrais: Int,
        val echantillonsFixFrais: Int,
        val mesuresRepetees: Int,
        val dureeHistoriqueS: Double,
        val ageS: Double,
        val confiancePct: Int?,
        val timestampMesureMs: Long?,
        val timestampCibleMs: Long?,
        val vitesseMps: Double?,
        val capDeg: Double?,
        val accelerationMps2: Double?,
        val virageDegS: Double?,
        val ecartVitesseMps: Double?,
        val ecartDirectionDeg: Double?,
        val residuModeleM: Double?,
        val incertitudeM: Double?,
        val horizonS: Double,
        val distancePredictionM: Double?,
        val latActuelle: Double?,
        val lonActuelle: Double?,
        val latPredite: Double?,
        val lonPredite: Double?
    ) {
        fun journal(): String = buildString {
            append(if (pret) "prediction_ready" else "prediction_blocked")
            append(" engine_version=").append(VERSION_MOTEUR)
            append(" display_ready=").append(pret)
            append(" control_ready=").append(controlePret)
            append(" rtk=").append(qualiteRtk)
            append(" state=").append(etatMouvement.code)
            append(" reason=").append(raison)
            append(" control_reason=").append(raisonControle)
            append(" model=").append(modele.code)
            append(" model_probability_pct=").append(probabiliteModelePct ?: "--")
            append(" model_stable_s=").append(format(stabiliteModeleS, 2))
            append(" quality_stable_s=").append(format(stabiliteQualiteS, 2))
            append(" rtk_transition_recent=").append(transitionRtkRecente)
            append(" accepted=").append(echantillonAccepte)
            append(" sample_status=").append(statutMesure)
            append(" samples=").append(echantillons)
            append(" fresh_samples=").append(echantillonsFrais)
            append(" fix_samples=").append(echantillonsFixFrais)
            append(" duplicate_samples=").append(mesuresRepetees)
            append(" history_s=").append(format(dureeHistoriqueS, 2))
            append(" age_s=").append(format(ageS, 2))
            append(" confidence_pct=").append(confiancePct ?: "--")
            append(" measurement_ts_ms=").append(timestampMesureMs ?: "--")
            append(" target_ts_ms=").append(timestampCibleMs ?: "--")
            append(" speed_mps=").append(format(vitesseMps, 2))
            append(" heading_deg=").append(format(capDeg, 1))
            append(" accel_mps2=").append(format(accelerationMps2, 2))
            append(" turn_deg_s=").append(format(virageDegS, 1))
            append(" speed_std_mps=").append(format(ecartVitesseMps, 2))
            append(" heading_std_deg=").append(format(ecartDirectionDeg, 1))
            append(" residual_m=").append(format(residuModeleM, 2))
            append(" uncertainty_m=").append(format(incertitudeM, 2))
            append(" horizon_s=").append(format(horizonS, 2))
            append(" predicted_m=").append(format(distancePredictionM, 2))
            append(" lat=").append(format(latActuelle, 7))
            append(" lon=").append(format(lonActuelle, 7))
            append(" predicted_lat=").append(format(latPredite, 7))
            append(" predicted_lon=").append(format(lonPredite, 7))
        }

        fun textePanneau(): String = buildString {
            append("AFFICHAGE : ").append(if (pret) "PRET" else "BLOQUE")
            append("  [").append(raison).append("]\n")
            append("CONTROLE : ").append(if (controlePret) "PRET" else "BLOQUE")
            append("  [").append(raisonControle).append("]\n")
            append("RTK : ").append(qualiteRtk)
            append("   Etat : ").append(etatMouvement.code).append("\n")
            append("Modele : ").append(modele.code)
            append("   Probabilite : ").append(probabiliteModelePct?.let { "$it %" } ?: "--").append("\n")
            append("Mesure : ").append(statutMesure)
            append("   Age : ").append(format(ageS, 2)).append(" s\n")
            append("Echantillons : ").append(echantillons)
            append("   Frais : ").append(echantillonsFrais)
            append("   FIX : ").append(echantillonsFixFrais)
            append("   Repetees : ").append(mesuresRepetees).append("\n")
            append("Fenetre : ").append(format(dureeHistoriqueS, 2)).append(" s")
            append("   Stabilite : ").append(format(stabiliteModeleS, 2)).append(" s\n")
            append("Confiance : ").append(confiancePct?.let { "$it %" } ?: "--").append("\n")
            append("Vitesse : ").append(format(vitesseMps, 2)).append(" m/s")
            append("   Cap : ").append(format(capDeg, 1)).append(" deg\n")
            append("Acceleration : ").append(format(accelerationMps2, 2)).append(" m/s2")
            append("   Virage : ").append(format(virageDegS, 1)).append(" deg/s\n")
            append("Residu : ").append(format(residuModeleM, 2)).append(" m")
            append("   Incertitude : ").append(format(incertitudeM, 2)).append(" m\n")
            append("Horizon : ").append(format(horizonS, 2)).append(" s")
            append("   Avance : ").append(format(distancePredictionM, 2)).append(" m\n")
            append("Actuelle : ").append(format(latActuelle, 7)).append(", ")
                .append(format(lonActuelle, 7)).append("\n")
            append("Predite : ").append(format(latPredite, 7)).append(", ")
                .append(format(lonPredite, 7))
        }

        companion object {
            private fun format(value: Double?, decimals: Int): String {
                if (value == null || !value.isFinite()) return "--"
                return ("%.${decimals}f").format(Locale.US, value)
            }
        }
    }

    private data class Echantillon(
        val lat: Double,
        val lon: Double,
        val timestampMs: Long,
        val qualite: String
    )

    private data class Vecteur(
        val estMps: Double,
        val nordMps: Double,
        val vitesseMps: Double,
        val capDeg: Double,
        val tempsMilieuMs: Long,
        val dureeObservationS: Double,
        val pointsUtilises: Int,
        val residuRegressionM: Double
    )

    private data class DroitePonderee(
        val pente: Double,
        val ordonneeOrigine: Double
    )

    private data class Mouvement(
        val modele: Modele,
        val vitesseMps: Double,
        val capDeg: Double,
        val accelerationMps2: Double,
        val virageDegS: Double,
        val ecartVitesseMps: Double,
        val ecartDirectionDeg: Double,
        val ecartAccelerationMps2: Double,
        val ecartVirageDegS: Double,
        val residuModeleM: Double,
        val incertitudeM: Double,
        val probabiliteModelePct: Int
    )

    private data class Barrieres(
        val displayReady: Boolean,
        val displayReason: String,
        val controlReady: Boolean,
        val controlReason: String
    )

    private val historique = ArrayDeque<Echantillon>()
    private var etatMouvement = EtatMouvement.ACQUISITION
    private var etatDepuisMs = 0L
    private var candidatEtat = EtatMouvement.ACQUISITION
    private var confirmationsCandidat = 0
    private var derniereQualite = "INCONNUE"
    private var qualiteStableDepuisMs = 0L
    private var derniereTransitionRtkMs = Long.MIN_VALUE
    private var mesuresRepeteesConsecutives = 0

    @Synchronized
    fun reinitialiser() {
        historique.clear()
        etatMouvement = EtatMouvement.ACQUISITION
        etatDepuisMs = 0L
        candidatEtat = EtatMouvement.ACQUISITION
        confirmationsCandidat = 0
        derniereQualite = "INCONNUE"
        qualiteStableDepuisMs = 0L
        derniereTransitionRtkMs = Long.MIN_VALUE
        mesuresRepeteesConsecutives = 0
    }

    /** Bloque explicitement lors d'une panne reseau/RTK. */
    @Synchronized
    fun signalerIndisponible(
        raison: String = "RTK_INDISPONIBLE",
        qualiteRtk: String = "LOST"
    ): EtatDiagnostic {
        historique.clear()
        etatMouvement = EtatMouvement.PERDU
        etatDepuisMs = System.currentTimeMillis()
        derniereQualite = normaliserQualite(qualiteRtk)
        qualiteStableDepuisMs = etatDepuisMs
        derniereTransitionRtkMs = etatDepuisMs
        return etatBloque(
            raison = raison,
            qualite = derniereQualite,
            ageS = Double.POSITIVE_INFINITY,
            horizonS = configuration.horizonMinS,
            lat = null,
            lon = null,
            echantillonAccepte = false,
            statutMesure = "ABSENTE"
        )
    }

    @Synchronized
    fun mettreAJour(
        lat: Double,
        lon: Double,
        ageS: Double,
        fiable: Boolean,
        qualiteRtk: String,
        timestampMs: Long = System.currentTimeMillis()
    ): EtatDiagnostic {
        val qualite = normaliserQualite(qualiteRtk)
        val age = if (ageS.isFinite()) ageS.coerceAtLeast(0.0) else Double.POSITIVE_INFINITY
        val timestampNormalise = normaliserTimestamp(timestampMs, age)
        val horizonInitial = calculerHorizonDynamique(age, qualite, etatMouvement, null)

        if (!coordonneesValides(lat, lon)) {
            basculerPerdu(timestampNormalise)
            return etatBloque(
                raison = "POSITION_INVALIDE",
                qualite = qualite,
                ageS = age,
                horizonS = horizonInitial,
                lat = null,
                lon = null,
                echantillonAccepte = false,
                statutMesure = "REJETEE"
            )
        }

        val raisonQualite = when {
            !qualiteAcceptee(qualite) -> when (qualite) {
                "GPS" -> "GPS_ONLY"
                "LOST" -> "RTK_PERDU"
                "RTK_GENERIC" -> "RTK_NON_QUALIFIE"
                else -> "RTK_NON_FIABLE"
            }
            !fiable -> "SOURCE_NON_FIABLE"
            else -> null
        }
        if (raisonQualite != null) {
            historique.clear()
            enregistrerTransitionQualite(qualite, timestampNormalise, forceDegrade = true)
            etatMouvement = if (qualite == "LOST") EtatMouvement.PERDU else EtatMouvement.DEGRADE
            etatDepuisMs = timestampNormalise
            return etatBloque(
                raison = raisonQualite,
                qualite = qualite,
                ageS = age,
                horizonS = horizonInitial,
                lat = lat,
                lon = lon,
                echantillonAccepte = false,
                statutMesure = "REJETEE"
            )
        }

        enregistrerTransitionQualite(qualite, timestampNormalise, forceDegrade = false)

        if (age > configuration.agePurgeS) {
            historique.clear()
            changerEtat(EtatMouvement.ACQUISITION, timestampNormalise)
            return etatBloque(
                raison = "MESURE_TROP_ANCIENNE",
                qualite = qualite,
                ageS = age,
                horizonS = horizonInitial,
                lat = lat,
                lon = lon,
                echantillonAccepte = false,
                statutMesure = "PURGE_AGE"
            )
        }
        if (age > configuration.ageMaxS) {
            return etatBloque(
                raison = "MESURE_TROP_ANCIENNE",
                qualite = qualite,
                ageS = age,
                horizonS = horizonInitial,
                lat = lat,
                lon = lon,
                echantillonAccepte = false,
                statutMesure = "ATTENTE_MESURE_FRAICHE"
            )
        }

        val nouveau = Echantillon(lat, lon, timestampNormalise, qualite)
        val dernier = historique.peekLast()
        if (dernier != null) {
            val dtS = (nouveau.timestampMs - dernier.timestampMs) / 1000.0
            when {
                dtS < -configuration.jitterHorodatageMaxS -> {
                    historique.clear()
                    historique.addLast(nouveau)
                    mesuresRepeteesConsecutives = 0
                    changerEtat(EtatMouvement.ACQUISITION, nouveau.timestampMs)
                    return etatBloque(
                        raison = "HORODATAGE_NON_MONOTONE",
                        qualite = qualite,
                        ageS = age,
                        horizonS = horizonInitial,
                        lat = lat,
                        lon = lon,
                        echantillonAccepte = true,
                        statutMesure = "REINITIALISATION_TEMPS"
                    )
                }

                dtS < configuration.intervalleMinS -> {
                    mesuresRepeteesConsecutives++
                    return evaluerHistorique(
                        qualite = qualite,
                        ageS = age,
                        echantillonAccepte = false,
                        statutMesure = "MESURE_REPETEE"
                    )
                }

                dtS > configuration.interruptionMaxS -> {
                    historique.clear()
                    historique.addLast(nouveau)
                    mesuresRepeteesConsecutives = 0
                    changerEtat(EtatMouvement.ACQUISITION, nouveau.timestampMs)
                    return etatBloque(
                        raison = "INTERRUPTION_MESURES",
                        qualite = qualite,
                        ageS = age,
                        horizonS = horizonInitial,
                        lat = lat,
                        lon = lon,
                        echantillonAccepte = true,
                        statutMesure = "REINITIALISATION_INTERVALLE"
                    )
                }

                else -> {
                    val distance = distanceM(dernier.lat, dernier.lon, lat, lon)
                    val distanceMax = configuration.vitesseMaxMps * dtS + configuration.margeSautM
                    if (!distance.isFinite() || distance > distanceMax) {
                        historique.clear()
                        historique.addLast(nouveau)
                        mesuresRepeteesConsecutives = 0
                        changerEtat(EtatMouvement.ACQUISITION, nouveau.timestampMs)
                        return etatBloque(
                            raison = "SAUT_POSITION",
                            qualite = qualite,
                            ageS = age,
                            horizonS = horizonInitial,
                            lat = lat,
                            lon = lon,
                            echantillonAccepte = true,
                            statutMesure = "REINITIALISATION_SAUT"
                        )
                    }
                }
            }
        }

        historique.addLast(nouveau)
        mesuresRepeteesConsecutives = 0
        while (historique.size > configuration.maxEchantillons) historique.removeFirst()
        purgerFenetreAbsolue(nouveau.timestampMs)

        return evaluerHistorique(
            qualite = qualite,
            ageS = age,
            echantillonAccepte = true,
            statutMesure = "ACCEPTEE"
        )
    }

    private fun evaluerHistorique(
        qualite: String,
        ageS: Double,
        echantillonAccepte: Boolean,
        statutMesure: String
    ): EtatDiagnostic {
        val dernier = historique.peekLast()
            ?: return etatBloque(
                raison = "AUCUNE_MESURE",
                qualite = qualite,
                ageS = ageS,
                horizonS = configuration.horizonMinS,
                lat = null,
                lon = null,
                echantillonAccepte = echantillonAccepte,
                statutMesure = statutMesure
            )

        var points = pointsActifs(dernier.timestampMs, fenetrePourEtat(etatMouvement))
        val duree = dureePointsS(points)
        if (points.size < configuration.minEchantillons) {
            proposerEtat(EtatMouvement.ACQUISITION, dernier.timestampMs)
            return etatBloque(
                raison = "ECHANTILLONS_INSUFFISANTS",
                qualite = qualite,
                ageS = ageS,
                horizonS = calculerHorizonDynamique(ageS, qualite, etatMouvement, null),
                lat = dernier.lat,
                lon = dernier.lon,
                echantillonAccepte = echantillonAccepte,
                statutMesure = statutMesure
            )
        }
        if (duree < configuration.dureeHistoriqueMinS) {
            proposerEtat(EtatMouvement.ACQUISITION, dernier.timestampMs)
            return etatBloque(
                raison = "DUREE_HISTORIQUE_INSUFFISANTE",
                qualite = qualite,
                ageS = ageS,
                horizonS = calculerHorizonDynamique(ageS, qualite, etatMouvement, null),
                lat = dernier.lat,
                lon = dernier.lon,
                echantillonAccepte = echantillonAccepte,
                statutMesure = statutMesure
            )
        }

        val recent = pointsDepuis(points, dernier.timestampMs, 4.0)
        val rayonImmobile = if (qualite == "FIX") configuration.rayonImmobileFixM else configuration.rayonImmobileFloatM
        val rayonRecent = rayonPointsM(recent)
        val vitesseNetteRecente = vitesseNettePointsMps(recent)
        val vecteursInitiaux = calculerVecteurs(points)
        if (vecteursInitiaux.isEmpty()) {
            return etatBloque(
                raison = "VITESSE_INCALCULABLE",
                qualite = qualite,
                ageS = ageS,
                horizonS = configuration.horizonMinS,
                lat = dernier.lat,
                lon = dernier.lon,
                echantillonAccepte = echantillonAccepte,
                statutMesure = statutMesure
            )
        }

        val immobileObserve = recent.size >= 4 &&
            rayonRecent <= rayonImmobile &&
            vitesseNetteRecente <= configuration.vitesseEntreeImmobileMps

        var mouvement = if (immobileObserve) {
            val vitesses = vecteursInitiaux.map { it.vitesseMps }
            val incertitude = incertitudeBaseQualite(qualite) + rayonRecent + ageS * 0.22
            Mouvement(
                modele = Modele.IMMOBILE,
                vitesseMps = 0.0,
                capDeg = 0.0,
                accelerationMps2 = 0.0,
                virageDegS = 0.0,
                ecartVitesseMps = ecartType(vitesses) ?: 0.0,
                ecartDirectionDeg = 0.0,
                ecartAccelerationMps2 = 0.0,
                ecartVirageDegS = 0.0,
                residuModeleM = rayonRecent,
                incertitudeM = incertitude,
                probabiliteModelePct = probabiliteImmobile(rayonRecent, rayonImmobile, vitesseNetteRecente)
            )
        } else {
            estimerMouvement(points, qualite, ageS)
                ?: return etatBloque(
                    raison = "MOUVEMENT_INCALCULABLE",
                    qualite = qualite,
                    ageS = ageS,
                    horizonS = configuration.horizonMinS,
                    lat = dernier.lat,
                    lon = dernier.lon,
                    echantillonAccepte = echantillonAccepte,
                    statutMesure = statutMesure
                )
        }

        val candidat = when {
            mouvement.modele == Modele.IMMOBILE -> EtatMouvement.IMMOBILE
            mouvement.modele == Modele.VIRAGE_CONSTANT || mouvement.modele == Modele.VIRAGE_ACCELERE -> EtatMouvement.VIRAGE
            mouvement.vitesseMps >= configuration.vitesseEntreeMouvementMps -> EtatMouvement.DEPLACEMENT_DROIT
            else -> EtatMouvement.ACQUISITION
        }
        proposerEtat(candidat, dernier.timestampMs)

        // Un changement d'etat peut raccourcir la fenetre immediatement.
        val nouvelleFenetre = fenetrePourEtat(etatMouvement)
        val pointsResserres = pointsActifs(dernier.timestampMs, nouvelleFenetre)
        if (pointsResserres.size >= configuration.minEchantillons && pointsResserres.size < points.size && mouvement.modele != Modele.IMMOBILE) {
            points = pointsResserres
            mouvement = estimerMouvement(points, qualite, ageS) ?: mouvement
        }

        val raisonPhysique = when {
            mouvement.vitesseMps > configuration.vitesseMaxMps -> "VITESSE_ABERRANTE"
            abs(mouvement.accelerationMps2) > configuration.accelerationMaxMps2 -> "ACCELERATION_ABERRANTE"
            abs(mouvement.virageDegS) > configuration.virageMaxDegS -> "VIRAGE_TROP_RAPIDE"
            mouvement.modele == Modele.VITESSE_CONSTANTE && mouvement.ecartDirectionDeg > 60.0 -> "DIRECTION_INSTABLE"
            mouvement.ecartVirageDegS > configuration.ecartVirageMaxDegS * 2.0 && etatMouvement == EtatMouvement.VIRAGE -> "VIRAGE_INSTABLE"
            mouvement.residuModeleM > max(12.0, configuration.residuModeleMaxM * 2.5) -> "MODELE_INCOHERENT"
            mouvement.incertitudeM > max(20.0, configuration.incertitudeMaxM * 4.0) -> "INCERTITUDE_TROP_ELEVEE"
            else -> null
        }
        if (raisonPhysique != null) {
            return etatBloque(
                raison = raisonPhysique,
                qualite = qualite,
                ageS = ageS,
                horizonS = calculerHorizonDynamique(ageS, qualite, etatMouvement, mouvement),
                lat = dernier.lat,
                lon = dernier.lon,
                echantillonAccepte = echantillonAccepte,
                statutMesure = statutMesure,
                mouvement = mouvement
            )
        }

        val horizon = calculerHorizonDynamique(ageS, qualite, etatMouvement, mouvement)
        val deplacement = if (mouvement.modele == Modele.IMMOBILE) {
            0.0 to 0.0
        } else {
            integrerMouvement(
                vitesseInitialeMps = mouvement.vitesseMps,
                capInitialDeg = mouvement.capDeg,
                accelerationMps2 = mouvement.accelerationMps2,
                virageDegS = mouvement.virageDegS,
                dureeS = horizon
            )
        }
        val distance = sqrt(deplacement.first.pow(2) + deplacement.second.pow(2))
        if (!distance.isFinite() || distance > configuration.distancePredictionMaxM) {
            return etatBloque(
                raison = "DISTANCE_PREDICTION_EXCESSIVE",
                qualite = qualite,
                ageS = ageS,
                horizonS = horizon,
                lat = dernier.lat,
                lon = dernier.lon,
                echantillonAccepte = echantillonAccepte,
                statutMesure = statutMesure,
                mouvement = mouvement
            )
        }

        val predite = deplacer(dernier.lat, dernier.lon, deplacement.second, deplacement.first)
        val confiance = calculerConfiance(qualite, ageS, mouvement)
        val stabiliteModele = stabiliteEtatS(dernier.timestampMs)
        val stabiliteQualite = stabiliteQualiteS(dernier.timestampMs)
        val transitionRecente = transitionRtkRecente(dernier.timestampMs)
        val echantillonsFrais = points.count {
            (dernier.timestampMs - it.timestampMs) <= 3_500L
        }
        val echantillonsFixFrais = points.count {
            it.qualite == "FIX" && (dernier.timestampMs - it.timestampMs) <= 3_500L
        }

        val barrieres = evaluerBarrieres(
            qualite = qualite,
            ageS = ageS,
            confiance = confiance,
            mouvement = mouvement,
            horizonS = horizon,
            latPredite = predite.first,
            lonPredite = predite.second,
            stabiliteModeleS = stabiliteModele,
            stabiliteQualiteS = stabiliteQualite,
            transitionRecente = transitionRecente,
            echantillonsFixFrais = echantillonsFixFrais
        )

        val raisonModele = when (mouvement.modele) {
            Modele.IMMOBILE -> "PRET_IMMOBILE"
            Modele.VIRAGE_ACCELERE -> "PRET_VIRAGE_ACCELERE"
            Modele.VIRAGE_CONSTANT -> "PRET_VIRAGE"
            Modele.ACCELERATION_CONSTANTE -> "PRET_ACCELERATION"
            Modele.VITESSE_CONSTANTE -> "PRET"
            else -> "PRET"
        }
        val raisonAffichage = if (barrieres.displayReady) {
            if (!echantillonAccepte && statutMesure == "MESURE_REPETEE") "${raisonModele}_MESURE_REPETEE" else raisonModele
        } else {
            barrieres.displayReason
        }

        return EtatDiagnostic(
            pret = barrieres.displayReady,
            controlePret = barrieres.controlReady,
            raison = raisonAffichage,
            raisonControle = barrieres.controlReason,
            qualiteRtk = qualite,
            etatMouvement = etatMouvement,
            modele = mouvement.modele,
            probabiliteModelePct = mouvement.probabiliteModelePct,
            stabiliteModeleS = stabiliteModele,
            stabiliteQualiteS = stabiliteQualite,
            transitionRtkRecente = transitionRecente,
            echantillonAccepte = echantillonAccepte,
            statutMesure = statutMesure,
            echantillons = points.size,
            echantillonsFrais = echantillonsFrais,
            echantillonsFixFrais = echantillonsFixFrais,
            mesuresRepetees = mesuresRepeteesConsecutives,
            dureeHistoriqueS = dureePointsS(points),
            ageS = ageS,
            confiancePct = confiance,
            timestampMesureMs = dernier.timestampMs,
            timestampCibleMs = dernier.timestampMs + (horizon * 1000.0).toLong(),
            vitesseMps = mouvement.vitesseMps,
            capDeg = if (mouvement.modele == Modele.IMMOBILE) null else mouvement.capDeg,
            accelerationMps2 = mouvement.accelerationMps2,
            virageDegS = mouvement.virageDegS,
            ecartVitesseMps = mouvement.ecartVitesseMps,
            ecartDirectionDeg = if (mouvement.modele == Modele.IMMOBILE) null else mouvement.ecartDirectionDeg,
            residuModeleM = mouvement.residuModeleM,
            incertitudeM = mouvement.incertitudeM,
            horizonS = horizon,
            distancePredictionM = distance,
            latActuelle = dernier.lat,
            lonActuelle = dernier.lon,
            latPredite = predite.first,
            lonPredite = predite.second
        )
    }

    private fun estimerMouvement(
        pointsComplets: List<Echantillon>,
        qualite: String,
        ageS: Double
    ): Mouvement? {
        var points = pointsComplets
        var vecteurs = calculerVecteurs(points)
        if (vecteurs.size < 2) return null

        val prelimTurn = calculerVirages(vecteurs)
        val turnMedian = mediane(prelimTurn) ?: 0.0
        val turnSpread = ecartTypeRobuste(prelimTurn) ?: 0.0
        val turnCandidate = abs(turnMedian) >= configuration.virageModeleMinDegS &&
            abs(turnMedian) <= configuration.virageMaxDegS &&
            turnSpread <= configuration.ecartVirageMaxDegS * 1.5

        val dernierTs = points.last().timestampMs
        val fenetreModele = if (turnCandidate || etatMouvement == EtatMouvement.VIRAGE) {
            configuration.fenetreVirageS
        } else {
            configuration.fenetreDeplacementS
        }
        points = pointsDepuis(points, dernierTs, fenetreModele)
        vecteurs = calculerVecteurs(points)
        if (vecteurs.size < 2) return null

        val tau = if (turnCandidate || etatMouvement == EtatMouvement.VIRAGE) {
            configuration.constanteTempsVirageS
        } else {
            configuration.constanteTempsDeplacementS
        }
        val referenceMs = vecteurs.last().tempsMilieuMs
        val poids = vecteurs.map { exp(-max(0.0, (referenceMs - it.tempsMilieuMs) / 1000.0) / tau) }
        val estCourant = moyennePonderee(vecteurs.map { it.estMps }, poids) ?: return null
        val nordCourant = moyennePonderee(vecteurs.map { it.nordMps }, poids) ?: return null
        val vitesseCourante = sqrt(estCourant * estCourant + nordCourant * nordCourant)
        val capLisse = calculerCap(estCourant, nordCourant)

        val vitesses = vecteurs.map { it.vitesseMps }
        val ecartVitesse = ecartTypeRobuste(vitesses) ?: 0.0
        val accelerations = calculerAccelerations(vecteurs)
        val virages = calculerVirages(vecteurs)
        val accelerationBrute = mediane(accelerations) ?: 0.0
        val ecartAcceleration = ecartTypeRobuste(accelerations) ?: 0.0
        val virageBrut = mediane(virages) ?: 0.0
        val ecartVirage = ecartTypeRobuste(virages) ?: 0.0

        val virageStable = abs(virageBrut) >= configuration.virageModeleMinDegS &&
            abs(virageBrut) <= configuration.virageMaxDegS &&
            ecartVirage <= configuration.ecartVirageMaxDegS
        val accelerationStable = abs(accelerationBrute) >= configuration.accelerationModeleMinMps2 &&
            abs(accelerationBrute) <= configuration.accelerationMaxMps2 &&
            ecartAcceleration <= configuration.ecartAccelerationMaxMps2

        val dernierVecteur = vecteurs.last()
        val extrapolationCapS = max(0.0, (points.last().timestampMs - dernierVecteur.tempsMilieuMs) / 1000.0)
        val capCourant = if (virageStable) {
            normaliserAngleDeg(dernierVecteur.capDeg + virageBrut * extrapolationCapS)
        } else {
            capLisse
        }
        val ecartDirection = ecartAngulairePondereDeg(vecteurs.map { it.capDeg }, capCourant, poids)

        val modele = when {
            virageStable && accelerationStable -> Modele.VIRAGE_ACCELERE
            virageStable -> Modele.VIRAGE_CONSTANT
            accelerationStable -> Modele.ACCELERATION_CONSTANTE
            else -> Modele.VITESSE_CONSTANTE
        }
        val acceleration = if (modele == Modele.ACCELERATION_CONSTANTE || modele == Modele.VIRAGE_ACCELERE) accelerationBrute else 0.0
        val virage = if (modele == Modele.VIRAGE_CONSTANT || modele == Modele.VIRAGE_ACCELERE) virageBrut else 0.0
        val tauResidu = if (modele == Modele.VIRAGE_CONSTANT || modele == Modele.VIRAGE_ACCELERE) {
            configuration.constanteTempsVirageS
        } else {
            configuration.constanteTempsDeplacementS
        }
        val residu = calculerResiduModele(points, vitesseCourante, capCourant, acceleration, virage, tauResidu)

        val horizonReference = when (modele) {
            Modele.VIRAGE_CONSTANT, Modele.VIRAGE_ACCELERE -> configuration.horizonVirageMaxS
            else -> configuration.horizonMaxS
        }
        val erreurDirectionRad = Math.toRadians(
            if (virageStable) min(45.0, ecartVirage * horizonReference + 2.0) else min(90.0, ecartDirection)
        )
        val transitionPenalty = if (transitionRtkRecente(points.last().timestampMs)) 0.60 else 0.0
        val incertitude = incertitudeBaseQualite(qualite) +
            residu +
            ecartVitesse * horizonReference +
            vitesseCourante * horizonReference * sin(min(PI / 2.0, erreurDirectionRad)) +
            0.5 * ecartAcceleration * horizonReference * horizonReference +
            ageS * 0.30 + transitionPenalty

        val probabilite = calculerProbabiliteModele(
            modele = modele,
            ecartDirection = ecartDirection,
            ecartVirage = ecartVirage,
            ecartAcceleration = ecartAcceleration,
            residu = residu,
            incertitude = incertitude
        )

        return Mouvement(
            modele = modele,
            vitesseMps = vitesseCourante.coerceAtLeast(0.0),
            capDeg = capCourant,
            accelerationMps2 = acceleration,
            virageDegS = virage,
            ecartVitesseMps = ecartVitesse,
            ecartDirectionDeg = ecartDirection,
            ecartAccelerationMps2 = ecartAcceleration,
            ecartVirageDegS = ecartVirage,
            residuModeleM = residu,
            incertitudeM = incertitude,
            probabiliteModelePct = probabilite
        )
    }

    private fun evaluerBarrieres(
        qualite: String,
        ageS: Double,
        confiance: Int,
        mouvement: Mouvement,
        horizonS: Double,
        latPredite: Double,
        lonPredite: Double,
        stabiliteModeleS: Double,
        stabiliteQualiteS: Double,
        transitionRecente: Boolean,
        echantillonsFixFrais: Int
    ): Barrieres {
        val displayReason = when {
            qualite != "FIX" && qualite != "FLOAT" -> "QUALITE_RTK_INSUFFISANTE"
            ageS > configuration.ageMaxS -> "MESURE_TROP_ANCIENNE"
            etatMouvement == EtatMouvement.PERDU -> "RTK_PERDU"
            etatMouvement == EtatMouvement.DEGRADE -> "MODE_DEGRADE"
            etatMouvement == EtatMouvement.ACQUISITION -> "MODELE_NON_STABILISE"
            stabiliteModeleS < configuration.stabiliteAffichageMinS -> "MODELE_NON_STABILISE"
            confiance < configuration.confianceAffichageMinPct -> "CONFIANCE_INSUFFISANTE"
            mouvement.incertitudeM > configuration.incertitudeAffichageMaxM -> "INCERTITUDE_EXCESSIVE"
            mouvement.residuModeleM > configuration.residuAffichageMaxM -> "RESIDU_EXCESSIF"
            mouvement.probabiliteModelePct < 35 -> "MODELE_INCOHERENT"
            !coordonneesValides(latPredite, lonPredite) -> "PREDICTION_INVALIDE"
            else -> "OK"
        }
        val displayReady = displayReason == "OK"

        val enVirage = etatMouvement == EtatMouvement.VIRAGE ||
            mouvement.modele == Modele.VIRAGE_CONSTANT || mouvement.modele == Modele.VIRAGE_ACCELERE
        val confianceMin = if (enVirage) configuration.confianceControleVirageMinPct else configuration.confianceControleMinPct
        val incertitudeMax = if (enVirage) configuration.incertitudeControleVirageMaxM else configuration.incertitudeControleMaxM
        val ageMax = if (enVirage) configuration.ageControleVirageMaxS else configuration.ageControleMaxS

        val controlReason = when {
            !displayReady -> displayReason
            qualite != "FIX" -> "QUALITE_RTK_INSUFFISANTE"
            transitionRecente -> "TRANSITION_RTK"
            stabiliteQualiteS < configuration.stabiliteFixControleMinS -> "QUALITE_RTK_NON_STABILISEE"
            echantillonsFixFrais < configuration.echantillonsFixControleMin -> "ECHANTILLONS_FIX_INSUFFISANTS"
            ageS > ageMax -> "MESURE_TROP_ANCIENNE"
            stabiliteModeleS < configuration.stabiliteModeleControleMinS -> "MODELE_NON_STABILISE"
            confiance < confianceMin -> "CONFIANCE_INSUFFISANTE"
            mouvement.incertitudeM > incertitudeMax -> "INCERTITUDE_EXCESSIVE"
            mouvement.residuModeleM > configuration.residuControleMaxM -> "RESIDU_EXCESSIF"
            mouvement.probabiliteModelePct < configuration.probabiliteModeleControleMinPct -> "MODELE_INCOHERENT"
            enVirage && horizonS > configuration.horizonVirageMaxS + 1e-9 -> "HORIZON_EXCESSIF"
            else -> "OK"
        }
        return Barrieres(
            displayReady = displayReady,
            displayReason = displayReason,
            controlReady = controlReason == "OK",
            controlReason = controlReason
        )
    }

    /**
     * V3.1: confiance de mesure, independante de la probabilite du modele.
     *
     * La V3 appliquait la probabilite du modele ici, puis une seconde fois
     * dans [evaluerBarrieres]. Cette double penalite rendait une marche reelle
     * stable presque toujours non autorisee. La coherence du modele conserve
     * sa propre barriere dure; la confiance mesure uniquement qualite, age,
     * incertitude et residu.
     */
    private fun calculerConfiance(qualite: String, ageS: Double, mouvement: Mouvement): Int {
        val qualiteFacteur = when (qualite) {
            "FIX" -> 1.00
            "FLOAT" -> 0.78
            "RTK_GENERIC" -> 0.55
            else -> 0.0
        }
        val incertitudeFacteur = exp(-mouvement.incertitudeM / 3.0)
        val residuFacteur = exp(-mouvement.residuModeleM / 2.5)
        val ageFacteur = (1.0 - ageS / configuration.ageMaxS).coerceIn(0.0, 1.0)
        val score = 100.0 * qualiteFacteur *
            (0.40 + 0.60 * incertitudeFacteur) *
            (0.50 + 0.50 * residuFacteur) *
            (0.65 + 0.35 * ageFacteur)
        return score.toInt().coerceIn(0, 100)
    }

    /**
     * V3.1: moyenne geometrique ponderee plutot qu'un produit brut.
     *
     * Un produit de cinq probabilites moderees sous-estime fortement un modele
     * pourtant coherent. La moyenne geometrique conserve une forte penalite
     * lorsqu'un facteur est mauvais, sans cumuler artificiellement toutes les
     * petites imperfections d'une marche humaine.
     */
    private fun calculerProbabiliteModele(
        modele: Modele,
        ecartDirection: Double,
        ecartVirage: Double,
        ecartAcceleration: Double,
        residu: Double,
        incertitude: Double
    ): Int {
        val enVirage = modele == Modele.VIRAGE_CONSTANT || modele == Modele.VIRAGE_ACCELERE
        val enAcceleration = modele == Modele.ACCELERATION_CONSTANTE || modele == Modele.VIRAGE_ACCELERE
        val directionLimit = if (enVirage) 55.0 else configuration.ecartDirectionMaxDeg

        val facteurs = ArrayList<Pair<Double, Double>>()
        facteurs += exp(-ecartDirection / max(1.0, directionLimit)) to 0.45
        facteurs += exp(-residu / max(0.2, configuration.residuAffichageMaxM)) to 0.30
        facteurs += exp(-incertitude / max(0.2, configuration.incertitudeAffichageMaxM)) to 0.25
        if (enVirage) {
            facteurs += exp(-ecartVirage / max(1.0, configuration.ecartVirageMaxDegS)) to 0.25
        }
        if (enAcceleration) {
            facteurs += exp(-ecartAcceleration / max(0.1, configuration.ecartAccelerationMaxMps2)) to 0.15
        }

        var sommeLog = 0.0
        var poidsTotal = 0.0
        for ((probabilite, poids) in facteurs) {
            val p = probabilite.coerceIn(1e-6, 1.0)
            sommeLog += poids * ln(p)
            poidsTotal += poids
        }
        val score = if (poidsTotal <= 0.0) 0.0 else exp(sommeLog / poidsTotal)
        return (100.0 * score).toInt().coerceIn(0, 100)
    }

    private fun probabiliteImmobile(rayon: Double, rayonMax: Double, vitesse: Double): Int {
        val pRayon = exp(-rayon / max(0.05, rayonMax))
        val pVitesse = exp(-vitesse / max(0.05, configuration.seuilImmobileMps))
        return (100.0 * sqrt(pRayon * pVitesse)).toInt().coerceIn(0, 100)
    }

    private fun calculerHorizonDynamique(
        ageS: Double,
        qualite: String,
        etat: EtatMouvement,
        mouvement: Mouvement?
    ): Double {
        val base = ageS.coerceAtLeast(0.0) + configuration.delaiPipelineS + configuration.delaiReactionDroneS
        var plafond = when (etat) {
            EtatMouvement.IMMOBILE -> 0.80
            EtatMouvement.VIRAGE -> configuration.horizonVirageMaxS
            EtatMouvement.DEPLACEMENT_DROIT -> configuration.horizonMaxS
            EtatMouvement.ACQUISITION, EtatMouvement.DEGRADE, EtatMouvement.PERDU -> 0.60
        }
        if (qualite == "FLOAT") plafond = min(plafond, 0.80)
        val incertitude = mouvement?.incertitudeM
        if (incertitude != null && incertitude.isFinite()) {
            val facteur = (1.0 - 0.55 * (incertitude / 8.0).coerceIn(0.0, 1.0))
            plafond = max(configuration.horizonMinS, plafond * facteur)
        }
        return base.coerceIn(configuration.horizonMinS, min(configuration.horizonMaxS, plafond))
    }

    private fun proposerEtat(candidat: EtatMouvement, timestampMs: Long) {
        if (etatMouvement == EtatMouvement.PERDU && candidat != EtatMouvement.ACQUISITION) {
            changerEtat(EtatMouvement.ACQUISITION, timestampMs)
        }
        if (candidat == etatMouvement) {
            candidatEtat = candidat
            confirmationsCandidat = 0
            return
        }
        if (candidat != candidatEtat) {
            candidatEtat = candidat
            confirmationsCandidat = 1
        } else {
            confirmationsCandidat++
        }
        val requis = when (candidat) {
            EtatMouvement.IMMOBILE -> configuration.confirmationsImmobile
            EtatMouvement.VIRAGE -> configuration.confirmationsVirage
            EtatMouvement.DEPLACEMENT_DROIT -> if (etatMouvement == EtatMouvement.VIRAGE) configuration.confirmationsSortieVirage else configuration.confirmationsMouvement
            EtatMouvement.ACQUISITION -> 2
            EtatMouvement.DEGRADE, EtatMouvement.PERDU -> 1
        }
        if (confirmationsCandidat >= requis) changerEtat(candidat, timestampMs)
    }

    private fun changerEtat(nouvelEtat: EtatMouvement, timestampMs: Long) {
        if (nouvelEtat == etatMouvement) return
        etatMouvement = nouvelEtat
        etatDepuisMs = timestampMs
        candidatEtat = nouvelEtat
        confirmationsCandidat = 0
        val fenetre = when (nouvelEtat) {
            EtatMouvement.IMMOBILE -> min(8.0, configuration.fenetreImmobileS)
            else -> fenetrePourEtat(nouvelEtat)
        }
        purgerAvant(timestampMs - (fenetre * 1000.0).toLong())
    }

    private fun enregistrerTransitionQualite(qualite: String, timestampMs: Long, forceDegrade: Boolean) {
        if (derniereQualite == "INCONNUE") {
            derniereQualite = qualite
            qualiteStableDepuisMs = timestampMs
            return
        }
        if (qualite != derniereQualite) {
            derniereQualite = qualite
            qualiteStableDepuisMs = timestampMs
            derniereTransitionRtkMs = timestampMs
            changerEtat(if (forceDegrade || qualite != "FIX") EtatMouvement.DEGRADE else EtatMouvement.ACQUISITION, timestampMs)
            purgerAvant(timestampMs - (configuration.fenetreDegradeS * 1000.0).toLong())
        }
    }

    private fun basculerPerdu(timestampMs: Long) {
        historique.clear()
        etatMouvement = EtatMouvement.PERDU
        etatDepuisMs = timestampMs
        derniereTransitionRtkMs = timestampMs
    }

    private fun stabiliteEtatS(timestampMs: Long): Double =
        if (etatDepuisMs <= 0L) 0.0 else max(0.0, (timestampMs - etatDepuisMs) / 1000.0)

    private fun stabiliteQualiteS(timestampMs: Long): Double =
        if (qualiteStableDepuisMs <= 0L) 0.0 else max(0.0, (timestampMs - qualiteStableDepuisMs) / 1000.0)

    private fun transitionRtkRecente(timestampMs: Long): Boolean =
        derniereTransitionRtkMs != Long.MIN_VALUE &&
            timestampMs - derniereTransitionRtkMs < (configuration.stabiliteFixControleMinS * 1000.0).toLong()

    private fun fenetrePourEtat(etat: EtatMouvement): Double = when (etat) {
        EtatMouvement.ACQUISITION -> configuration.fenetreAcquisitionS
        EtatMouvement.IMMOBILE -> configuration.fenetreImmobileS
        EtatMouvement.DEPLACEMENT_DROIT -> configuration.fenetreDeplacementS
        EtatMouvement.VIRAGE -> configuration.fenetreVirageS
        EtatMouvement.DEGRADE -> configuration.fenetreDegradeS
        EtatMouvement.PERDU -> configuration.fenetreDegradeS
    }

    private fun purgerFenetreAbsolue(timestampMs: Long) {
        val maxFenetre = maxOf(
            configuration.fenetreImmobileS,
            configuration.fenetreDeplacementS,
            configuration.fenetreAcquisitionS,
            configuration.fenetreVirageS,
            configuration.fenetreDegradeS
        ) + 2.0
        purgerAvant(timestampMs - (maxFenetre * 1000.0).toLong())
    }

    private fun purgerAvant(timestampMinMs: Long) {
        while (historique.isNotEmpty() && historique.peekFirst().timestampMs < timestampMinMs) {
            historique.removeFirst()
        }
    }

    private fun pointsActifs(timestampMs: Long, fenetreS: Double): List<Echantillon> =
        historique.filter { it.timestampMs >= timestampMs - (fenetreS * 1000.0).toLong() }

    private fun pointsDepuis(points: List<Echantillon>, timestampMs: Long, fenetreS: Double): List<Echantillon> =
        points.filter { it.timestampMs >= timestampMs - (fenetreS * 1000.0).toLong() }

    private fun calculerAccelerations(vecteurs: List<Vecteur>): List<Double> {
        val result = ArrayList<Double>()
        for (i in 1 until vecteurs.size) {
            val a = vecteurs[i - 1]
            val b = vecteurs[i]
            val dt = (b.tempsMilieuMs - a.tempsMilieuMs) / 1000.0
            if (dt <= 0.0) continue
            val v = (b.vitesseMps - a.vitesseMps) / dt
            if (v.isFinite()) result += v
        }
        return result
    }

    private fun calculerVirages(vecteurs: List<Vecteur>): List<Double> {
        val result = ArrayList<Double>()
        for (i in 1 until vecteurs.size) {
            val a = vecteurs[i - 1]
            val b = vecteurs[i]
            val dt = (b.tempsMilieuMs - a.tempsMilieuMs) / 1000.0
            if (dt <= 0.0) continue
            val v = differenceAngleDeg(b.capDeg, a.capDeg) / dt
            if (v.isFinite()) result += v
        }
        return result
    }

    private fun calculerResiduModele(
        points: List<Echantillon>,
        vitesseMps: Double,
        capDeg: Double,
        accelerationMps2: Double,
        virageDegS: Double,
        tauS: Double
    ): Double {
        if (points.size < 2) return Double.POSITIVE_INFINITY
        val dernier = points.last()
        var sommePonderee = 0.0
        var poidsTotal = 0.0
        for (point in points.dropLast(1)) {
            val duree = (point.timestampMs - dernier.timestampMs) / 1000.0
            val predit = integrerMouvement(vitesseMps, capDeg, accelerationMps2, virageDegS, duree)
            val observe = deltaLocalM(dernier.lat, dernier.lon, point.lat, point.lon)
            val erreurEst = observe.first - predit.first
            val erreurNord = observe.second - predit.second
            val age = abs(duree)
            val poids = exp(-age / max(0.1, tauS))
            sommePonderee += poids * (erreurEst * erreurEst + erreurNord * erreurNord)
            poidsTotal += poids
        }
        return if (poidsTotal <= 0.0) Double.POSITIVE_INFINITY else sqrt(sommePonderee / poidsTotal)
    }

    /** Retourne Pair(est, nord) en metres. Duree negative permise pour le backtest. */
    private fun integrerMouvement(
        vitesseInitialeMps: Double,
        capInitialDeg: Double,
        accelerationMps2: Double,
        virageDegS: Double,
        dureeS: Double
    ): Pair<Double, Double> {
        if (dureeS == 0.0) return 0.0 to 0.0
        val pas = max(4, (abs(dureeS) / 0.05).toInt() + 1)
        val dt = dureeS / pas
        var est = 0.0
        var nord = 0.0
        for (i in 0 until pas) {
            val tMilieu = (i + 0.5) * dt
            val vitesse = max(0.0, vitesseInitialeMps + accelerationMps2 * tMilieu)
            val capRad = Math.toRadians(capInitialDeg + virageDegS * tMilieu)
            est += vitesse * sin(capRad) * dt
            nord += vitesse * cos(capRad) * dt
        }
        return est to nord
    }

    /**
     * V3.1: produit des vecteurs de vitesse par regression locale robuste.
     *
     * A 5 Hz, deux positions voisines ne sont separees que d'environ 20 cm
     * pendant une marche. Le bruit GNSS et le balancement de l'antenne peuvent
     * alors dominer le cap instantane. Chaque vecteur est maintenant estime sur
     * une fenetre temporelle d'environ une seconde avec reponderation de Huber.
     * Les mesures brutes restent toutefois conservees pour les transitions RTK,
     * les sauts et l'age: aucun mecanisme de securite n'est contourne.
     */
    private fun calculerVecteurs(points: List<Echantillon>): List<Vecteur> {
        if (points.size < configuration.pointsMinRegressionVitesse) return emptyList()

        val resultat = ArrayList<Vecteur>()
        val fenetreMs = (configuration.fenetreRegressionVitesseS * 1000.0).toLong()
        val dureeMinMs = (configuration.dureeMinRegressionVitesseS * 1000.0).toLong()
        val pasMinMs = (configuration.pasVecteurMinS * 1000.0).toLong().coerceAtLeast(1L)
        var dernierVecteurMs = Long.MIN_VALUE

        for (fin in points.indices) {
            val timestampFin = points[fin].timestampMs
            if (dernierVecteurMs != Long.MIN_VALUE && timestampFin - dernierVecteurMs < pasMinMs) continue

            var debut = fin
            while (debut > 0 && timestampFin - points[debut - 1].timestampMs <= fenetreMs) {
                debut--
            }
            val fenetre = points.subList(debut, fin + 1)
            if (fenetre.size < configuration.pointsMinRegressionVitesse) continue
            if (timestampFin - fenetre.first().timestampMs < dureeMinMs) continue

            val vecteur = ajusterVitesseRobuste(fenetre) ?: continue
            if (
                vecteur.vitesseMps.isFinite() &&
                vecteur.vitesseMps <= configuration.vitesseMaxMps +
                    configuration.margeSautM / max(configuration.dureeMinRegressionVitesseS, vecteur.dureeObservationS)
            ) {
                resultat += vecteur
                dernierVecteurMs = timestampFin
            }
        }
        return resultat
    }

    private fun ajusterVitesseRobuste(points: List<Echantillon>): Vecteur? {
        if (points.size < configuration.pointsMinRegressionVitesse) return null
        val origine = points.first()
        val dureeS = (points.last().timestampMs - origine.timestampMs) / 1000.0
        if (dureeS < configuration.dureeMinRegressionVitesseS) return null

        val temps = ArrayList<Double>(points.size)
        val est = ArrayList<Double>(points.size)
        val nord = ArrayList<Double>(points.size)
        val poidsBase = ArrayList<Double>(points.size)

        for (point in points) {
            val t = (point.timestampMs - origine.timestampMs) / 1000.0
            val local = deltaLocalM(origine.lat, origine.lon, point.lat, point.lon)
            temps += t
            est += local.first
            nord += local.second
            val progression = (t / dureeS).coerceIn(0.0, 1.0)
            poidsBase += configuration.ponderationRecenceMin +
                (1.0 - configuration.ponderationRecenceMin) * progression
        }

        var poids = poidsBase.toMutableList()
        var droiteEst = ajusterDroitePonderee(temps, est, poids) ?: return null
        var droiteNord = ajusterDroitePonderee(temps, nord, poids) ?: return null

        repeat(configuration.iterationsRegressionRobuste) {
            val residus = points.indices.map { i ->
                val erreurEst = est[i] - (droiteEst.ordonneeOrigine + droiteEst.pente * temps[i])
                val erreurNord = nord[i] - (droiteNord.ordonneeOrigine + droiteNord.pente * temps[i])
                sqrt(erreurEst * erreurEst + erreurNord * erreurNord)
            }
            val med = mediane(residus) ?: 0.0
            val mad = mediane(residus.map { abs(it - med) }) ?: 0.0
            val echelle = max(configuration.residuHuberPlancherM, 1.4826 * mad)
            val seuil = max(configuration.residuHuberPlancherM, configuration.facteurHuberRegression * echelle)

            poids = residus.indices.map { i ->
                val r = residus[i]
                val huber = if (r <= seuil || r <= 1e-12) 1.0 else seuil / r
                poidsBase[i] * huber
            }.toMutableList()

            droiteEst = ajusterDroitePonderee(temps, est, poids) ?: return null
            droiteNord = ajusterDroitePonderee(temps, nord, poids) ?: return null
        }

        val estMps = droiteEst.pente
        val nordMps = droiteNord.pente
        val vitesse = sqrt(estMps * estMps + nordMps * nordMps)
        if (!vitesse.isFinite()) return null

        var sommeErreur = 0.0
        var poidsTotal = 0.0
        for (i in points.indices) {
            val erreurEst = est[i] - (droiteEst.ordonneeOrigine + estMps * temps[i])
            val erreurNord = nord[i] - (droiteNord.ordonneeOrigine + nordMps * temps[i])
            val p = poids[i].coerceAtLeast(0.0)
            sommeErreur += p * (erreurEst * erreurEst + erreurNord * erreurNord)
            poidsTotal += p
        }
        val residu = if (poidsTotal > 0.0) sqrt(sommeErreur / poidsTotal) else Double.POSITIVE_INFINITY

        return Vecteur(
            estMps = estMps,
            nordMps = nordMps,
            vitesseMps = vitesse,
            capDeg = calculerCap(estMps, nordMps),
            tempsMilieuMs = points.last().timestampMs,
            dureeObservationS = dureeS,
            pointsUtilises = points.size,
            residuRegressionM = residu
        )
    }

    private fun ajusterDroitePonderee(
        temps: List<Double>,
        valeurs: List<Double>,
        poids: List<Double>
    ): DroitePonderee? {
        if (temps.size < 2 || temps.size != valeurs.size || temps.size != poids.size) return null

        var poidsTotal = 0.0
        var sommeTemps = 0.0
        var sommeValeurs = 0.0
        for (i in temps.indices) {
            val p = poids[i].coerceAtLeast(0.0)
            poidsTotal += p
            sommeTemps += p * temps[i]
            sommeValeurs += p * valeurs[i]
        }
        if (poidsTotal <= 0.0) return null

        val moyenneTemps = sommeTemps / poidsTotal
        val moyenneValeurs = sommeValeurs / poidsTotal
        var numerateur = 0.0
        var denominateur = 0.0
        for (i in temps.indices) {
            val p = poids[i].coerceAtLeast(0.0)
            val dt = temps[i] - moyenneTemps
            numerateur += p * dt * (valeurs[i] - moyenneValeurs)
            denominateur += p * dt * dt
        }
        if (denominateur <= 1e-12) return null

        val pente = numerateur / denominateur
        val ordonnee = moyenneValeurs - pente * moyenneTemps
        if (!pente.isFinite() || !ordonnee.isFinite()) return null
        return DroitePonderee(pente, ordonnee)
    }

    private fun etatBloque(
        raison: String,
        qualite: String,
        ageS: Double,
        horizonS: Double,
        lat: Double?,
        lon: Double?,
        echantillonAccepte: Boolean,
        statutMesure: String,
        mouvement: Mouvement? = null
    ): EtatDiagnostic {
        val dernier = historique.peekLast()
        val timestamp = dernier?.timestampMs ?: System.currentTimeMillis()
        return EtatDiagnostic(
            pret = false,
            controlePret = false,
            raison = raison,
            raisonControle = raison,
            qualiteRtk = qualite,
            etatMouvement = etatMouvement,
            modele = mouvement?.modele ?: Modele.ACQUISITION,
            probabiliteModelePct = mouvement?.probabiliteModelePct,
            stabiliteModeleS = stabiliteEtatS(timestamp),
            stabiliteQualiteS = stabiliteQualiteS(timestamp),
            transitionRtkRecente = transitionRtkRecente(timestamp),
            echantillonAccepte = echantillonAccepte,
            statutMesure = statutMesure,
            echantillons = historique.size,
            echantillonsFrais = historique.count { timestamp - it.timestampMs <= 3_500L },
            echantillonsFixFrais = historique.count { it.qualite == "FIX" && timestamp - it.timestampMs <= 3_500L },
            mesuresRepetees = mesuresRepeteesConsecutives,
            dureeHistoriqueS = dureePointsS(historique.toList()),
            ageS = ageS,
            confiancePct = 0,
            timestampMesureMs = dernier?.timestampMs,
            timestampCibleMs = null,
            vitesseMps = mouvement?.vitesseMps,
            capDeg = mouvement?.capDeg,
            accelerationMps2 = mouvement?.accelerationMps2,
            virageDegS = mouvement?.virageDegS,
            ecartVitesseMps = mouvement?.ecartVitesseMps,
            ecartDirectionDeg = mouvement?.ecartDirectionDeg,
            residuModeleM = mouvement?.residuModeleM,
            incertitudeM = mouvement?.incertitudeM,
            horizonS = horizonS,
            distancePredictionM = null,
            latActuelle = lat,
            lonActuelle = lon,
            latPredite = null,
            lonPredite = null
        )
    }

    private fun dureePointsS(points: List<Echantillon>): Double {
        if (points.size < 2) return 0.0
        return max(0.0, (points.last().timestampMs - points.first().timestampMs) / 1000.0)
    }

    private fun vitesseNettePointsMps(points: List<Echantillon>): Double {
        if (points.size < 2) return 0.0
        val dt = (points.last().timestampMs - points.first().timestampMs) / 1000.0
        if (dt <= 0.0) return 0.0
        return distanceM(points.first().lat, points.first().lon, points.last().lat, points.last().lon) / dt
    }

    private fun rayonPointsM(points: List<Echantillon>): Double {
        if (points.isEmpty()) return 0.0
        val latCentre = mediane(points.map { it.lat }) ?: points.last().lat
        val lonCentre = mediane(points.map { it.lon }) ?: points.last().lon
        return points.maxOf { distanceM(latCentre, lonCentre, it.lat, it.lon) }
    }

    private fun incertitudeBaseQualite(qualite: String): Double = when (qualite) {
        "FIX" -> 0.08
        "FLOAT" -> 0.55
        "RTK_GENERIC" -> 1.50
        else -> 3.00
    }

    private fun qualiteAcceptee(qualite: String): Boolean =
        qualite == "FIX" || qualite == "FLOAT" ||
            (configuration.accepterRtkGenerique && qualite == "RTK_GENERIC")

    private fun normaliserQualite(qualite: String): String {
        val brut = qualite.trim().uppercase(Locale.US)
        if (brut.isBlank()) return "INCONNUE"
        val q = brut.replace('-', '_').replace(' ', '_')
        if (
            q.contains("NO_FIX") || q.contains("NOFIX") || q.contains("NOT_FIXED") ||
            (q.startsWith("NO_") && q.contains("FIX")) ||
            (q.startsWith("NOT_") && q.contains("FIX")) ||
            q.contains("INVALID") || q.contains("LOST") || q.contains("PERDU") ||
            q.contains("UNAVAILABLE") || q.contains("SEARCHING") || q == "NONE" || q == "NULL"
        ) return "LOST"
        if (
            q == "FLOAT" || q == "RTK_FLOAT" || q == "RTKFLOAT" ||
            q.contains("RTK_FLOAT") || q.contains("CARRIER_FLOAT") || q.contains("FLOAT_SOLUTION")
        ) return "FLOAT"
        if (
            q == "FIX" || q == "FIXED" || q == "RTK_FIX" || q == "RTK_FIXED" ||
            q == "RTKFIX" || q == "RTKFIXED" || q.contains("RTK_FIX") ||
            q.contains("CARRIER_FIXED") || q.contains("FIXED_SOLUTION")
        ) return "FIX"
        if (
            q == "GPS" || q == "GPS_ONLY" || q == "DGPS" || q == "SINGLE" ||
            q.contains("AUTONOMOUS") || q.contains("3D_FIX") || q.contains("GNSS")
        ) return "GPS"
        if (q == "RTK" || q == "RTK_GENERIC") return "RTK_GENERIC"
        return q.take(24)
    }

    private fun normaliserTimestamp(timestampMs: Long, ageS: Double): Long {
        val maintenant = System.currentTimeMillis()
        var ts = timestampMs
        if (ts in 1..9_999_999_999L) ts *= 1000L
        if (ts <= 0L || ts > maintenant + 60_000L) {
            ts = maintenant - (ageS.coerceAtLeast(0.0) * 1000.0).toLong()
        }
        return ts
    }

    private fun coordonneesValides(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    private fun calculerCap(estMps: Double, nordMps: Double): Double {
        var deg = Math.toDegrees(atan2(estMps, nordMps))
        if (deg < 0.0) deg += 360.0
        return deg
    }

    private fun normaliserAngleDeg(angle: Double): Double {
        var valeur = angle % 360.0
        if (valeur < 0.0) valeur += 360.0
        return valeur
    }

    private fun differenceAngleDeg(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private fun ecartAngulairePondereDeg(valeurs: List<Double>, centreDeg: Double, poids: List<Double>): Double {
        if (valeurs.size < 2 || valeurs.size != poids.size) return 0.0
        var somme = 0.0
        var total = 0.0
        valeurs.indices.forEach { i ->
            val p = poids[i].coerceAtLeast(0.0)
            somme += p * differenceAngleDeg(valeurs[i], centreDeg).pow(2)
            total += p
        }
        return if (total <= 0.0) 0.0 else sqrt(somme / total)
    }

    private fun moyennePonderee(valeurs: List<Double>, poids: List<Double>): Double? {
        if (valeurs.isEmpty() || valeurs.size != poids.size) return null
        var somme = 0.0
        var poidsTotal = 0.0
        valeurs.indices.forEach { i ->
            val p = poids[i].coerceAtLeast(0.0)
            somme += valeurs[i] * p
            poidsTotal += p
        }
        return if (poidsTotal <= 0.0) null else somme / poidsTotal
    }

    private fun mediane(valeurs: List<Double>): Double? {
        val triees = valeurs.filter { it.isFinite() }.sorted()
        if (triees.isEmpty()) return null
        val milieu = triees.size / 2
        return if (triees.size % 2 == 0) (triees[milieu - 1] + triees[milieu]) / 2.0 else triees[milieu]
    }

    private fun ecartType(valeurs: List<Double>): Double? {
        val v = valeurs.filter { it.isFinite() }
        if (v.size < 2) return null
        val moyenne = v.average()
        return sqrt(v.sumOf { (it - moyenne).pow(2) } / v.size)
    }

    private fun ecartTypeRobuste(valeurs: List<Double>): Double? {
        if (valeurs.size < 2) return null
        val med = mediane(valeurs) ?: return null
        val deviations = valeurs.map { abs(it - med) }
        val mad = mediane(deviations) ?: 0.0
        val limite = max(1e-6, mad * 3.5)
        val filtrees = valeurs.filter { abs(it - med) <= limite }
        return ecartType(if (filtrees.size >= 2) filtrees else valeurs)
    }

    private fun deplacer(lat: Double, lon: Double, nordM: Double, estM: Double): Pair<Double, Double> {
        val rayonTerre = 6_371_000.0
        val dLat = nordM / rayonTerre
        val cosLat = abs(cos(Math.toRadians(lat))).coerceAtLeast(1e-8)
        val dLon = estM / (rayonTerre * cosLat)
        return (lat + Math.toDegrees(dLat)) to (lon + Math.toDegrees(dLon))
    }

    private fun deltaLocalM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val rayonTerre = 6_371_000.0
        val latMoy = Math.toRadians((lat1 + lat2) / 2.0)
        val nord = Math.toRadians(lat2 - lat1) * rayonTerre
        val est = Math.toRadians(lon2 - lon1) * rayonTerre * cos(latMoy)
        return est to nord
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val d = deltaLocalM(lat1, lon1, lat2, lon2)
        return sqrt(d.first * d.first + d.second * d.second)
    }

    companion object {
        const val VERSION_MOTEUR: String = "3.1.0"

        fun extraireQualite(source: Any?): String {
            if (source == null) return "INCONNUE"
            if (source is String) return source
            val noms = listOf(
                "getRtk", "getRtkQuality", "getQualiteRtk", "getQualityRtk",
                "getFixType", "getSolution", "getQualite", "getQuality",
                "getFix", "getStatus", "getMode", "getReason"
            )
            for (nom in noms) {
                try {
                    val methode = source.javaClass.methods.firstOrNull {
                        it.name.equals(nom, ignoreCase = true) && it.parameterCount == 0
                    } ?: continue
                    val valeur = methode.invoke(source)?.toString()?.trim().orEmpty()
                    if (valeur.isNotEmpty()) return valeur
                } catch (_: Exception) {
                    // Une autre propriete sera essayee.
                }
            }
            val champs = listOf(
                "rtk", "rtkQuality", "qualiteRtk", "qualityRtk", "fixType",
                "solution", "qualite", "quality", "fix", "status", "mode", "reason"
            )
            for (nom in champs) {
                try {
                    val champ = source.javaClass.declaredFields.firstOrNull {
                        it.name.equals(nom, ignoreCase = true)
                    } ?: continue
                    champ.isAccessible = true
                    val valeur = champ.get(source)?.toString()?.trim().orEmpty()
                    if (valeur.isNotEmpty()) return valeur
                } catch (_: Exception) {
                    // Une autre propriete sera essayee.
                }
            }
            return "INCONNUE"
        }

        fun extraireTimestampMs(source: Any?, ageS: Double): Long {
            val maintenant = System.currentTimeMillis()
            if (source != null) {
                val noms = listOf(
                    "getTimestampMs", "getMeasurementTimeMs", "getGnssTimeMs",
                    "getTimeMs", "getEpochMs", "getTimestamp", "getTs",
                    "getTime", "getServerTimeMs"
                )
                for (nom in noms) {
                    try {
                        val methode = source.javaClass.methods.firstOrNull {
                            it.name.equals(nom, ignoreCase = true) && it.parameterCount == 0
                        } ?: continue
                        val valeur = methode.invoke(source)
                        val nombre = when (valeur) {
                            is Number -> valeur.toLong()
                            is String -> valeur.toDoubleOrNull()?.toLong()
                            else -> null
                        } ?: continue
                        return if (nombre in 1..9_999_999_999L) nombre * 1000L else nombre
                    } catch (_: Exception) {
                        // Une autre propriete sera essayee.
                    }
                }
            }
            return maintenant - (ageS.coerceAtLeast(0.0) * 1000.0).toLong()
        }
    }
}
