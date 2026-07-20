package ca.cineflight.stage.control

/**
 * OrchestrationMiroir — orchestration PURE du gate en MODE MIROIR (ObstacleSafetyGate étape 1/2).
 *
 * RÔLE : c'est UNIQUEMENT la couche d'orchestration extraite de PontDjiReel.envoyerVitesses.
 * Elle enchaîne les évaluateurs PURS déjà existants (ObstacleSafetyGate.evaluate pour le
 * vertical, .evaluateHorizontal pour l'horizontal) et renvoie une décision immuable. Elle NE
 * contient AUCUNE logique de sûreté propre : le gate reste la seule source de vérité, et on ne
 * recrée PAS un second évaluateur.
 *
 * INVARIANT MIROIR (le cœur de l'étape 1/2) :
 *   En mode miroir, `cmdAEnvoyer == cmdAssainie` TOUJOURS. La commande modulée par le gate
 *   (`cmdGate`) est CALCULÉE (et journalisable / testable), mais JAMAIS envoyée au SDK. Le
 *   pont n'appelle le SDK qu'avec `cmdAEnvoyer`. C'est ce qui rend le déploiement sans risque :
 *   on prouve que le gate calcule quelque chose, sans jamais l'appliquer au vol.
 *
 * PURETÉ : aucune dépendance Android (pas de Log, pas d'horloge). Les snapshots (qui portent
 * déjà leurs `ageMs`) et l'état sont passés en argument. 100% testable en JVM sans drone/SDK.
 */
object OrchestrationMiroir {

    /**
     * Décision d'émission calculée par l'orchestration miroir.
     *
     * @property cmdAssainie commande APRÈS assainissement/clamp (AssainisseurVitesse), AVANT gate.
     * @property cmdGate commande telle que MODULÉE par le gate (vertical puis horizontal). En
     *   miroir elle n'est PAS envoyée ; elle sert au diagnostic et aux tests.
     * @property cmdAEnvoyer commande réellement destinée au SDK. En miroir : == cmdAssainie.
     * @property newMirrorState état du gate MIS À JOUR (propre au miroir, jamais appliqué au vol).
     * @property actions actions du gate dans l'ordre [vertical, horizontal] (diagnostic).
     */
    data class EmissionDecision(
        val cmdAssainie: ObstacleSafetyGate.Vitesses,
        val cmdGate: ObstacleSafetyGate.Vitesses,
        val cmdAEnvoyer: ObstacleSafetyGate.Vitesses,
        val newMirrorState: ObstacleSafetyGate.SafetyState,
        val actions: List<ObstacleSafetyGate.GateAction>
    )

    /**
     * Calcule la décision d'émission en MODE MIROIR. PURE.
     *
     * Enchaînement identique à l'ancien code inline de PontDjiReel.envoyerVitesses :
     *   1. gate VERTICAL sur la commande assainie ;
     *   2. gate HORIZONTAL sur la SORTIE du vertical (même chaînage qu'avant) ;
     *   3. `cmdGate` = sortie horizontale, `cmdAEnvoyer` = cmdAssainie (miroir strict).
     *
     * @param cmdAssainie commande déjà assainie/clampée (repère corps).
     * @param snapshotVertical instantané vertical (âges déjà calculés en amont), ou null.
     * @param snapshotHorizontal instantané horizontal (âge déjà calculé en amont), ou null.
     * @param origin origine de la commande.
     * @param mirrorState état miroir courant du gate.
     * @return décision immuable. NE modifie rien en place.
     */
    fun deciderMiroir(
        cmdAssainie: ObstacleSafetyGate.Vitesses,
        snapshotVertical: ObstacleSafetyGate.VerticalSnapshot?,
        snapshotHorizontal: ObstacleSafetyGate.HorizontalSnapshot?,
        origin: CommandOrigin,
        mirrorState: ObstacleSafetyGate.SafetyState
    ): EmissionDecision {
        // 1. VERTICAL : évaluateur pur, sur la commande assainie.
        val resV = ObstacleSafetyGate.evaluate(
            cmd = cmdAssainie,
            perception = snapshotVertical,
            origin = origin,
            state = mirrorState,
            resumeRequested = false
        )
        // 2. HORIZONTAL : évaluateur pur, chaîné sur la sortie verticale (comme l'ancien inline).
        val resH = ObstacleSafetyGate.evaluateHorizontal(
            cmd = resV.command,
            horizontal = snapshotHorizontal,
            origin = origin,
            state = resV.state,
            resumeRequested = false
        )
        // 3. MIROIR STRICT : cmdGate est calculée mais on renvoie cmdAssainie comme cmdAEnvoyer.
        return EmissionDecision(
            cmdAssainie = cmdAssainie,
            cmdGate = resH.command,
            cmdAEnvoyer = cmdAssainie,        // invariant : cmdAEnvoyer == cmdAssainie
            newMirrorState = resH.state,
            actions = listOf(resV.action, resH.action)
        )
    }

    /**
     * Surcharge acceptant le snapshot COMBINE (lecture atomique unique). Delegue a la version a
     * deux snapshots — AUCUNE duplication de logique. A privilegier quand on veut journaliser
     * exactement les memes objets/ages que ceux transmis au gate (un seul now).
     *
     * @param snapshot lecture combinee vertical+horizontal, ou null si rien n'a ete publie.
     */
    fun deciderMiroir(
        cmdAssainie: ObstacleSafetyGate.Vitesses,
        snapshot: ca.cineflight.stage.sentinelle.PerceptionSnapshotStore.GatePerceptionSnapshot?,
        origin: CommandOrigin,
        mirrorState: ObstacleSafetyGate.SafetyState
    ): EmissionDecision = deciderMiroir(
        cmdAssainie = cmdAssainie,
        snapshotVertical = snapshot?.vertical,
        snapshotHorizontal = snapshot?.horizontal,
        origin = origin,
        mirrorState = mirrorState
    )
}
