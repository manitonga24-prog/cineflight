package ca.cineflight.stage.control

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Demande UNE fois le consentement a l'envoi des metadonnees de vol (loi 25 QC).
 *
 * A appeler depuis l'ecran principal apres le premier vol genere. La capture
 * LOCALE fonctionne quoi qu'il arrive ; ce dialogue ne conditionne QUE l'envoi
 * vers le serveur. Si l'utilisateur refuse, l'app fonctionne pleinement, les
 * donnees restent sur le telephone et ne partent jamais.
 */
object DialogueConsentement {

    fun demanderSiNecessaire(ctx: Context, onFini: (() -> Unit)? = null) {
        if (ConsentementCapture.consentementDejaDemande(ctx)) { onFini?.invoke(); return }
        AlertDialog.Builder(ctx)
            .setTitle("Aider a ameliorer CineFlight")
            .setMessage(
                "CineFlight peut envoyer des donnees anonymisees de vol (type de paysage, " +
                "recette utilisee, lumiere) pour ameliorer les recommandations futures.\n\n" +
                "Aucune video ni photo n'est envoyee. Aucune donnee n'est reliee a votre identite. " +
                "Vous pouvez refuser : l'application fonctionne exactement pareil."
            )
            .setPositiveButton("Accepter") { _, _ ->
                ConsentementCapture.definirConsentement(ctx, true)
                CaptureSync.synchroniser(ctx)
                onFini?.invoke()
            }
            .setNegativeButton("Refuser") { _, _ ->
                ConsentementCapture.definirConsentement(ctx, false)
                onFini?.invoke()
            }
            .setCancelable(false)
            .show()
    }
}

