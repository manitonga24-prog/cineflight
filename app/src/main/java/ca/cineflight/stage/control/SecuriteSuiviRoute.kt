package ca.cineflight.stage.control

import ca.cineflight.stage.sentinelle.EtatCapteurs

/**
 * SecuriteSuiviRoute — TRADUIT un verdict ParcoursRoute en signal de securite
 * pour le NoyauSecurite (via EtatCapteurs.rtkSujetOk). DRY_RUN.
 *
 * >>> ROLE <<<
 * Le NoyauSecurite est souverain et bloque deja toute intention si rtkSujetOk=false
 * (-> RTK_SUJET_PERDU -> ERREUR_SYSTEME -> gel 0,0,0,0). Ce module NE MODIFIE PAS
 * le noyau : il calcule la valeur de rtkSujetOk a partir du verdict du profil route.
 *
 * REGLE (grave) : le profil route SUGGERE une cible, mais si ParcoursRoute refuse
 * (verdict != OK) OU si aucune cible n'est produite, alors rtkSujetOk=false ->
 * le noyau gele. "Non defini = non sur." Un doute sur la position/vitesse/age du
 * sujet ne doit JAMAIS laisser le drone bouger.
 *
 * Module PUR (depend seulement d'EtatCapteurs + ParcoursRoute), testable en JVM.
 */
object SecuriteSuiviRoute {

    /**
     * rtkSujetOk deduit du verdict ParcoursRoute.
     * true UNIQUEMENT si le verdict est OK (cible utilisable). Tout autre verdict
     * (RTK absent, RTK vieux, erreur de position, route invalide) -> false.
     */
    fun rtkSujetOk(res: ParcoursRoute.Resultat): Boolean =
        res.verdict == ParcoursRoute.Verdict.OK && res.cibleDrone != null

    /**
     * Construit l'EtatCapteurs a soumettre au noyau pour un tick de suivi route.
     * Combine les capteurs de base (video/yolo/telemetrie/stop/intrusion, tels que
     * l'orchestrateur les connait) avec rtkSujetOk DEDUIT du profil route.
     *
     * @param base capteurs deja evalues par l'orchestrateur (video, yolo, etc.).
     * @param res resultat du profil route (GenerateurMouvement.cibleRoute -> resultat).
     * @return copie de `base` avec rtkSujetOk force selon le verdict route.
     *
     * IMPORTANT : on ne fait que RESTREINDRE. Si base.rtkSujetOk etait deja false
     * pour une autre raison, il reste false (AND). On ne relache jamais la securite.
     */
    fun capteursPourRoute(
        base: EtatCapteurs,
        res: ParcoursRoute.Resultat
    ): EtatCapteurs {
        val ok = base.rtkSujetOk && rtkSujetOk(res)
        return base.copy(rtkSujetOk = ok)
    }
}
