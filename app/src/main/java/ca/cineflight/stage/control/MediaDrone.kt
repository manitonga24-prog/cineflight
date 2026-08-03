package ca.cineflight.stage.control

import android.content.Context
import android.util.Log
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import dji.v5.manager.datacenter.media.MediaFileListState
import dji.v5.manager.datacenter.media.MediaFileFilter
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * MediaDrone - acces a la phototheque du drone (carte SD) via MSDK v5.
 *
 * IMPORTANT : en mode media, la camera NE PEUT PLUS filmer / le flux live s'arrete.
 * A utiliser APRES le tournage, dans un ecran dedie. Toujours appeler quitter()
 * en sortant pour rendre la camera au mode normal.
 */
class MediaDrone {

    private val mgr get() = MediaDataCenter.getInstance().mediaManager

    /**
     * Emplacement d'où provenaient les fichiers du DERNIER listage réussi.
     *
     * Le repli automatique sur la mémoire interne quand la carte SD ne rend rien est utile,
     * mais il était SILENCIEUX : un écran qui affiche « contenu de la carte » sans savoir
     * qu'il lit la mémoire embarquée fait douter d'un formatage qui a pourtant réussi
     * (constaté le 2026-07-27). Toute vue qui nomme un support doit lire ce champ.
     */
    @Volatile var dernierEmplacement: CameraStorageLocation? = null
        private set

