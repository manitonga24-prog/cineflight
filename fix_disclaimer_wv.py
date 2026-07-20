f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\DisclaimerActivity.kt"
s = open(f, encoding="utf-8").read()

# rendre la WebView plus robuste : encodage explicite + domStorage, et capter les erreurs
old = '''        val wv = WebView(this).apply {
            settings.javaScriptEnabled = false
            loadUrl("file:///android_asset/disclaimer.html")
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        racine.addView(wv)'''
new = '''        val wv = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = true
            settings.domStorageEnabled = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        try {
            wv.loadUrl("file:///android_asset/disclaimer.html")
        } catch (e: Throwable) {
            wv.loadData("<html><body style='padding:20px;font-family:sans-serif'><h2>Avis de responsabilite</h2><p>Le pilote est seul responsable du pilotage de son drone, de la securite des personnes et des biens, et du respect des lois.</p></body></html>", "text/html", "UTF-8")
        }
        racine.addView(wv)'''
if old in s:
    s = s.replace(old, new, 1)
    open(f, "w", encoding="utf-8", newline="\n").write(s)
    print("WebView securisee")
else:
    print("ANCRE NON TROUVEE")