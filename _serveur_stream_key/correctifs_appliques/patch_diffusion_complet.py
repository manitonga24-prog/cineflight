#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch_diffusion_complet.py — Onglet Diffusion complet :
  1. Cle YouTube (secret)
  2. URL Regie (URL complete)
  3. Lien de visionnage (a partager) + bloc Copier/Ouvrir
  4. Heure du direct (optionnel)  -> compte a rebours sur la page d'attente
  5. Titre du live (optionnel)
  6. Description du live (optionnel)
  7. Image d'attente (JPEG/PNG, optionnel) -> upload + apercu

Idempotent + sauvegarde. REMPLACE la fonction cfRendreDiffusion.
USAGE : python3 patch_diffusion_complet.py cineflight_web/static/preview3d.html
"""
import sys, os, time, re

TOKEN_LS = "cineflight_token"

def champ_input(id_, label, ph, typ="text"):
    return ("'<div style=\"font-size:12px;font-weight:600;color:#cfe0f2;margin:14px 0 6px\">"+label+"</div>'+"
            "'<input id=\""+id_+"\" type=\""+typ+"\" autocomplete=\"off\" placeholder=\""+ph+"\" "
            "style=\"width:100%;max-width:480px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box\">'+")

FONCTION = r"""// -- ONGLET DIFFUSION COMPLET : destinations + lien partage + heure/titre/desc + image --
async function cfRendreDiffusion(){
  const host=document.getElementById('cfTabDiffusion');
  if(!host) return;
  host.innerHTML =
    '<div style="font-size:11px;text-transform:uppercase;letter-spacing:.08em;color:#8b949e;margin-bottom:8px">DIFFUSION EN DIRECT</div>'+
    '<p style="color:#9fb3c8;font-size:13px;margin:0 0 14px">L\'app recupere la cle et l\'URL automatiquement. Le lien de visionnage, l\'heure, le titre, la description et l\'image s\'affichent sur la page partagee a tes amis.</p>'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:4px 0 6px">1. YouTube &mdash; cle de diffusion</div>'+
    '<input id="cfDiffCle" type="password" autocomplete="off" placeholder="Cle de diffusion (xxxx-xxxx-xxxx-xxxx)" style="width:100%;max-width:480px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+
    '<div style="margin-top:8px;padding:9px 12px;background:#2a230d;border:0.5px solid #5a4a1c;border-radius:8px;color:#e8cf94;font-size:12px;max-width:480px">&#9888; <b>A refaire avant chaque live :</b> recolle le nouveau lien de visionnage (champ 3) puis Enregistrer. Le lien de partage ne change jamais.</div>'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:16px 0 6px">2. Regie / distributeur &mdash; URL complete</div>'+
    '<input id="cfDiffRegie" type="text" autocomplete="off" placeholder="rtmps://exemple-regie.tv/live/xxxxx" style="width:100%;max-width:480px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:16px 0 6px">3. Lien de visionnage (a partager)</div>'+
    '<input id="cfDiffWatch" type="text" autocomplete="off" placeholder="https://youtu.be/XXXXXXXXXXX" style="width:100%;max-width:480px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+

    '<div style="border-top:0.5px solid #1c3a5e;margin:18px 0 4px"></div>'+
    '<div style="font-size:11px;text-transform:uppercase;color:#8b949e;margin:8px 0 4px">Infos affichees sur la page d\'attente (optionnel)</div>'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:12px 0 6px">Heure du direct</div>'+
    '<input id="cfDiffTime" type="text" autocomplete="off" placeholder="2026-07-25 19:00" style="width:100%;max-width:480px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+
    '<p style="color:#9fb3c8;font-size:11px;margin:4px 0 0">Format AAAA-MM-JJ HH:MM &mdash; declenche un compte a rebours anime.</p>'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:14px 0 6px">Titre du live</div>'+
    '<input id="cfDiffTitle" type="text" autocomplete="off" placeholder="Ex. Match de soccer U15" style="width:100%;max-width:480px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box">'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:14px 0 6px">Description</div>'+
    '<textarea id="cfDiffDesc" rows="3" placeholder="Quelques mots sur la diffusion..." style="width:100%;max-width:480px;padding:10px 13px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:14px;box-sizing:border-box;resize:vertical"></textarea>'+

    '<div id="cfDiffEtat" style="margin:14px 0 6px;color:#7fce7f;font-size:13px">Chargement&hellip;</div>'+
    '<div><button id="cfDiffSave" style="padding:9px 18px;border:0;border-radius:8px;background:#1565C0;color:#fff;cursor:pointer;font-size:14px">Enregistrer</button></div>'+

    '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin:18px 0 6px">Image d\'attente (JPEG/PNG, max 5 Mo)</div>'+
    '<div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">'+
      '<input id="cfDiffImg" type="file" accept="image/jpeg,image/png" style="color:#cfe0f2;font-size:13px">'+
      '<button id="cfDiffImgUp" style="padding:8px 14px;border:0;border-radius:8px;background:#2E7D32;color:#fff;cursor:pointer;font-size:13px">Televerser</button>'+
      '<button id="cfDiffImgDel" style="padding:8px 14px;border:0.5px solid #5a2a2a;border-radius:8px;background:transparent;color:#e0857a;cursor:pointer;font-size:13px">Retirer</button>'+
    '</div>'+
    '<img id="cfDiffImgPrev" alt="" style="display:none;margin-top:10px;max-width:260px;max-height:150px;border-radius:8px;border:0.5px solid #1c3a5e">'+

    '<div id="cfDiffPartage" style="display:none;margin-top:20px;padding:12px 14px;background:#0d2136;border:0.5px solid #1c3a5e;border-radius:10px">'+
      '<div style="font-size:12px;font-weight:600;color:#cfe0f2;margin-bottom:6px">Page a partager a tes amis</div>'+
      '<div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">'+
        '<input id="cfDiffLien" type="text" readonly style="flex:1;min-width:220px;padding:9px 12px;background:#0F2A4D;border:0.5px solid #1c3a5e;border-radius:8px;color:#eaf2fb;font-size:13px">'+
        '<button id="cfDiffCopier" style="padding:9px 16px;border:0;border-radius:8px;background:#2E7D32;color:#fff;cursor:pointer;font-size:13px">Copier</button>'+
        '<a id="cfDiffOuvrir" href="#" target="_blank" rel="noopener" style="padding:9px 16px;border-radius:8px;border:0.5px solid #1c3a5e;color:#4FC3F7;text-decoration:none;font-size:13px">Ouvrir</a>'+
      '</div>'+
    '</div>'+
    '<p style="color:#e0a3a3;font-size:12px;margin-top:12px">&#9888; La cle de diffusion est un secret : ne la partage avec personne.</p>';

  const g=function(id){return document.getElementById(id);};
  const etat=g('cfDiffEtat'), inpCle=g('cfDiffCle'), inpReg=g('cfDiffRegie'), inpWat=g('cfDiffWatch');
  const inpTime=g('cfDiffTime'), inpTitle=g('cfDiffTitle'), inpDesc=g('cfDiffDesc');
  const btn=g('cfDiffSave'), bloc=g('cfDiffPartage'), lien=g('cfDiffLien'), cop=g('cfDiffCopier'), ouv=g('cfDiffOuvrir');
  const fImg=g('cfDiffImg'), bUp=g('cfDiffImgUp'), bDel=g('cfDiffImgDel'), prev=g('cfDiffImgPrev');
  const T=()=>(localStorage.getItem('%TOKEN%')||'');
  const H=()=>({'Authorization':'Bearer '+T(),'Content-Type':'application/json'});
  let liveUser='';
  function montrerPartage(livePage){
    if(!livePage){ bloc.style.display='none'; return; }
    liveUser=livePage.split('/').pop();
    const url=location.origin+livePage;
    lien.value=url; ouv.href=url; bloc.style.display='block';
    prev.src='/live_image/'+liveUser+'?t='+Date.now();
    prev.onload=function(){ prev.style.display='block'; };
    prev.onerror=function(){ prev.style.display='none'; };
  }
  try{
    const r=await fetch('/api/stream_key',{headers:H()});
    if(r.status===200){
      const d=await r.json().catch(()=>({}));
      const p=[]; p.push(d.stream_key?'cle ✓':'cle —'); p.push(d.regie_url?'regie ✓':'regie —');
      p.push(d.watch_url?'lien ✓':'lien —');
      etat.textContent='Enregistre : '+p.join('  ·  '); etat.style.color='#7fce7f';
      if(d.regie_url) inpReg.value=d.regie_url;
      if(d.watch_url) inpWat.value=d.watch_url;
      if(d.live_time) inpTime.value=d.live_time;
      if(d.live_title) inpTitle.value=d.live_title;
      if(d.live_desc) inpDesc.value=d.live_desc;
      montrerPartage(d.live_page);
    }
    else if(r.status===404){ etat.textContent='Rien d\'enregistre pour l\'instant.'; etat.style.color='#caa'; }
    else if(r.status===401){ etat.textContent='Connecte-toi pour gerer tes destinations.'; etat.style.color='#e99'; }
    else { etat.textContent='Etat indisponible ('+r.status+').'; etat.style.color='#e99'; }
  }catch(e){ etat.textContent='Etat indisponible (reseau).'; etat.style.color='#e99'; }

  btn.onclick=async function(){
    const corps={ regie_url:(inpReg.value||'').trim(), watch_url:(inpWat.value||'').trim(),
      live_time:(inpTime.value||'').trim(), live_title:(inpTitle.value||'').trim(), live_desc:(inpDesc.value||'').trim() };
    const cle=(inpCle.value||'').trim(); if(cle) corps.stream_key=cle;
    btn.disabled=true;
    try{
      const r=await fetch('/api/stream_key',{method:'POST',headers:H(),body:JSON.stringify(corps)});
      if(r.ok){ const d=await r.json().catch(()=>({}));
        etat.textContent='✓ Enregistre. L\'app recuperera tout automatiquement.'; etat.style.color='#7fce7f';
        inpCle.value=''; montrerPartage(d.live_page);
      } else { etat.textContent='Echec ('+r.status+').'; etat.style.color='#e99'; }
    }catch(e){ etat.textContent='Echec (reseau).'; etat.style.color='#e99'; }
    finally{ btn.disabled=false; }
  };

  bUp.onclick=async function(){
    if(!fImg.files || !fImg.files[0]){ etat.textContent='Choisis d\'abord une image.'; etat.style.color='#e99'; return; }
    const fd=new FormData(); fd.append('fichier', fImg.files[0]);
    bUp.disabled=true; etat.textContent='Televersement...'; etat.style.color='#9fb3c8';
    try{
      const r=await fetch('/api/live_image',{method:'POST',headers:{'Authorization':'Bearer '+T()},body:fd});
      if(r.ok){ etat.textContent='✓ Image enregistree.'; etat.style.color='#7fce7f';
        if(liveUser){ prev.src='/live_image/'+liveUser+'?t='+Date.now(); prev.style.display='block'; } }
      else { const e=await r.json().catch(()=>({})); etat.textContent='Echec image : '+(e.detail||r.status); etat.style.color='#e99'; }
    }catch(e){ etat.textContent='Echec image (reseau).'; etat.style.color='#e99'; }
    finally{ bUp.disabled=false; }
  };

  bDel.onclick=async function(){
    bDel.disabled=true;
    try{
      const r=await fetch('/api/live_image',{method:'DELETE',headers:{'Authorization':'Bearer '+T()}});
      if(r.ok){ etat.textContent='Image retiree.'; etat.style.color='#7fce7f'; prev.style.display='none'; prev.removeAttribute('src'); }
    }catch(e){}
    finally{ bDel.disabled=false; }
  };

  cop.onclick=async function(){
    try{ await navigator.clipboard.writeText(lien.value); cop.textContent='Copie ✓'; setTimeout(()=>{cop.textContent='Copier';},1500); }
    catch(e){ lien.select(); document.execCommand('copy'); cop.textContent='Copie ✓'; setTimeout(()=>{cop.textContent='Copier';},1500); }
  };
}
""".replace("%TOKEN%", TOKEN_LS)


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 patch_diffusion_complet.py <chemin/preview3d.html>"); sys.exit(1)
    path = sys.argv[1]
    if not os.path.exists(path):
        print("Fichier introuvable :", path); sys.exit(1)
    html = open(path, encoding="utf-8").read()
    original = html
    m_deb = re.search(r"(?:// ?-+ ?ONGLET DIFFUSION.*?\n)?async function cfRendreDiffusion\s*\(", html)
    m_fin = re.search(r"async function cfRendreParrainage\s*\(", html)
    if m_deb and m_fin and m_deb.start() < m_fin.start():
        html = html[:m_deb.start()] + FONCTION + "\n" + html[m_fin.start():]
        print("OK  cfRendreDiffusion REMPLACEE (champs complets + image)")
    elif m_fin:
        html = html[:m_fin.start()] + FONCTION + "\n" + html[m_fin.start():]
        print("OK  cfRendreDiffusion INSEREE")
    else:
        print("!! Ancre cfRendreParrainage introuvable. Fichier inchange."); return
    if html == original:
        print("Aucune modification."); return
    bak = path + ".bak_diffcomplet_" + time.strftime("%Y%m%d_%H%M%S")
    open(bak, "w", encoding="utf-8").write(original)
    open(path, "w", encoding="utf-8").write(html)
    print("Sauvegarde :", bak); print("Modifie    :", path)


if __name__ == "__main__":
    main()
