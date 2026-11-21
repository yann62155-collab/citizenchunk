# Citizen Chunk (`citizenchunk`)

Pré-génération des chunks du serveur Citizen, réservée aux admins (OP niveau 2).
Une fois les chunks générés, ils sont sur le disque : quand les joueurs explorent, le serveur n'a plus qu'à les lire au lieu de les créer, donc beaucoup moins de lag.

- Minecraft 1.21.1, NeoForge 21.1.215, Java 21
- **Mod 100 % serveur** : à mettre uniquement dans le dossier `mods/` du serveur BoxToPlay. Les joueurs (PC et console via Geyser) n'ont rien à installer.

## Commandes (`/citizenchunk` ou `/cchunk`)

| Commande | Effet |
|---|---|
| `/cchunk lancer <rayon>` | Génère un carré autour du spawn (rayon en blocs, ex. `5000`) |
| `/cchunk lancer <rayon> <monde> <x> <z>` | Choisit le monde (`minecraft:overworld`, `minecraft:the_nether`…) et le centre |
| `/cchunk lancer bordure [monde]` | Génère tout l'intérieur de la bordure (`/worldborder`) |
| `/cchunk pause` | Met en pause, la progression est gardée |
| `/cchunk reprendre` | Reprend là où c'était |
| `/cchunk arreter` puis `/cchunk arreter confirmer` | Arrête et efface la progression (les chunks déjà faits restent) |
| `/cchunk etat` | Pourcentage, chunks faits/total, vitesse, temps écoulé, **temps restant et heure de fin** |
| `/cchunk suivre` | Affiche/masque la barre de progression en haut de l'écran |
| `/cchunk vitesse <1-64>` | Chunks générés en parallèle (défaut 12) |

## Rester connecté pendant la génération

- L'admin qui lance la génération voit une **barre en haut de l'écran** : `Citizen Chunk — 42,3 % — 12 345/29 241 — reste ~1 h 05 min`. Elle revient à chaque reconnexion.
- Les admins connectés reçoivent un message tous les 10 % (réglable).
- Le mod mesure le temps de chaque tick : si le serveur dépasse 40 ms/tick, il ralentit tout seul, puis réaccélère quand ça va mieux. On peut donc jouer pendant la génération.
- La progression est sauvegardée toutes les 30 s et à l'arrêt du serveur ; après un redémarrage, la génération **reprend toute seule**.

## Ordre de génération

Spirale qui part du centre : les zones autour du spawn sont prêtes en premier.

## Config — `config/citizenchunk.json`

```json
{
  "vitesseMax": 12,
  "msptCible": 40,
  "repriseAutomatique": true,
  "annonceTousLesPourcents": 10,
  "rayonMaxBlocs": 50000
}
```

## Fichiers

- État de la génération : `world/citizenchunk/etat.json`
- Logs : lignes `[CitizenChunk]` dans `logs/latest.log`

## Ordre de grandeur

Un rayon de 5 000 blocs = 393 129 chunks (plusieurs Go sur le disque pour l'Overworld : vérifie l'espace disponible chez BoxToPlay). La durée dépend du processeur du serveur : regarde `/cchunk etat` au bout d'une minute pour l'estimation réelle.
