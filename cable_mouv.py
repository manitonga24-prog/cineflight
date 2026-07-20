f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
if "mouvementActuel" in s:
    print("DEJA cable"); raise SystemExit
ch = 0

# 1) variable mouvement apres cibleHPlan
a1 = "private var cibleHPlan = 0.55f   // cadrage courant (americain par defaut)"
if a1 in s:
    s = s.replace(a1, a1 + "\n    private var mouvementActuel = 0   // 0=statik 1=orbite 2=travel 3=revel", 1); ch+=1

# 2) cablage des 4 boutons mouvement, apres le bloc des plans (apres bEns.setOnClickListener)
a2 = 'bEns.setOnClickListener  { choisir(bEns, 0.25f) }\n        }'
add2 = '''bEns.setOnClickListener  { choisir(bEns, 0.25f) }
        }
        run {
            val mStat = findViewById<Button>(R.id.btnMouvStatique)
            val mOrb  = findViewById<Button>(R.id.btnMouvOrbite)
            val mTrav = findViewById<Button>(R.id.btnMouvTravel)
            val mRev  = findViewById<Button>(R.id.btnMouvRevel)
            val tousM = listOf(mStat, mOrb, mTrav, mRev)
            fun choisirMouv(sel: Button, m: Int) {
                mouvementActuel = m
                tousM.forEach { it.setBackgroundColor(0xFF37474F.toInt()) }
                sel.setBackgroundColor(0xFF2E7D32.toInt())
            }
            mStat.setOnClickListener { choisirMouv(mStat, 0) }
            mOrb.setOnClickListener  { choisirMouv(mOrb, 1) }
            mTrav.setOnClickListener { choisirMouv(mTrav, 2) }
            mRev.setOnClickListener  { choisirMouv(mRev, 3) }
        }'''
if a2 in s:
    s = s.replace(a2, add2, 1); ch+=1

# 3) logique mouvement dans calculerSuivi : remplacer la ligne vy=0 fixe
# on cible la construction de vx et on ajoute les composantes selon le mouvement
a3 = '''        val errY = cy - 0.5f
        val gimbalPitch = (-errY * 25f).coerceIn(-20f, 20f)'''
add3 = '''        val errY = cy - 0.5f
        val gimbalPitch = (-errY * 25f).coerceIn(-20f, 20f)
        // --- composante mouvement cinematographique ---
        var vyMouv = 0f
        var vxMouv = vx
        var vzMouv = 0f
        var yawMouv = yawRate
        when (mouvementActuel) {
            1 -> { vyMouv = 0.6f }                    // ORBITE : translation laterale + yaw recentre (deja calcule)
            2 -> { vyMouv = (errX * 1.0f).coerceIn(-0.6f, 0.6f) }  // TRAVEL : accompagne le sujet lateralement
            3 -> { vxMouv = (vx - 0.4f).coerceIn(-0.8f, 0.8f); vzMouv = 0.3f }  // REVELATION : recule + monte
        }'''
if a3 in s:
    s = s.replace(a3, add3, 1); ch+=1

# 4) utiliser les composantes mouvement dans le return
a4 = '''        return RecepteurBridge.CommandeBridge(
            t = System.currentTimeMillis() / 1000.0,
            vx = vx, vy = 0f, vz = 0f, yawRate = yawRate,
            mode = "actif",
            recuA = System.currentTimeMillis(),
            gimbalPitch = gimbalPitch, gimbalYaw = 0f
        )'''
new4 = '''        return RecepteurBridge.CommandeBridge(
            t = System.currentTimeMillis() / 1000.0,
            vx = vxMouv, vy = vyMouv, vz = vzMouv, yawRate = yawMouv,
            mode = "actif",
            recuA = System.currentTimeMillis(),
            gimbalPitch = gimbalPitch, gimbalYaw = 0f
        )'''
if a4 in s:
    s = s.replace(a4, new4, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Cablage MOUVEMENTS :", ch, "/ 4")
print("mouvementActuel present :", "mouvementActuel" in s)