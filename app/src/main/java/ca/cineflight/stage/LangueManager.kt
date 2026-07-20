package ca.cineflight.stage

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * LangueManager — gestion centralisee de la langue de l'app (internationalisation).
 *
 * Langue PAR DEFAUT = anglais (res/values/). Le francais est dans res/values-fr/.
 * La langue choisie est appliquee a TOUTE l'app via AppCompatDelegate et memorisee
 * automatiquement (service AppLocalesMetadataHolderService declare dans le manifest).
 * Sans choix explicite, l'app suit la langue du telephone si elle est supportee,
 * sinon elle affiche l'anglais.
 *
 * ── AJOUTER UNE LANGUE (ex. espagnol "es") ──────────────────────────────────
 *   1) creer res/values-es/strings.xml (memes cles que res/values/) ;
 *   2) ajouter <locale android:name="es"/> dans res/xml/locales_config.xml ;
 *   3) ajouter Langue("es", "Espanol") dans LANGUES ci-dessous ;
 *   (optionnel) traduire le guide en assets/guide-es.html.
 * Rien d'autre a modifier : tous les ecrans migres vers getString(R.string.*)
 * basculeront automatiquement.
 */
object LangueManager {

    data class Langue(val code: String, val nom: String)

    /** Langues proposees dans le selecteur (ordre d'affichage). */
    val LANGUES = listOf(
        Langue("en", "English"),
        Langue("fr", "Français")
    )

    /** Code de la langue actuellement affichee ("en", "fr", ...). */
    fun langueActuelle(ctx: Context): String {
        val app = AppCompatDelegate.getApplicationLocales()
        val choisi = if (!app.isEmpty) app.get(0)?.language else null
        if (choisi != null && LANGUES.any { it.code == choisi }) return choisi
        val sys = ctx.resources.configuration.locales.get(0).language
        return if (LANGUES.any { it.code == sys }) sys else "en"
    }

    /** Applique une langue a TOUTE l'app (les ecrans se recreent automatiquement). */
    fun appliquer(code: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
    }

    /** Ouvre un petit choix de langue. La selection s'applique immediatement. */
    fun choisir(activite: Activity) {
        val actuel = langueActuelle(activite)
        val noms = LANGUES.map { it.nom }.toTypedArray()
        val idx = LANGUES.indexOfFirst { it.code == actuel }.coerceAtLeast(0)
        AlertDialog.Builder(activite)
            .setTitle(activite.getString(R.string.choix_langue))
            .setSingleChoiceItems(noms, idx) { d, i ->
                d.dismiss()
                appliquer(LANGUES[i].code)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