    /** Entre dans le module media (coupe le live). callback(true) si OK. */
    /** Formate la carte SD du drone (EFFACE TOUT). onFini(true) si succes.
     *  Cle confirmee par decouverte : CameraKey.KeyFormatStorage, location SDCARD. */
    fun formaterCarteSD(onFini: (Boolean, String) -> Unit) {
        // Le format est une operation CAMERA : il faut quitter le mode media d'abord
        // (en mode media la camera ne peut ni filmer ni formater -> erreur null).
        try { mgr.disable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { Log.i(TAG, "quitte le mode media avant de formater") }
            override fun onFailure(error: IDJIError) { Log.w(TAG, "disable avant format: ${error.description()}") }
        }) } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ formaterMaintenant(onFini) }, 1500)
    }

    /**
     * Tout ce que l'erreur SDK veut bien dire.
     *
     * ⚠ `description()` peut rendre **null** : le premier essai réel de formatage
     * (2026-07-27) affichait « Echec du formatage : null », c'est-à-dire rien. En ratissant
     * les autres champs on a obtenu `INVALID_REQUEST_IN_CURRENT_STATE`, code −13 sur
     * `CAMERA.FormatStorage` — ce qui a immédiatement désigné la cause : la caméra était
     * restée en mode média. Un message d'échec qui ne dit pas pourquoi ne vaut pas mieux
     * qu'un plantage silencieux.
     */
    private fun decrireErreur(error: IDJIError?): String {
        if (error == null) return "erreur nulle (le SDK n'a rien transmis)"
        val morceaux = ArrayList<String>()
        for (nom in listOf("description", "errorCode", "errorType", "hint", "innerCode")) {
            try {
                val v = error.javaClass.methods.firstOrNull {
                    it.name == nom && it.parameterTypes.isEmpty()
                }?.invoke(error)
                val s = (v as? Enum<*>)?.name ?: v?.toString()
                if (!s.isNullOrBlank() && s != "null") morceaux.add("$nom=$s")
            } catch (_: Throwable) { }
        }
        if (morceaux.isEmpty()) morceaux.add(error.toString())
        return morceaux.joinToString(" ")
    }

    private fun formaterMaintenant(onFini: (Boolean, String) -> Unit) {
        try {
            val cle = KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyFormatStorage)
            KeyManager.getInstance().performAction(cle, CameraStorageLocation.SDCARD,
                object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                        Log.i(TAG, "carte SD formatee")
                        onFini(true, "Carte SD formatee.")
                    }
                    override fun onFailure(error: IDJIError) {
                        val d = decrireErreur(error)
                        Log.e(TAG, "format echec: $d")
                        onFini(false, "Echec du formatage : $d")
                    }
                })
        } catch (e: Throwable) {
            Log.e(TAG, "format ex: ${e.message}")
            onFini(false, "Erreur : ${e.message}")
        }
    }

    fun activer(onFait: (Boolean) -> Unit) {
        try {
            mgr.enable(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "media active, attente 3s que le manager soit pret...")
                    // Le SDK media (Mini 3) a besoin de ~3s apres enable avant d'accepter liste/action.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.i(TAG, "media active (pret apres delai)"); onFait(true)
                    }, 3000)
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "enable echec: ${error.description()}"); onFait(false)
                }
            })
        } catch (e: Exception) { Log.e(TAG, "activer ex: ${e.message}"); onFait(false) }
    }

    /** Sort du module media (rend la camera au mode normal). */
    fun quitter() {
        try {
            mgr.disable(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { Log.i(TAG, "media quitte") }
                override fun onFailure(error: IDJIError) { Log.e(TAG, "disable: ${error.description()}") }
            })
        } catch (e: Exception) { Log.e(TAG, "quitter ex: ${e.message}") }
    }

    /** Liste les photos du drone. onListe(liste) sur succes.
     *  Le MediaManager met un instant a etre pret apres enable() : on reessaie
     *  jusqu'a 3 fois avec un court delai (corrige "execution could not be executed").
     *  CARTE SD d'abord ; si elle est VIDE, repli AUTOMATIQUE sur la MEMOIRE INTERNE
     *  (2 Go sur Mini 4 Pro) : la camera y ecrit quand DJI Fly est configure ainsi ou
     *  quand la carte n'etait pas reconnue au moment de la prise (constat 2026-07-25 :
     *  liste SD a 0 photo alors que des photos venaient d'etre prises). */
    fun listerPhotos(onListe: (List<MediaFile>) -> Unit) {
        listerMediasAvecEssais(3, CameraStorageLocation.SDCARD,
            MediaFileFilter.PHOTO, EXTENSIONS_PHOTO) { res ->
            // REPLI FILTRE (constat Mini 4 Pro 2026-07-25) : le filtre VIDEO listait 10
            // fichiers pendant que le filtre PHOTO rendait 0. Si PHOTO ne rend rien, on
            // reliste avec le filtre ALL du SDK (résolu par nom, sans dépendre de l'enum
            // exact) et on trie par extension .jpg/.dng côté app. Si ALL ne rend rien non
            // plus : il n'y a réellement aucune photo.
            val tout = MediaFileFilter.values().firstOrNull { it.name == "ALL" }
            if (res.isEmpty() && tout != null) {
                Log.w(TAG, "filtre PHOTO vide -> repli filtre ALL (tri par extension)")
                listerMediasAvecEssais(3, CameraStorageLocation.SDCARD, tout, EXTENSIONS_PHOTO, onListe)
            } else onListe(res)
        }
    }

    /** Comme [listerPhotos] mais pour les VIDÉOS (.mp4/.mov). Même mécanique complète :
     *  réessais, filtre SDK VIDEO, repli mémoire interne si la carte SD est vide. */
    /**
     * Liste les médias de la MÉMOIRE INTERNE du drone (2 Go sur Mini 4 Pro), sans jamais
     * regarder la carte.
     *
     * POURQUOI ÇA COMPTE. Quand aucune carte n'est insérée, pleine ou défaillante, le drone
     * enregistre là — sans le dire. Ces fichiers sont ensuite invisibles : les écrans
     * parlent de « la carte », un formatage ne les touche pas, et personne ne va les
     * chercher. Ils y restent jusqu'à ce que les 2 Go soient pleins, moment où les
     * nouveaux enregistrements cessent silencieusement.
     *
     * On utilise le filtre ALL et un tri par extension : sur ce drone, le filtre PHOTO
     * rend 0 alors que des photos existent (constat du 2026-07-25).
     */
    fun listerInterne(onListe: (List<MediaFile>) -> Unit) {
        val interne = CameraStorageLocation.values().firstOrNull { it.name.contains("INTERNAL") }
        if (interne == null) { Log.i(TAG, "pas de memoire interne sur ce drone"); onListe(emptyList()); return }
        val tout = MediaFileFilter.values().firstOrNull { it.name == "ALL" } ?: MediaFileFilter.PHOTO
        listerMediasAvecEssais(3, interne, tout, EXTENSIONS_PHOTO + EXTENSIONS_VIDEO) { res ->
            Log.i(TAG, "memoire interne : ${res.size} fichier(s)")
            onListe(res)
        }
    }

    fun listerVideos(onListe: (List<MediaFile>) -> Unit) {
        listerMediasAvecEssais(3, CameraStorageLocation.SDCARD,
            MediaFileFilter.VIDEO, EXTENSIONS_VIDEO, onListe)
    }

    private fun listerMediasAvecEssais(
        essaisRestants: Int,
        lieu: CameraStorageLocation,
        filtre: MediaFileFilter,
        extensions: List<String>,
        onListe: (List<MediaFile>) -> Unit,
    ) {
        try {
            // Source = emplacement demande (carte SD, puis memoire interne en repli)
            val source = MediaFileListDataSource.Builder().setLocation(lieu).build()
            mgr.setMediaFileDataSource(source)

            // Sur Mini 3 : le pull echoue si le MediaManager n'est pas IDLE. On attend IDLE.
            val etat = try { mgr.mediaFileListState } catch (e: Exception) { null }
            Log.i(TAG, "etat MediaManager: $etat")
            if (etat != MediaFileListState.IDLE && etat != MediaFileListState.UP_TO_DATE) {
                if (essaisRestants > 1) {
                    Log.w(TAG, "manager pas pret ($etat), nouvel essai dans 1000ms... restants=${essaisRestants - 1}")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        listerMediasAvecEssais(essaisRestants - 1, lieu, filtre, extensions, onListe)
                    }, 1000)
                    return
                }
            }

            // nettoie un pull precedent reste coince
            try { mgr.stopPullMediaFileListFromCamera() } catch (_: Exception) {}

            // param AVEC filtre SDK explicite (necessaire sur Mini 3/4)
            val param = PullMediaFileListParam.Builder()
                .mediaFileIndex(-1)
                .count(-1)
                .filter(filtre)
                .build()
            mgr.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    val data = try { mgr.mediaFileListData.data ?: emptyList() } catch (e: Exception) { emptyList() }
                    // DIAGNOSTIC : contenu BRUT avant filtrage (revele "0 fichier" vs
                    // "des fichiers mais aucun .jpg", deux problemes differents).
                    Log.i(TAG, "liste $lieu ($filtre): brut=${data.size}" +
                        if (data.isEmpty()) "" else " (" + data.take(5).joinToString { it.fileName } + "...)")
                    val medias = data.filter { m -> extensions.any { m.fileName.lowercase().endsWith(it) } }
                    Log.i(TAG, "medias trouves ($lieu, $filtre): ${medias.size}")
                    // REPLI MEMOIRE INTERNE : SD vide -> les medias sont peut-etre stockes
                    // dans la memoire embarquee du drone. Resolution du nom d'enum par
                    // recherche (INTERNAL_STORAGE selon la doc v5, sans dependre du nom exact).
                    val interne = CameraStorageLocation.values().firstOrNull { it.name.contains("INTERNAL") }
                    if (medias.isEmpty() && lieu == CameraStorageLocation.SDCARD && interne != null) {
                        Log.w(TAG, "carte SD vide -> essai memoire interne ($interne)")
                        listerMediasAvecEssais(2, interne, filtre, extensions, onListe)
                    } else {
                        // ⚠ ON RETIENT D'OÙ VIENNENT LES FICHIERS (2026-07-27). Le repli sur
                        // la mémoire interne est utile, mais silencieux : après un formatage
                        // de la carte, l'écran affichait « contenu de la carte : 4 photos »
                        // alors que ces fichiers étaient dans la mémoire embarquée. Un
                        // affichage qui se trompe de support fait douter d'un formatage qui
                        // a pourtant réussi.
                        dernierEmplacement = lieu
                        onListe(medias)
                    }
                }
                override fun onFailure(error: IDJIError) {
                    if (essaisRestants > 1) {
                        Log.w(TAG, "liste echec (${error.description()}), nouvel essai dans 1000ms... restants=${essaisRestants - 1}")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            listerMediasAvecEssais(essaisRestants - 1, lieu, filtre, extensions, onListe)
                        }, 1000)
                    } else {
                        Log.e(TAG, "liste echec definitif ($lieu): ${error.description()}"); onListe(emptyList())
                    }
                }
            })
        } catch (e: Exception) { Log.e(TAG, "lister ex: ${e.message}"); onListe(emptyList()) }
    }

    /**
     * Telecharge une photo pleine resolution vers le stockage du telephone.
     * onProgres(0..100), onFini(fichier ou null).
     */
    /**
     * Détail de progression : pourcentage, débit et temps restant ESTIMÉ (2026-07-26).
     * @param pct 0..100 (100 = fichier fermé et utilisable).
     * @param resteS secondes restantes estimées ; -1 si pas encore calculable.
     * @param debitMoS débit instantané en Mo/s ; -1 si inconnu.
     */
    data class Progres(val pct: Int, val resteS: Int, val debitMoS: Double)

    /**
     * Fichier DÉJÀ transféré et COMPLET sur le téléphone ? (2026-07-26)
     * Évite de refaire un transfert de plusieurs centaines de Mo pour rien.
     * Critère STRICT : même nom ET même taille que sur la carte du drone — un fichier
     * tronqué (transfert interrompu) n'est donc PAS considéré comme déjà présent.
     * @return le fichier local s'il est complet, sinon null.
     */
    fun dejaTelecharge(ctx: Context, mf: MediaFile): File? {
        return try {
            val f = File(File(ctx.getExternalFilesDir(null), "CineFlight"), mf.fileName)
            val attendue = mf.fileSize
            if (f.exists() && attendue > 0L && f.length() == attendue) f else null
        } catch (_: Throwable) { null }
    }

    /**
     * Ancienne signature (pourcentage seul) — CONSERVÉE pour les appelants existants.
     * ⚠ Pas de surcharge du même nom avec un lambda de type différent : Kotlin ne saurait
     * pas choisir et la compilation échouerait. D'où deux noms distincts.
     */
    fun telecharger(ctx: Context, mf: MediaFile, onProgres: (Int) -> Unit, onFini: (File?) -> Unit) {
        telechargerDetaille(ctx, mf, { p -> onProgres(p.pct) }, onFini)
    }

    /** Variante avec pourcentage + temps restant estimé + débit. */
    fun telechargerDetaille(
        ctx: Context,
        mf: MediaFile,
        onProgres: (Progres) -> Unit,
        onFini: (File?) -> Unit,
    ) {
        try {
            val dossier = File(ctx.getExternalFilesDir(null), "CineFlight")
            if (!dossier.exists()) dossier.mkdirs()
            val sortie = File(dossier, mf.fileName)
            if (sortie.exists()) sortie.delete()
            val fos = FileOutputStream(sortie)
            val tailleDeclaree = mf.fileSize.coerceAtLeast(1L)
            var ecrits = 0L      // octets RÉELLEMENT écrits sur le disque (référence sûre)
            val tDebut = android.os.SystemClock.elapsedRealtime()
            mf.pullOriginalMediaFileFromCamera(0, object : MediaFileDownloadListener {
                override fun onStart() { onProgres(Progres(0, -1, -1.0)) }
                /**
                 * ⚠ CORRECTIF 2026-07-26 : `c` est le CUMUL d'octets téléchargés (et `t` la
                 * taille totale) — l'ancien code faisait `recu += c`, ce qui ADDITIONNAIT des
                 * cumuls : le pourcentage montait plusieurs fois trop vite et affichait 100 %
                 * bien avant la fin réelle du transfert (constat pilote sur une vidéo).
                 * On utilise donc `c` tel quel, avec `t` comme total quand il est fourni, et
                 * on ne rend 100 % QUE dans onFinish (le fichier n'est utilisable qu'alors).
                 */
                override fun onProgress(t: Long, c: Long) {
                    val total = if (t > 0L) t else tailleDeclaree
                    val fait = if (c in 0..total) c else ecrits
                    val pct = ((fait * 100) / total).toInt().coerceIn(0, 99)
                    // TEMPS RESTANT : débit MOYEN depuis le début (plus stable qu'un débit
                    // instantané, qui saute à chaque paquet). Rendu seulement après 1,5 s et
                    // 1 % — avant, l'estimation serait fantaisiste et donnerait de faux espoirs.
                    val ecoule = (android.os.SystemClock.elapsedRealtime() - tDebut) / 1000.0
                    var reste = -1
                    var debit = -1.0
                    if (ecoule > 1.5 && fait > 0) {
                        debit = (fait / 1_048_576.0) / ecoule                 // Mo/s
                        val restantOctets = (total - fait).coerceAtLeast(0L)
                        reste = (restantOctets / (fait / ecoule)).toInt().coerceIn(0, 36_000)
                    }
                    onProgres(Progres(pct, reste, debit))
                }
                override fun onRealtimeDataUpdate(data: ByteArray, offset: Long) {
                    try { fos.write(data); ecrits += data.size } catch (e: Exception) { Log.e(TAG, "write: ${e.message}") }
                }
                override fun onFinish() {
                    try { fos.flush(); fos.close() } catch (_: Exception) {}
                    val secs = ((android.os.SystemClock.elapsedRealtime() - tDebut) / 1000.0).coerceAtLeast(0.1)
                    Log.i(TAG, "telecharge: ${sortie.absolutePath} (${ecrits / 1024} Ko en " +
                        "%.1f s, %.1f Mo/s)".format(secs, (ecrits / 1_048_576.0) / secs))
                    onProgres(Progres(100, 0, (ecrits / 1_048_576.0) / secs))
                    onFini(sortie)
                }
                override fun onFailure(error: IDJIError) {
                    try { fos.close() } catch (_: Exception) {}
                    Log.e(TAG, "dl echec: ${error.description()}"); onFini(null)
                }
            })
        } catch (e: Exception) { Log.e(TAG, "telecharger ex: ${e.message}"); onFini(null) }
    }

    /** Recupere la miniature (thumbnail) d'une photo sous forme de Bitmap. onMini(bitmap ou null). */
    fun miniature(mf: MediaFile, onMini: (Bitmap?) -> Unit) {
        try {
            mf.pullThumbnailFromCamera(object : CommonCallbacks.CompletionCallbackWithParam<Bitmap> {
                override fun onSuccess(bmp: Bitmap?) { onMini(bmp) }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "mini echec: ${error.description()}"); onMini(null)
                }
            })
        } catch (e: Exception) { Log.e(TAG, "miniature ex: ${e.message}"); onMini(null) }
    }

    companion object {
        private const val TAG = "MediaDrone"
        private val EXTENSIONS_PHOTO = listOf(".jpg", ".jpeg", ".dng")
        private val EXTENSIONS_VIDEO = listOf(".mp4", ".mov")
    }
}
