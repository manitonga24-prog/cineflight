package ca.cineflight.stage.control

/**
 * Hyperlapse - capture photo periodique pendant un deplacement (rail Cable-Cam + suivi).
 * Ne pilote PAS le drone : il dit juste "est-ce le moment de prendre une photo ?".
 * La boucle (MainActivity) appelle tempsPourPhoto() a chaque frame ; quand true, declenche
 * pont.declencherPhoto(). A la fin, les photos (sur SD) sont assemblees en video acceleree.
 */
class Hyperlapse {
    @Volatile var actif = false; private set
    @Volatile var nbPhotos = 0; private set
    /** Intervalle entre deux photos, en millisecondes (defaut 2 s). */
    var intervalleMs = 2000L
    private var dernierePhotoMs = 0L

    fun demarrer() {
        actif = true
        nbPhotos = 0
        dernierePhotoMs = 0L   // premiere photo immediate
    }

    fun arreter() { actif = false }

    /**
     * Renvoie true si l'intervalle est ecoule depuis la derniere photo.
     * Incremente le compteur et memorise l'instant. A appeler depuis la boucle.
     */
    fun tempsPourPhoto(): Boolean {
        if (!actif) return false
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierePhotoMs < intervalleMs) return false
        dernierePhotoMs = maintenant
        nbPhotos++
        return true
    }
}

