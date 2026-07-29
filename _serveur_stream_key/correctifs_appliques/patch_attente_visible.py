import shutil, time, sys
p = sys.argv[1] if len(sys.argv) > 1 else "cineflight_web/stream_key.py"
src = open(p, encoding="utf-8").read()
if "ratio.attente" in src:
    print("Deja patche - rien a faire."); raise SystemExit
shutil.copy(p, p + ".bak_" + time.strftime("%Y%m%d_%H%M%S"))

a1 = "  .ratio iframe{ position:absolute; inset:0; width:100%; height:100%; border:0; }"
n1 = a1 + """
  /* Mode attente : le cadre grandit avec son contenu (sinon logo, description
     et texte d'aide sont coupes par le ratio 16:9 + overflow:hidden). */
  .ratio.attente{ padding-top:0; }
  .ratio.attente .vide{ position:relative; inset:auto; min-height:380px; }"""
assert a1 in src, "ancre CSS introuvable"
src = src.replace(a1, n1, 1)

a2 = '<div class="ratio">'
assert a2 in src, "ancre div ratio introuvable"
src = src.replace(a2, '<div class="ratio%MODE_RATIO%">', 1)

a3 = '        auto = ""  # live present : pas besoin de rafraichir'
assert a3 in src, "ancre live introuvable"
src = src.replace(a3, a3 + '\n        mode_ratio = ""  # live : cadre 16:9 normal', 1)

a4 = '        auto = \'<meta http-equiv="refresh" content="30">\''
assert a4 in src, "ancre attente introuvable"
src = src.replace(a4, a4 + '\n        mode_ratio = " attente"  # cadre extensible : rien n\'est coupe', 1)

a5 = '            .replace("%AUTO_REFRESH%", auto)'
assert a5 in src, "ancre replace introuvable"
src = src.replace(a5, a5 + '\n            .replace("%MODE_RATIO%", mode_ratio)', 1)

open(p, "w", encoding="utf-8").write(src)
print("Patch applique :", p)
