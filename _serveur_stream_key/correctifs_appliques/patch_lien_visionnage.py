#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch_lien_visionnage.py — Onglet "Diffusion" a 3 destinations :
  1. Cle YouTube (secret)
  2. URL Regie / distributeur (URL complete)
  3. Lien de visionnage PUBLIC (a partager aux amis) + bouton COPIER

Le bouton Copier copie l'URL complete de la page CineFlight isolee par compte
(ex. https://cineflight.ca/live/christian), fournie par le serveur dans le champ
"live_page" de /api/stream_key. Les amis ouvrent cette page : le direct YouTube
y joue integre, aux couleurs CineFlight.

Idempotent + sauvegarde. REMPLACE la fonction cfRendreDiffusion existante.

USAGE (sur le serveur, depuis le dossier qui contient cineflight_web/) :
    python3 patch_lien_visionnage.py cineflight_web/static/preview3d.html
"""

import sys, os, time, re

TOKEN_LS = "cineflight_token"

FONCTION = r"""// -- ONGLET DIFFUSION : cle YouTube + URL Regie + lien de visionnage public --
async function cfRendreDiffusion(){
  const host=document.getElementById('cfTabDiffusion');
  if(!host) return;
  host.innerHTML =
    '<div style="font-size:11px;text-transform:uppercase;letter-spacing:.08em;color:#8b949e;margin-bottom:8px">DIFFUSION EN DIRECT</div>'+
    '<p style="color:#9fb3c8;font-size:13px;margin:0 0 14px">Configure tes destinations. L\'app Android recupere la cle et l\'URL '+
      '<b>automatiquement</b>. Le lien de visionnage sert a partager le direct a tes amis.</p>'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:4px 0 6px">1. YouTube &mdash; cle de diffusion</div>'+
    '<p style="color:#9fb3c8;font-size:12px;margin:0 0 8px">YouTube Studio &rarr; Parametres du flux &rarr; Cle de flux.</p>'+
    '<input id="cfDiffCle" type="password" autocomplete="off" placeholder="Cle de diffusion (xxxx-xxxx-xxxx-xxxx)" '+
      'style="width:100%;max-width:480px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:16px 0 6px">2. Regie / distributeur &mdash; URL complete</div>'+
    '<input id="cfDiffRegie" type="text" autocomplete="off" placeholder="rtmps://exemple-regie.tv/live/xxxxx" '+
      'style="width:100%;max-width:480px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:16px 0 6px">3. Lien de visionnage (a partager)</div>'+
    '<p style="color:#9fb3c8;font-size:12px;margin:0 0 8px">Colle le lien de ta video YouTube en direct (ex. https://youtu.be/XXXX ou l\'URL &laquo; watch &raquo;).</p>'+
    '<input id="cfDiffWatch" type="text" autocomplete="off" placeholder="https://youtu.be/XXXXXXXXXXX" '+
      'style="width:100%;max-width:480px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+
    '<div style="margin-top:8px;padding:9px 12px;background:#2a230d;border:0.5px solid #5a4a1c;border-radius:8px;color:#e8cf94;font-size:12px;max-width:480px">'+
      '&#9888; <b>A refaire avant chaque live :</b> l\'URL YouTube change a chaque nouvelle diffusion. '+
      'Recolle le nouveau lien dans ce champ 3 puis clique Enregistrer. C\'est le seul geste manuel. '+
      'Le lien de partage (cineflight.ca/live/...) ne change jamais.</div>'+

    '<div id="cfDiffEtat" style="margin:14px 0 6px;color:#7fce7f;font-size:13px">Chargement&hellip;</div>'+
    '<div><button id="cfDiffSave" style="padding:9px 18px;border:0;border-radius:8px;background:#1565C0;color:#fff;cursor:pointer;font-size:14px">Enregistrer</button></div>'+

    '<div id="cfDiffPartage" style="display:none;margin-top:18px;padding:12px 14px;background:#0d2136;border:0.5px solid #1c3a5e;border-radius:10px">'+
      '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin-bottom:6px">Page a partager a tes amis</div>'+
      '<div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">'+
        '<input id="cfDiffLien" type="text" readonly style="flex:1;min-width:220px;padding:9px 12px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:13px">'+
        '<button id="cfDiffCopier" style="padding:9px 16px;border:0;border-radius:8px;background:#2E7D32;color:#fff;cursor:pointer;font-size:13px">Copier</button>'+
        '<a id="cfDiffOuvrir" href="#" target="_blank" rel="noopener" style="padding:9px 16px;border-radius:8px;border:0.5px solid #1c3a5e;color:#4FC3F7;text-decoration:none;font-size:13px">Ouvrir</a>'+
      '</div>'+
      '<p style="color:#9fb3c8;font-size:12px;margin:8px 0 0">Ce lien ne change jamais : partage-le une seule fois. La video apparait automatiquement pendant le direct.</p>'+
    '</div>'+
    '<p style="color:#e0a3a3;font-size:12px;margin-top:12px">&#9888; La cle de diffusion est un secret : ne la partage avec personne.</p>';

  const etat =document.getElementById('cfDiffEtat');
  const inpCle=document.getElementById('cfDiffCle');
  const inpReg=document.getElementById('cfDiffRegie');
  const inpWat=document.getElementById('cfDiffWatch');
  const btn  =document.getElementById('cfDiffSave');
  const bloc =document.getElementById('cfDiffPartage');
  const lien =document.getElementById('cfDiffLien');
  const cop  =document.getElementById('cfDiffCopier');
  const ouv  =document.getElementById('cfDiffOuvrir');
  const H=()=>({'Authorization':'Bearer '+(localStorage.getItem('%TOKEN%')||''),'Content-Type':'application/json'});

  function montrerPartage(livePage){
    if(!livePage){ bloc.style.display='none'; return; }
    const url = location.origin + livePage;   // ex. https://cineflight.ca/live/christian
    lien.value = url; ouv.href = url; bloc.style.display='block';
  }

  try{
    const r=await fetch('/api/stream_key',{headers:H()});
    if(r.status===200){
      const d=await r.json().catch(()=>({}));
      const p=[]; p.push(d.stream_key?'cle YouTube ✓':'cle YouTube —');
      p.push(d.regie_url?'URL Regie ✓':'URL Regie —');
      p.push(d.watch_url?'lien visionnage ✓':'lien visionnage —');
      etat.textContent='Enregistre : '+p.join('  ·  '); etat.style.color='#7fce7f';
      if(d.regie_url) inpReg.value=d.regie_url;
      if(d.watch_url) inpWat.value=d.watch_url;
      montrerPartage(d.live_page);
    }
    else if(r.status===404){ etat.textContent='Rien d\'enregistre pour l\'instant.'; etat.style.color='#caa'; }
    else if(r.status===401){ etat.textContent='Connecte-toi pour gerer tes destinations.'; etat.style.color='#e99'; }
    else { etat.textContent='Etat indisponible ('+r.status+').'; etat.style.color='#e99'; }
  }catch(e){ etat.textContent='Etat indisponible (reseau).'; etat.style.color='#e99'; }

  btn.onclick=async function(){
    const cle=(inpCle.value||'').trim(), regie=(inpReg.value||'').trim(), watch=(inpWat.value||'').trim();
    if(!cle && !regie && !watch){ etat.textContent='Saisis au moins un champ.'; etat.style.color='#e99'; return; }
    const corps={ regie_url:regie, watch_url:watch };
    if(cle) corps.stream_key=cle;
    btn.disabled=true;
    try{
      const r=await fetch('/api/stream_key',{method:'POST',headers:H(),body:JSON.stringify(corps)});
      if(r.ok){ const d=await r.json().catch(()=>({}));
        etat.textContent='✓ Enregistre. L\'app recuperera tout automatiquement.'; etat.style.color='#7fce7f';
        inpCle.value=''; montrerPartage(d.live_page);
      } else { etat.textContent='Echec de l\'enregistrement ('+r.status+').'; etat.style.color='#e99'; }
    }catch(e){ etat.textContent='Echec (reseau).'; etat.style.color='#e99'; }
    finally{ btn.disabled=false; }
  };

  cop.onclick=async function(){
    try{ await navigator.clipboard.writeText(lien.value); cop.textContent='Copie ✓';
      setTimeout(()=>{cop.textContent='Copier';},1500);
    }catch(e){ lien.select(); document.execCommand('copy'); cop.textContent='Copie ✓';
      setTimeout(()=>{cop.textContent='Copier';},1500); }
  };
}
""".replace("%TOKEN%", TOKEN_LS)


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 patch_lien_visionnage.py <chemin/vers/preview3d.html>")
        sys.exit(1)
    path = sys.argv[1]
    if not os.path.exists(path):
        print("Fichier introuvable :", path); sys.exit(1)
    html = open(path, encoding="utf-8").read()
    original = html

    m_deb = re.search(r"(?:// ?-+ ?ONGLET DIFFUSION.*?\n)?async function cfRendreDiffusion\s*\(", html)
    m_fin = re.search(r"async function cfRendreParrainage\s*\(", html)
    if m_deb and m_fin and m_deb.start() < m_fin.start():
        html = html[:m_deb.start()] + FONCTION + "\n" + html[m_fin.start():]
        print("OK  cfRendreDiffusion REMPLACEE (3 champs + bouton Copier)")
    elif m_fin:
        html = html[:m_fin.start()] + FONCTION + "\n" + html[m_fin.start():]
        print("OK  cfRendreDiffusion INSEREE (3 champs + bouton Copier)")
    else:
        print("!! Ancre 'async function cfRendreParrainage' introuvable. Fichier inchange.")
        return

    if html == original:
        print("Aucune modification. Fichier inchange."); return

    bak = path + ".bak_lienwatch_" + time.strftime("%Y%m%d_%H%M%S")
    open(bak, "w", encoding="utf-8").write(original)
    open(path, "w", encoding="utf-8").write(html)
    print("Sauvegarde :", bak)
    print("Modifie    :", path)


if __name__ == "__main__":
    main()
