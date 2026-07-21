=====================================================================
  CINEFLIGHT — Endpoint /api/stream_key (clé de diffusion YouTube)
  À DÉPOSER SUR LE SERVEUR  161.35.188.68:8095  (backend cineflight.ca)
=====================================================================

OBJECTIF
  Permettre à l'utilisateur de saisir sa clé de diffusion YouTube UNE FOIS
  sur le web (Paramètres), et à l'app Android de la récupérer AUTOMATIQUEMENT
  (aucun copier-coller sur le téléphone).

CE QUI EST FOURNI
  - stream_key.py     : le module Flask (2 routes : GET et POST /api/stream_key)
  - snippet_parametres_web.html : le champ à coller dans la page Paramètres du site

CÔTÉ APP ANDROID (déjà fait)
  L'app appelle GET /api/stream_key avec l'en-tête  Authorization: Bearer <JWT>
  et attend une réponse JSON :
      { "stream_key": "xxxx-xxxx-xxxx-xxxx", "server_url": "rtmps://a.rtmps.youtube.com/live2" }
  (server_url est facultatif ; si absent, l'app garde son URL par défaut.)

INSTALLATION (3 étapes)
  1) Adapter stream_key.py à TON code réel :
       - la fonction _utilisateur_courant() : remplace-la par TA façon de lire
         l'utilisateur depuis le JWT (celle déjà utilisée par /api/missions).
       - le stockage : ici un fichier JSON par simplicité. Si tu as une base
         de données (users), stocke plutôt la clé dans une colonne du compte.
  2) Enregistrer le blueprint dans ton app Flask principale :
         from stream_key import bp_stream_key
         app.register_blueprint(bp_stream_key)
  3) Ajouter le champ "clé de diffusion" dans la page Paramètres web
     (voir snippet_parametres_web.html).

SÉCURITÉ
  - La clé est un SECRET : ne jamais la journaliser en clair.
  - Ne renvoie la clé QU'À l'utilisateur authentifié qui la possède (via le JWT).
  - Sers l'API en HTTPS si possible (la clé transite sur le réseau).
