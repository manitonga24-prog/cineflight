package ca.cineflight.stage.cine

import android.content.Context

/**
 * ReglagesCine : parametres ajustables de l'assistant realisateur.
 *
 * Ces valeurs etaient "devinees" dans le moteur (durees de recette, vitesse du pivot,
 * facteur de vitesse global). On les rend reglables pour que l'utilisateur les calibre
 * AU VOL plutot que de figer des chiffres arbitraires. Stockage SharedPreferences,
 * memes fichier que le reste de l'app.
 *
 * Toutes les valeurs ont des bornes sures (coerceIn) : meme un reglage extreme reste
 * dans une plage que le validateur acceptera.
 */
class ReglagesCine(context: Context) {

    private val prefs = context.getSharedPreferences("cineflight_reglages", Context.MODE_PRIVATE)

    // --- Facteur de duree des plans (multiplie la duree de chaque recette) ---
    //  0.5 = plans deux fois plus courts, 2.0 = deux fois plus longs.
    fun getFacteurDuree(): Float = prefs.getFloat("cine_facteur_duree", 1.0f).coerceIn(0.5f, 3.0f)
    fun setFacteurDuree(v: Float) = prefs.edit().putFloat("cine_facteur_duree", v.coerceIn(0.5f, 3.0f)).apply()

    // --- Facteur de duree PAR RECETTE : chaque ambiance a SA propre duree. ---
    //  Cle = Catalogue.cleDe(recette). Defaut 1.0 (duree de base). Memes bornes sures.
    fun getFacteurDureeRecette(cle: String): Float =
        prefs.getFloat("cine_facteur_duree_" + cle, 1.0f).coerceIn(0.5f, 3.0f)
    fun setFacteurDureeRecette(cle: String, v: Float) =
        prefs.edit().putFloat("cine_facteur_duree_" + cle, v.coerceIn(0.5f, 3.0f)).apply()

    // --- Style de duree PAR RECETTE (Normal / Plan tenu / Ralenti). Defaut NORMAL. ---
    fun getStyleDuree(cle: String): StyleDuree {
        val n = prefs.getString("cine_style_duree_" + cle, null) ?: return StyleDuree.NORMAL
        return try { StyleDuree.valueOf(n) } catch (_: Exception) { StyleDuree.NORMAL }
    }
    fun setStyleDuree(cle: String, s: StyleDuree) =
        prefs.edit().putString("cine_style_duree_" + cle, s.name).apply()

    // --- Vitesse globale (decale d'un cran l'echelle Lente/Moderee/Rapide) ---
    //  -1 = tout plus lent, 0 = comme la grammaire, +1 = tout plus rapide.
    fun getDecalageVitesse(): Int = prefs.getInt("cine_decalage_vitesse", 0).coerceIn(-1, 1)
    fun setDecalageVitesse(v: Int) = prefs.edit().putInt("cine_decalage_vitesse", v.coerceIn(-1, 1)).apply()

    // --- Vitesse du pivot panoramique (deg/s), bornee sous le garde-fou pilote (60). ---
    fun getVitessePivotDps(): Float = prefs.getFloat("cine_pivot_dps", 18f).coerceIn(8f, 40f)
    fun setVitessePivotDps(v: Float) = prefs.edit().putFloat("cine_pivot_dps", v.coerceIn(8f, 40f)).apply()

    // --- Duree de la passe d'analyse (secondes) ---
    fun getDureeAnalyseS(): Int = prefs.getInt("cine_duree_analyse", 8).coerceIn(4, 20)
    fun setDureeAnalyseS(v: Int) = prefs.edit().putInt("cine_duree_analyse", v.coerceIn(4, 20)).apply()

    /** Applique le facteur de duree a une recette (duree minimale garantie : 6 s). */
    fun dureeAjustee(dureeBase: Int): Int = (dureeBase * getFacteurDuree()).toInt().coerceAtLeast(6)

    /** Applique le decalage de vitesse a une vitesse de grammaire. */
    fun vitesseAjustee(v: Vitesse): Vitesse {
        val ordre = listOf(Vitesse.LENTE, Vitesse.MODEREE, Vitesse.RAPIDE)
        val i = (ordre.indexOf(v) + getDecalageVitesse()).coerceIn(0, ordre.size - 1)
        return ordre[i]
    }
}

