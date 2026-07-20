package ca.cineflight.stage

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper

/**
 * StageApplication — classe Application de l'app.
 *
 * Le DJI Mobile SDK v5 livre des librairies natives compressees. Elles doivent
 * etre decompressees AVANT toute utilisation du SDK, via Helper.install(), qui
 * DOIT etre appele dans attachBaseContext() d'une classe Application (c'est le
 * tout premier point d'entree de l'app, avant onCreate de la moindre Activity).
 *
 * Sans cette etape, SDKManager.init() echoue (libs natives introuvables).
 *
 * Declaree dans AndroidManifest.xml : <application android:name=".StageApplication" ...>
 */
class StageApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Decompresse et installe les libs natives du MSDK v5. Indispensable.
        Helper.install(this)
    }
}

