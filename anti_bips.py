f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\EcouteContinue.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) Ajouter AudioManager pour couper les bips systeme
old1 = '''    private var sr: SpeechRecognizer? = null
    private var actif = false
    private val handler = Handler(Looper.getMainLooper())'''
new1 = '''    private var sr: SpeechRecognizer? = null
    private var actif = false
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager'''
if old1 in s:
    s = s.replace(old1, new1, 1); ch+=1

# 2) Couper le son systeme au demarrage, le retablir a l'arret
old2 = '''        actif = true
        onTexte("\\u25CF ECOUTE ACTIVE")
        lancerCycle()'''
new2 = '''        actif = true
        couperBips(true)
        onTexte("\\u25CF ECOUTE ACTIVE")
        lancerCycle()'''
if old2 in s:
    s = s.replace(old2, new2, 1); ch+=1

old3 = '''    fun arreter() {
        actif = false
        handler.removeCallbacksAndMessages(null)
        sr?.destroy(); sr = null
        onTexte("Ecoute arretee")
    }'''
new3 = '''    fun arreter() {
        actif = false
        handler.removeCallbacksAndMessages(null)
        sr?.destroy(); sr = null
        couperBips(false)
        onTexte("Ecoute arretee")
    }

    @Suppress("DEPRECATION")
    private fun couperBips(couper: Boolean) {
        try {
            val flux = intArrayOf(
                android.media.AudioManager.STREAM_SYSTEM,
                android.media.AudioManager.STREAM_NOTIFICATION,
                android.media.AudioManager.STREAM_MUSIC
            )
            for (st in flux) {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    audio.adjustStreamVolume(st,
                        if (couper) android.media.AudioManager.ADJUST_MUTE
                        else android.media.AudioManager.ADJUST_UNMUTE, 0)
                } else {
                    audio.setStreamMute(st, couper)
                }
            }
        } catch (e: Exception) { Log.w("EcouteContinue", "mute: $e") }
    }'''
if old3 in s:
    s = s.replace(old3, new3, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Anti-bips :", ch, "/ 3")