# -*- coding: utf-8 -*-
# Diagnostic : compare le KMZ TOP-DOWN et le KMZ SOUVENIR generes par le serveur.
# Lance:  python diag_topdown.py
import json, io, zipfile, re, urllib.request

BASE = "http://161.35.188.68:8095"
LAT, LON = 45.5017, -73.5673   # point de test (peu importe le lieu pour la structure)

def post(path, body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(BASE+path, data=data,
        headers={"Content-Type":"application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        body = e.read()
        print("   HTTP %d sur %s -> %s" % (e.code, path, body[:400]))
        return e.code, body

def kmz_from_plan(plan_id):
    st, raw = post("/api/kmz", {"plan_id": plan_id})
    return st, raw

def analyse(nom, raw):
    print("\n===== %s =====" % nom)
    print("taille KMZ:", len(raw), "octets")
    try:
        z = zipfile.ZipFile(io.BytesIO(raw))
    except Exception as e:
        print("  PAS un zip valide:", e); print("  debut:", raw[:200]); return
    print("  entrees:", z.namelist())
    for name in z.namelist():
        if name.endswith("waylines.wpml"):
            xml = z.read(name).decode("utf-8", "replace")
            coords = re.findall(r"<coordinates>\s*([-0-9.]+),([-0-9.]+)", xml)
            print("  [%s] nb <coordinates>=%d" % (name, len(coords)))
            if coords:
                import math
                lons = [float(a) for a,b in coords]
                lats = [float(b) for a,b in coords]
                clat = sum(lats)/len(lats); clon = sum(lons)/len(lons)
                # rayon max en metres depuis le centre
                rmax = 0.0
                for la,lo in zip(lats,lons):
                    dx = (lo-clon)*111320*math.cos(math.radians(clat))
                    dy = (la-clat)*111320
                    rmax = max(rmax, math.hypot(dx,dy))
                print("     rayon (centre->point le plus loin): %.1f m  (diametre ~%.1f m)" % (rmax, 2*rmax))
                # hauteurs de vol (executeHeight / height)
                hs = re.findall(r"executeHeight>\s*([-0-9.]+)", xml) or re.findall(r"height>\s*([-0-9.]+)", xml)
                hs = [float(h) for h in hs]
                if hs:
                    print("     hauteurs de vol: min=%.1f m  max=%.1f m  (%d valeurs)" % (min(hs), max(hs), len(hs)))
                    print("     profil hauteurs (ordre):", [round(h) for h in hs])
                print("     3 premiers (lon,lat):", coords[:3])

# 1) TOP-DOWN : on teste plusieurs variantes
for varnt in ["simple", "orbite_haute", "spirale"]:
    print("\nGeneration TOP-DOWN variante=%s ..." % varnt)
    st, raw = post("/api/top_down", {"lat":LAT,"lon":LON,"contexte":"objet_lieu",
        "variante":varnt,"descendre":True,"consentement":True})
    print("  /api/top_down HTTP", st)
    try: pid = json.loads(raw).get("plan_id","")
    except Exception: pid = ""
    print("  plan_id:", pid)
    if pid:
        st, kmz = kmz_from_plan(pid)
        print("  /api/kmz HTTP", st)
        analyse("TOP-DOWN (%s)" % varnt, kmz)

# 2) SOUVENIR : teste les 3 proximites (adultes = couple)
for prox in ["proche","equilibre","large"]:
    print("\nGeneration SOUVENIR orbite_360 proximite=%s ..." % prox)
    st, raw = post("/api/souvenir", {"lat":LAT,"lon":LON,"depart_lat":LAT+0.00007,"depart_lon":LON,
        "groupe":"couple","plan":"orbite_360","orientation":"auto","pilote_dans_scene":True,"proximite":prox})
    print("  /api/souvenir HTTP", st)
    try: pid2 = json.loads(raw).get("plan_id","")
    except Exception: pid2=""
    print("  plan_id:", pid2)
    if pid2:
        st, kmz2 = kmz_from_plan(pid2)
        print("  /api/kmz HTTP", st)
        analyse("SOUVENIR orbite_360 (%s)" % prox, kmz2)
