# Commandes

Toutes les commandes ouvrent un écran ; chaque action de l'écran est revérifiée par le serveur (permissions, soldes, montants).

| Commande | Qui | Effet |
|---|---|---|
| `/ceconomy` | tous | Menu principal (aussi touche **K**) |
| `/ceconomy bank` | tous | Compte personnel : dépôt, retrait, virement, historique |
| `/ceconomy company` | tous | Mes entreprises (employé, gérant ou propriétaire) |
| `/ceconomy create` | tous | Demande de création d'entreprise (statut « En attente ») |
| `/ceconomy announcements` | tous | Annonces publiques par catégorie |
| `/ceconomy treasury` | admin | Trésorerie royale : solde, entrées/sorties, dépenses royales |
| `/ceconomy admin` | admin | Menu d'administration (16 sections) |
| `/ceconomy help` | tous | Rappel des commandes |

Les commandes `treasury` et `admin` n'apparaissent qu'aux administrateurs ; un non-administrateur qui forgerait la requête réseau est refusé par le serveur (testé).
