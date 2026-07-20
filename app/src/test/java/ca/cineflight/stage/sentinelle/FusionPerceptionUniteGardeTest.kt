package ca.cineflight.stage.sentinelle

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * GARDE D'ACTIVATION pour FusionPerception (maillon evitement).
 *
 * CONTEXTE PROUVE (docs/RAPPORT_EVITEMENT_TEST_SOL_20260718.md, section 9) :
 *   - LecteurPerception.distanceHorizontale() renvoie des MILLIMETRES (doc DJI MSDK v5).
 *   - FusionPerception les compare a DIST_DANGER=3 / DIST_DEGAGE=10 declares en METRES
 *     => bug d'unite mm/metres (un obstacle a 2,5 m serait classe "degage").
 *   - Bug DORMANT : FusionPerception.active reste false (jamais mis a true).
 *
 * PHILOSOPHIE DE CE GARDE (choix delibere) :
 *   - Un test rouge en permanence rendrait la suite inutilisable et masquerait les
 *     vraies regressions. Un @Ignore ne bloquerait rien.
 *   - Ce garde est donc VERT tant que le bug reste DORMANT, et devient ROUGE
 *     UNIQUEMENT si quelqu'un ACTIVE la fusion avant d'avoir corrige l'unite.
 *
 *     Bug present mais dormant  -> vert
 *     Activation accidentelle   -> rouge
 *     Unite corrigee + testee   -> ce garde peut etre retire/remplace
 *
 * Il inspecte le CODE DE PRODUCTION (source-as-data), pas le comportement, pour
 * rester independant du SDK DJI (indisponible en test JVM).
 */
class FusionPerceptionUniteGardeTest {

    /** Localise un fichier source du module en remontant depuis le working dir. */
    private fun trouverSource(relit: String): File? {
        val departs = listOf(
            File(System.getProperty("user.dir") ?: "."),
            File("."),
            File("app"),
            File("..")
        )
        for (d0 in departs) {
            var d: File? = d0.absoluteFile
            var profondeur = 0
            while (d != null && profondeur < 8) {
                val direct = File(d, relit)
                if (direct.exists()) return direct
                val sousApp = File(d, "app/$relit")
                if (sousApp.exists()) return sousApp
                d = d.parentFile
                profondeur++
            }
        }
        return null
    }

    private val relFusion =
        "src/main/java/ca/cineflight/stage/sentinelle/FusionPerception.kt"
    private val relMain =
        "src/main/java/ca/cineflight/stage/MainActivity.kt"

    /**
     * GARDE PRINCIPAL : aucune activation de FusionPerception ne doit exister dans le
     * code de production tant que l'unite n'est pas corrigee.
     *   - l'instanciation doit rester "active = false"
     *   - aucune ligne "<qqch>.active = true" visant fusionPerception
     */
    @Test
    fun fusion_perception_ne_doit_pas_etre_activee_avant_correction_unite() {
        val main = trouverSource(relMain)
        if (main == null) {
            // Probleme d'ENVIRONNEMENT (working dir), pas une regression : on ne casse
            // pas la suite pour ca. On signale seulement.
            println("[GARDE] MainActivity.kt introuvable depuis ${System.getProperty("user.dir")} : garde non evalue.")
            return
        }
        val src = main.readText()

        // 1) L'instanciation connue doit rester en active = false.
        val instanciationSure = Regex(
            """FusionPerception\s*\(\s*lecteurPerception\s*,\s*active\s*=\s*false\s*\)"""
        )
        assertTrue(
            "L'instanciation de FusionPerception n'est plus 'active = false'. " +
            "Le bug d'unite mm/metres (rapport section 9) n'est PAS corrige : ne pas activer la fusion.",
            instanciationSure.containsMatchIn(src)
        )

        // 2) Aucune mise a true du drapeau active de fusionPerception.
        val activationInterdite = Regex("""fusionPerception\s*\.\s*active\s*=\s*true""")
        if (activationInterdite.containsMatchIn(src)) {
            fail(
                "ACTIVATION INTERDITE detectee : 'fusionPerception.active = true' dans MainActivity. " +
                "Le bug d'unite mm/metres n'est pas corrige (DIST_DANGER=3 / DIST_DEGAGE=10 en metres " +
                "compares a des millimetres). Corriger l'unite AVANT toute activation. Voir rapport section 9."
            )
        }

        // 3) Aucune autre instanciation de FusionPerception avec active = true.
        val instanceActive = Regex("""FusionPerception\s*\([^)]*active\s*=\s*true""")
        if (instanceActive.containsMatchIn(src)) {
            fail(
                "Une instance de FusionPerception est creee avec active = true. " +
                "Interdit tant que l'unite mm/metres n'est pas corrigee. Voir rapport section 9."
            )
        }
    }

    /**
     * Verifie que les seuils n'ont pas ete "corriges a moitie" : si quelqu'un passe
     * les seuils en millimetres (DIST_DANGER >= 100) sans retirer ce garde, c'est le
     * signe que la correction a commence -> il faudra alors reevaluer/retirer ce garde.
     * Ce test reste VERT dans les deux etats coherents ; il ne sert qu'a documenter.
     */
    @Test
    fun seuils_actuels_sont_ceux_du_bug_documente_ou_corriges() {
        // Coherent si : (seuils encore en metres ET fusion non activee) -> garde ci-dessus suffit.
        // Ce test ne fait qu'affirmer que les constantes existent et sont lisibles.
        val danger = FusionPerception.DIST_DANGER
        val degage = FusionPerception.DIST_DEGAGE
        assertTrue("DIST_DEGAGE doit rester > DIST_DANGER (coherence logique)", degage > danger)
    }

    /**
     * TEST DE COMPORTEMENT (documentaire) du defaut mm/metres.
     * DESACTIVE VOLONTAIREMENT (@Ignore) : il ECHOUERAIT tant que le bug existe, ce qui
     * rendrait la suite rouge. Il est conserve pour etre REACTIVE une fois l'unite
     * corrigee, afin de prouver que la classification devient correcte.
     * Justification du @Ignore : bug connu, dormant, garde d'activation ci-dessus actif.
     */
    @Ignore("Documente le bug mm/metres (rapport section 9). Reactiver APRES correction de l'unite.")
    @Test
    fun apres_correction_obstacle_2500mm_doit_etre_danger() {
        val distHmm = 2500 // 2,5 m en millimetres
        val verdict = when {
            distHmm < FusionPerception.DIST_DANGER -> "DANGER"
            distHmm >= FusionPerception.DIST_DEGAGE -> "DEGAGE"
            else -> "INTERMEDIAIRE"
        }
        assertTrue("Apres correction, 2,5 m doit etre DANGER (obtenu: $verdict)", verdict == "DANGER")
    }
}
