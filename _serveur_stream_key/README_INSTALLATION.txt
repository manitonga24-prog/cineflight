=====================================================================
  CINEFLIGHT — Router FastAPI /api/stream_key (cle de diffusion YouTube)
  A DEPOSER SUR LE SERVEUR  cineflight.ca  (dossier cineflight_web/)
=====================================================================

TON SERVEUR : FastAPI + PyJWT. Auth = dependance get_current_user (cine_auth.py),
qui renvoie {"id", "username"}. Missions = APIRouter dans cine_missions_store.py.
Le module stream_key.py suit EXACTEMENT ce modele.

OBJECTIF
  L'utilisateur saisit sa cle YouTube UNE FOIS sur le web (Parametres).
  L'app Android la recupere AUTOMATIQUEMENT (GET /api/stream_key, Bearer JWT),
  a l'ouverture de l'ecran Live. Aucun copier-coller sur le telephone.

FICHIERS FOURNIS
  - stream_key.py                : le router FastAPI (GET + POST /api/stream_key)
  - snippet_parametres_web.html  : le champ a coller dans la page Parametres du site

INSTALLATION (3 etapes)
  1) Copier stream_key.py dans le dossier cineflight_web/ (a cote de cine_auth.py).
  2) Le monter dans app.py, la ou les autres routers sont inclus :
         from stream_key import router as stream_key_router
         app.include_router(stream_key_router)
     (si app.py importe avec prefixe paquet, utiliser
         from cineflight_web.stream_key import router as stream_key_router )
  3) Ajouter le champ "cle de diffusion" dans la page Parametres web
     (voir snippet_parametres_web.html — adapter la fonction jwt() a ta facon
      de recuperer le token cote navigateur, ex. localStorage 'cineflight_token').

A VERIFIER
  - L'import de get_current_user en haut de stream_key.py : deux variantes sont
    tentees automatiquement (cine_auth / cineflight_web.cine_auth). Ajuster au besoin.
  - get_current_user renvoie bien {"id", ...} : le module utilise user["id"] comme cle.
    Si ta dependance renvoie autre chose (ex. un objet), adapter _cle_utilisateur().

STOCKAGE
  - Par defaut : fichier stream_keys.json (cree a cote du module). Simple et suffisant.
  - MIEUX (si tu as une base users) : remplace _lire_cle/_ecrire_cle par une lecture/
    ecriture d'une colonne "stream_key" du compte. Je peux te le faire si tu me montres
    comment cine_missions_store.py stocke les missions.

SECURITE
  - La cle est un SECRET : jamais journalisee en clair, renvoyee au seul proprietaire
    (identifie par le JWT). Servir en HTTPS (deja le cas via cineflight.ca).
