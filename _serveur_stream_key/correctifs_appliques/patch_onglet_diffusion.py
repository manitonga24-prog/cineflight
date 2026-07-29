#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch_onglet_diffusion.py — Ajoute l'onglet "Diffusion" (cle YouTube) dans
cineflight_web/static/index.html, de facon idempotente et avec sauvegarde.

USAGE (sur le serveur, depuis le dossier qui contient cineflight_web/) :
    python3 patch_onglet_diffusion.py cineflight_web/static/index.html

Effet :
    1) sauvegarde  index.html  ->  index.html.bak_diffusion_<horodatage>
    2) ajoute l'onglet .cfTab "Diffusion" (apres l'onglet Preferences)
    3) ajoute le panneau <div id="cfTabDiffusion">
    4) ajoute l'appel cfRendreDiffusion() dans cfSwitchTab
    5) ajoute la fonction cfRendreDiffusion() (avant cfRendreParrainage)

Si le patch est deja applique, il ne fait rien (idempotent).
Ne change RIEN d'autre dans le fichier.
"""

import sys, os, time, re

TOKEN_LS = "cineflight_token"  # <-- nom de la cle localStorage du JWT (a ajuster si besoin)

FONCTION = r"""
// ── ONGLET DIFFUSION : cle YouTube stockee sur le compte, recuperee AUTO par l'app ──
async function cfRendreDiffusion(){
  const host=document.getElementById('cfTabDiffusion');
  if(!host) return;
  host.innerHTML =
    '<div style="font-size:11px;text-transform:uppercase;letter-spacing:.08em;color:#8b949e;margin-bottom:8px">DIFFUSION EN DIRECT (YOUTUBE)</div>'+
    '<p style="color:#9fb3c8;font-size:13px;margin:0 0 12px">Colle ta <b>cle de diffusion</b> YouTube (YouTube Studio &rarr; Parametres du flux &rarr; Cle de flux). '+
    'Elle sera recuperee <b>automatiquement</b> par l\'application Android &mdash; aucun copier-coller a faire sur le telephone.</p>'+
    '<div id="cfDiffEtat" style="margin:8px 0;color:#7fce7f;font-size:13px">Chargement&hellip;</div>'+
    '<input id="cfDiffCle" type="password" autocomplete="off" placeholder="Cle de diffusion (xxxx-xxxx-xxxx-xxxx)" '+
      'style="width:100%;max-width:420px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+
    '<div style="margin-top:10px"><button id="cfDiffSave" style="padding:9px 18px;border:0;border-radius:8px;background:#1565C0;color:#fff;cursor:pointer;font-size:14px">Enregistrer la cle</button></div>'+
    '<p style="color:#e0a3a3;font-size:12px;margin-top:10px">&#9888; La cle de diffusion est un secret : ne la partage avec personne.</p>';
  const etat=document.getElementById('cfDiffEtat');
  const inp =document.getElementById('cfDiffCle');
  const btn =document.getElementById('cfDiffSave');
  const H=()=>({'Authorization':'Bearer '+(localStorage.getItem('%TOKEN%')||''),'Content-Type':'application/json'});
  try{
    const r=await fetch('/api/stream_key',{headers:H()});
    if(r.status===200){ etat.textContent='✓ Une cle est enregistree sur ton compte.'; etat.style.color='#7fce7f'; }
    else if(r.status===404){ etat.textContent='Aucune cle enregistree pour l\'instant.'; etat.style.color='#caa'; }
    else if(r.status===401){ etat.textContent='Connecte-toi pour gerer ta cle.'; etat.style.color='#e99'; }
    else { etat.textContent='Etat indisponible ('+r.status+').'; etat.style.color='#e99'; }
  }catch(e){ etat.textContent='Etat indisponible (reseau).'; etat.style.color='#e99'; }
  btn.onclick=async function(){
    const cle=(inp.value||'').trim();
    if(!cle){ etat.textContent='Saisis d\'abord une cle.'; etat.style.color='#e99'; return; }
    btn.disabled=true;
    try{
      const r=await fetch('/api/stream_key',{method:'POST',headers:H(),body:JSON.stringify({stream_key:cle})});
      if(r.ok){ etat.textContent='✓ Cle enregistree. L\'app la recuperera automatiquement.'; etat.style.color='#7fce7f'; inp.value=''; }
      else { etat.textContent='Echec de l\'enregistrement ('+r.status+').'; etat.style.color='#e99'; }
    }catch(e){ etat.textContent='Echec (reseau).'; etat.style.color='#e99'; }
    finally{ btn.disabled=false; }
  };
}

""".replace("%TOKEN%", TOKEN_LS)


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 patch_onglet_diffusion.py <chemin/vers/index.html>")
        sys.exit(1)
    path = sys.argv[1]
    if not os.path.exists(path):
        print("Fichier introuvable :", path); sys.exit(1)
    html = open(path, encoding="utf-8").read()

    if "cfTabDiffusion" in html or "cfRendreDiffusion" in html:
        print("Deja patche (rien a faire).")
        return

    modifs = 0

    # 1) Onglet : apres l'onglet 'preferences'
    m = re.search(r'(<div class="cfTab"[^>]*data-tab="preferences"[^>]*>.*?</div>)', html)
    if m:
        onglet = '\n        <div class="cfTab"            data-tab="diffusion"  onclick="cfSwitchTab(\'diffusion\')">Diffusion</div>'
        html = html[:m.end()] + onglet + html[m.end():]
        modifs += 1
        print("OK 1/4 onglet ajoute")
    else:
        print("!! onglet 'preferences' introuvable")

    # 2) Panneau : a cote de #cfTabParrainage
    m = re.search(r'(<div id="cfTabParrainage"[^>]*></div>)', html)
    if m:
        panneau = '\n        <div id="cfTabDiffusion" style="display:none"></div>'
        html = html[:m.end()] + panneau + html[m.end():]
        modifs += 1
        print("OK 2/4 panneau ajoute")
    else:
        print("!! #cfTabParrainage introuvable")

    # 3) Rendu dans cfSwitchTab : apres la ligne preferences
    m = re.search(r"(if\(tab==='preferences'\)\s*cfRendrePreferences\(\);)", html)
    if m:
        html = html[:m.end()] + "\n  if(tab==='diffusion') cfRendreDiffusion();" + html[m.end():]
        modifs += 1
        print("OK 3/4 appel cfSwitchTab ajoute")
    else:
        print("!! ligne cfRendrePreferences() introuvable")

    # 4) Fonction : juste avant 'async function cfRendreParrainage'
    m = re.search(r"(async function cfRendreParrainage\s*\()", html)
    if m:
        html = html[:m.start()] + FONCTION + html[m.start():]
        modifs += 1
        print("OK 4/4 fonction cfRendreDiffusion ajoutee")
    else:
        print("!! 'async function cfRendreParrainage' introuvable")

    if modifs == 0:
        print("Aucune modification appliquee (ancres introuvables). Fichier inchange.")
        return

    bak = path + ".bak_diffusion_" + time.strftime("%Y%m%d_%H%M%S")
    with open(bak, "w", encoding="utf-8") as f:
        f.write(open(path, encoding="utf-8").read())
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    print("Sauvegarde :", bak)
    print("Modifie    :", path, "(%d/4 insertions)" % modifs)
    print("Recharge la page Parametres : un onglet 'Diffusion' doit apparaitre.")


if __name__ == "__main__":
    main()
