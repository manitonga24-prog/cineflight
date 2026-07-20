import re
fp = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PiloteDrone.kt"
s = open(fp, encoding="utf-8").read()
ch = 0
if "fun modeleDrone" not in s:
    anc = "        fun enregistreEnCours(): Boolean  // true si la caméra enregistre\n    }"
    new = "        fun enregistreEnCours(): Boolean  // true si la caméra enregistre\n        fun modeleDrone(): String         // nom du modele de drone connecte\n    }"
    if anc in s:
        s = s.replace(anc, new, 1); ch+=1
    else:
        print("ancre interface KO")
    open(fp, "w", encoding="utf-8", newline="\n").write(s)
print("Interface :", ch)

# PontDjiReel : ajouter l'override
fr = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\control\PontDji.kt"
r = open(fr, encoding="utf-8").read()
ch2 = 0
if "override fun modeleDrone" not in r:
    # apres enregistreEnCours du pont REEL
    anc_reel = "    override fun enregistreEnCours(): Boolean = enregistre"
    # il y en a deux (reel + simule). On les traite separement.
    # 1er = reel
    idx = r.find(anc_reel)
    if idx != -1:
        ins = anc_reel + '\n    override fun modeleDrone(): String = if (modele.isNotEmpty()) modele else "Drone connecte"'
        r = r[:idx] + ins + r[idx+len(anc_reel):]
        ch2+=1
    # 2e = simule (chercher apres la 1ere insertion)
    idx2 = r.find(anc_reel, idx + len(ins) if idx!=-1 else 0)
    if idx2 != -1:
        ins2 = anc_reel + '\n    override fun modeleDrone(): String = "Simulateur"'
        r = r[:idx2] + ins2 + r[idx2+len(anc_reel):]
        ch2+=1
    open(fr, "w", encoding="utf-8", newline="\n").write(r)
print("Overrides PontDji :", ch2, "/ 2")