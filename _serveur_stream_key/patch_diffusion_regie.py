#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch_diffusion_regie.py — Met a jour l'onglet "Diffusion" de
cineflight_web/static/index.html pour gerer DEUX destinations :
  - la cle de diffusion YouTube  (secret)
  - l'URL Regie / distributeur    (URL complete)

Les deux sont stockees sur le compte (POST /api/stream_key) et recuperees
AUTOMATIQUEMENT par l'app Android (GET /api/stream_key) — aucun copier-coller
sur le telephone, quel que soit le mode choisi.

Idempotent + sauvegarde. Ce script :
  1) sauvegarde index.html -> index.html.bak_diffregie_<horodatage>
  2) REMPLACE la fonction cfRendreDiffusion() existante par la nouvelle
     (2 champs). Si l'ancienne fonction n'existe pas, l'insere avant
     cfRendreParrainage.

USAGE (sur le serveur) :
    python3 patch_diffusion_regie.py cineflight_web/static/index.html
"""

import sys, os, time, re

TOKEN_LS = "cineflight_token"  # nom de la cle localStorage du JWT (confirme: const CF_TOKEN_KEY='cineflight_token')

NOUVELLE_FONCTION = r"""// -- ONGLET DIFFUSION : cle YouTube + URL Regie stockees sur le compte, recuperees AUTO par l'app --
async function cfRendreDiffusion(){
  const host=document.getElementById('cfTabDiffusion');
  if(!host) return;
  host.innerHTML =
    '<div style="font-size:11px;text-transform:uppercase;letter-spacing:.08em;color:#8b949e;margin-bottom:8px">DIFFUSION EN DIRECT</div>'+
    '<p style="color:#9fb3c8;font-size:13px;margin:0 0 14px">Configure ici tes destinations. L\'application Android les recupere '+
      '<b>automatiquement</b> a l\'ouverture de l\'ecran Live &mdash; aucun copier-coller sur le telephone.</p>'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:4px 0 6px">1. YouTube &mdash; cle de diffusion</div>'+
    '<p style="color:#9fb3c8;font-size:12px;margin:0 0 8px">YouTube Studio &rarr; Parametres du flux &rarr; Cle de flux.</p>'+
    '<input id="cfDiffCle" type="password" autocomplete="off" placeholder="Cle de diffusion (xxxx-xxxx-xxxx-xxxx)" '+
      'style="width:100%;max-width:460px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:16px 0 6px">2. Regie / distributeur &mdash; URL complete</div>'+
    '<p style="color:#9fb3c8;font-size:12px;margin:0 0 8px">URL RTMP(S) fournie par ta regie (serveur + cle deja incluse, si applicable).</p>'+
    '<input id="cfDiffRegie" type="text" autocomplete="off" placeholder="rtmps://exemple-regie.tv/live/xxxxx" '+
      'style="width:100%;max-width:460px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+

    '<div id="cfDiffEtat" style="margin:14px 0 6px;color:#7fce7f;font-size:13px">Chargement&hellip;</div>'+
    '<div><button id="cfDiffSave" style="padding:9px 18px;border:0;border-radius:8px;background:#1565C0;color:#fff;cursor:pointer;font-size:14px">Enregistrer</button></div>'+
    '<p style="color:#e0a3a3;font-size:12px;margin-top:12px">&#9888; La cle de diffusion est un secret : ne la partage avec personne.</p>';

  const etat =document.getElementById('cfDiffEtat');
  const inpCle=document.getElementById('cfDiffCle');
  const inpReg=document.getElementById('cfDiffRegie');
  const btn  =document.getElementById('cfDiffSave');
  const H=()=>({'Authorization':'Bearer '+(localStorage.getItem('%TOKEN%')||''),'Content-Type':'application/json'});

  try{
    const r=await fetch('/api/stream_key',{headers:H()});
    if(r.status===200){
      const d=await r.json().catch(()=>({}));
      const parts=[];
      parts.push(d.stream_key ? 'cle YouTube ✓' : 'cle YouTube —');
      parts.push(d.regie_url  ? 'URL Regie ✓'  : 'URL Regie —');
      etat.textContent='Enregistre sur ton compte : '+parts.join('  ·  '); etat.style.color='#7fce7f';
      // On ne re-affiche jamais la cle en clair ; l'URL Regie n'est pas secrete -> on peut la pre-remplir.
      if(d.regie_url) inpReg.value=d.regie_url;
    }
    else if(r.status===404){ etat.textContent='Rien d\'enregistre pour l\'instant.'; etat.style.color='#caa'; }
    else if(r.status===401){ etat.textContent='Connecte-toi pour gerer tes destinations.'; etat.style.color='#e99'; }
    else { etat.textContent='Etat indisponible ('+r.status+').'; etat.style.color='#e99'; }
  }catch(e){ etat.textContent='Etat indisponible (reseau).'; etat.style.color='#e99'; }

  btn.onclick=async function(){
    const cle  =(inpCle.value||'').trim();
    const regie=(inpReg.value||'').trim();
    if(!cle && !regie){ etat.textContent='Saisis au moins une destination.'; etat.style.color='#e99'; return; }
    // On n'envoie stream_key que s'il est saisi (champ password vide = on ne touche pas la cle existante).
    const corps={};
    if(cle)   corps.stream_key=cle;
    corps.regie_url=regie;   // toujours envoye (vide = efface l'URL regie)
    btn.disabled=true;
    try{
      const r=await fetch('/api/stream_key',{method:'POST',headers:H(),body:JSON.stringify(corps)});
      if(r.ok){ etat.textContent='✓ Enregistre. L\'app recuperera tout automatiquement.'; etat.style.color='#7fce7f'; inpCle.value=''; }
      else { etat.textContent='Echec de l\'enregistrement ('+r.status+').'; etat.style.color='#e99'; }
    }catch(e){ etat.textContent='Echec (reseau).'; etat.style.color='#e99'; }
    finally{ btn.disabled=false; }
  };
}
""".replace("%TOKEN%", TOKEN_LS)


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 patch_diffusion_regie.py <chemin/vers/index.html>")
        sys.exit(1)
    path = sys.argv[1]
    if not os.path.exists(path):
        print("Fichier introuvable :", path); sys.exit(1)
    html = open(path, encoding="utf-8").read()
    original = html

    # Cas A : la fonction cfRendreDiffusion existe deja -> on la REMPLACE en entier.
    # On repere depuis le commentaire d'entete (ou "async function cfRendreDiffusion")
    # jusqu'a la fonction suivante cfRendreParrainage.
    m_deb = re.search(r"(?:// ?-+ ?ONGLET DIFFUSION.*?\n)?async function cfRendreDiffusion\s*\(", html)
    m_fin = re.search(r"async function cfRendreParrainage\s*\(", html)
    if m_deb and m_fin and m_deb.start() < m_fin.start():
        avant = html[:m_deb.start()]
        apres = html[m_fin.start():]
        html = avant + NOUVELLE_FONCTION + "\n" + apres
        print("OK  fonction cfRendreDiffusion REMPLACEE (2 champs : cle + URL Regie)")
    elif m_fin:
        # Cas B : pas de fonction existante -> on insere avant cfRendreParrainage.
        html = html[:m_fin.start()] + NOUVELLE_FONCTION + "\n" + html[m_fin.start():]
        print("OK  fonction cfRendreDiffusion INSEREE (2 champs)")
    else:
        print("!! Ancre 'async function cfRendreParrainage' introuvable. Fichier inchange.")
        return

    if html == original:
        print("Aucune modification (deja a jour ?). Fichier inchange.")
        return

    bak = path + ".bak_diffregie_" + time.strftime("%Y%m%d_%H%M%S")
    with open(bak, "w", encoding="utf-8") as f:
        f.write(original)
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    print("Sauvegarde :", bak)
    print("Modifie    :", path)
    print("Recharge Parametres > Diffusion : 2 champs (cle YouTube + URL Regie).")


if __name__ == "__main__":
    main()
