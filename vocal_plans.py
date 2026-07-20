f = r"C:\cineflight_android\CineFlightSolo\app\src\main\java\ca\cineflight\stage\MainActivity.kt"
s = open(f, encoding="utf-8").read()
ch = 0

# 1) interpreterVocal : ajouter la reconnaissance des 4 plans (AVANT plan_suivant pour priorite)
old1 = '''            "approche" in t || "rapproche" in t -> "approche"
            "suivant" in t -> "plan_suivant"'''
new1 = '''            "approche" in t || "rapproche" in t -> "approche"
            "gros" in t -> "plan_gros"
            ("am\\u00e9ricain" in t || "americain" in t || "am\\u00e9ricaine" in t) -> "plan_americain"
            ("d\\u00e9taill" in t || "detaill" in t || "pied" in t) -> "plan_pied"
            "ensemble" in t || "large" in t -> "plan_ensemble"
            "suivant" in t -> "plan_suivant"'''
if old1 in s:
    s = s.replace(old1, new1, 1); ch+=1

# 2) executerCommandeVocale : ajouter les actions pour chaque plan
old2 = '''            "plan_suivant" -> cyclerPlan()
        }'''
new2 = '''            "plan_suivant" -> cyclerPlan()
            "plan_gros" -> { planIndex = 0; findViewById<Button>(R.id.btnPlanGros).performClick() }
            "plan_americain" -> { planIndex = 1; findViewById<Button>(R.id.btnPlanAmericain).performClick() }
            "plan_pied" -> { planIndex = 2; findViewById<Button>(R.id.btnPlanPied).performClick() }
            "plan_ensemble" -> { planIndex = 3; findViewById<Button>(R.id.btnPlanEnsemble).performClick() }
        }'''
if old2 in s:
    s = s.replace(old2, new2, 1); ch+=1

open(f, "w", encoding="utf-8", newline="\n").write(s)
print("Commandes plans vocales :", ch, "/ 2")