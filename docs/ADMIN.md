# Documentation administrateur

## Monnaie
PO, plus petite unité 1. Pièces : bronze 1, argent 10, or 100, platine 1000. Au dépôt, le mod retire les pièces nécessaires (petites d'abord) et **rend la monnaie en plus grosses pièces**. Au retrait, il donne le montant en plus grosses pièces ; si l'inventaire est plein, le reste tombe au sol.

## Menu d'administration (`/ceconomy admin`)
Tableau de bord, puis :
1. **Entreprises** : liste, statut, caisse. 2. **Demandes** : informations sur le demandeur, accepter, refuser, demander des modifications (note de l'admin). 3. **Joueurs** : soldes. 4-6. **Propriétaires / Gérants / Employés** : transfert de propriété, retrait d'un gérant, licenciement. 7. **Taxes**. 8. **Trésorerie**. 9. **Historique bancaire**. 10. **Historique fiscal**. 11. **Annonces** (retrait, suspension de la publication d'une entreprise). 12-13. **Suspension / Fermeture** d'entreprise (motif obligatoire ; la fermeture est irréversible). 14. **Journal administratif**. 15. **Paramètres** (fuseau horaire, sauvegardes, vérification des soldes). 16. **Limites d'annonces**.

Chaque action sensible demande un motif et est écrite dans le **journal administratif**.

## Taxes
- Trois taxes : **entreprise** (base chiffre d'affaires ou bénéfice), **revenus personnels** (types de revenus choisis par l'admin), **salaires** (optionnelle).
- Taux en pourcentage (ex : `12,5`), stocké en points de base. Activation/désactivation, mode **Déclaration** (le contribuable paie depuis son écran Impôts) ou **Automatique**, exonérations par joueur ou entreprise.
- Fréquence par défaut : hebdomadaire, clôture le **dimanche 00:00** (fuseau réglable, Europe/Paris recommandé). Les modifications **s'appliquent à la période suivante, jamais rétroactivement**, et ne changent pas les déclarations déjà émises.
- Pas de double imposition : seuls les salaires et les paiements de factures comptent comme revenus imposables ; les virements entre comptes ne sont jamais taxés.
- L'argent des taxes arrive dans le **trésor royal** (« l'entreprise du royaume »).

## Trésor royal
Solde, entrées/sorties sur 30 jours par catégorie, historique. Dépenses royales : vers un joueur, vers une entreprise (`@NomEntreprise`) ou en pièces ; **motif obligatoire**, consigné au journal. Les revenus administratifs (amendes…) se versent en pièces.

## Paie et planificateur
Toutes les minutes : clôture fiscale, paie (une fois par période, identifiants déterministes → impossible de payer deux fois), factures en retard, sauvegarde si l'intervalle est écoulé. Au tout premier lancement la paie et les taxes s'alignent sur la période courante **sans rattrapage rétroactif**.

## Sauvegarde et restauration
Sauvegardes dans `<monde>/citizeneconomy/backups/` (copie cohérente SQLite). Pour restaurer : arrêter le serveur, remplacer `citizeneconomy.db` par une sauvegarde, redémarrer. Le mod refuse de démarrer sur une base plus récente que lui. Menu Paramètres → « Vérifier les soldes » compare chaque solde au grand livre.

## Limites connues
- Un crash du serveur **exactement** entre le débit d'un retrait et la remise des pièces peut faire perdre les pièces (le débit est fait en premier pour empêcher toute duplication). Les retraits annulés (joueur déconnecté) sont compensés.
- Les destinataires de virements doivent s'être connectés au moins une fois.

## Lien avec le mod Hibou Postal (facultatif)
Si **Hibou Postal** (`hiboupostal`) est installé sur le serveur, les factures à un joueur se font avec son **numéro de hibou** (ex : `0007`, `7`, `#7` ou `n°7`) et non avec son pseudo ; le numéro s'affiche aussi sur les factures émises. Les factures vers une entreprise restent par nom. Le joueur doit s'être connecté au moins une fois depuis l'installation de Citizen Economy. Sans Hibou Postal, le pseudo reste utilisé. Le numéro est aussi accepté à la place du pseudo pour les virements et les recrutements.

## Annonces dans le chat
Chaque **annonce publique** publiée par une entreprise est affichée dans le chat de tous les joueurs connectés : `[Annonce] NomDeLEntreprise — Catégorie : Titre` (cliquable : ouvre le panneau des annonces). Les annonces **internes** ne vont qu'aux membres de l'entreprise, par notification. Pour couper l'affichage général : `serverconfig/citizeneconomy-server.toml`, `publicAnnouncementsInChat = false`. Les limites anti-spam (annonces par heure, délai) s'appliquent toujours.

## Matricule des entreprises

Chaque entreprise possède un matricule unique au format `E-0001`, affiché sur sa fiche, dans « Mes entreprises » et dans la liste admin des entreprises.
- Les entreprises déjà existantes reçoivent automatiquement leur matricule au premier démarrage avec cette version (migration V3, sauvegarde automatique avant migration).
- Partout où l'on désigne une entreprise (payer `@E-0003`, facturer une entreprise, exonération d'impôt), on peut saisir le **nom**, le **matricule** (`E-0003`) ou simplement le numéro (`3`).
- Le matricule ne change jamais, même si l'entreprise est suspendue ou fermée.

## Annonces à l'écran (HUD)

Chaque annonce **publique** s'affiche pendant 15 secondes à gauche de l'écran de tous les joueurs connectés : nom de l'entreprise en tête, puis le titre, puis le texte complet. Si plusieurs annonces arrivent, elles se suivent (5 en attente au maximum). Désactivable dans la config serveur : `publicAnnouncementsHud = false`. Les annonces internes ne sont jamais affichées. Le protocole réseau passe en version 2 : le serveur et tous les clients doivent avoir la même version du mod.

## Panneau web des entreprises (port 30039)

Le mod ouvre un site de gestion des entreprises, avec les mêmes écrans et les mêmes règles que le menu en jeu : tout ce qui est fait sur le site est exécuté directement par le serveur.

**Deux accès**
- **Patron** : en jeu, `/ceconomy web` donne un code à 8 chiffres (valable 5 minutes, à usage unique). Réservé aux joueurs qui détiennent une entreprise ; ils gèrent toutes les entreprises qu'ils possèdent.
- **Conseil royal** : mot de passe (par défaut `2007`). Accès patron à toutes les entreprises : employés, salaires, droits, finances de l'entreprise, annonces, factures, impôts.

**Disponible sur le site** : fiche entreprise, employés (recruter, licencier, salaire, grade, droits, gérant), finances (paiements depuis le compte de l'entreprise), annonces, factures, impôts de l'entreprise.
**Réservé au Conseil royal** : toute la partie administration du menu en jeu (demandes de création, entreprises, joueurs, impôts, transactions, annonces, journal d'audit, réglages, limites, trésorerie royale, sauvegardes). Les patrons n'y ont jamais accès.
**Non disponible sur le site, pour personne** : banque personnelle, dépôt/retrait en pièces (il faut l'inventaire d'un joueur en jeu).

**Configuration** (fichier de config serveur du mod, section `web`) : `enabled`, `port` (30039), `bindAddress`, `councilPassword`, `publicUrl` (adresse affichée par `/ceconomy web`).
**À faire :**
1. Changez `councilPassword` : la valeur par défaut est connue et le serveur affiche un avertissement tant qu'elle n'est pas modifiée. Un mot de passe vide désactive l'accès Conseil.
2. Ouvrez le port TCP 30039 chez l'hébergeur (BoxToPlay), comme pour les autres panneaux web.

**Sécurité** : sessions de 2 h, verrouillage 10 min après 5 échecs de connexion par adresse, aucune action n'est crue côté navigateur (le serveur revérifie droits et montants). Le site est en HTTP simple : le mot de passe circule en clair sauf si vous placez un reverse proxy HTTPS devant.

## Recensement des citoyens (`/recensement`)
- Réservé aux **employés de la couronne**, aux admins et au Conseil royal (voir « Personnel du royaume » plus bas). Les autres joueurs ne peuvent ni lancer `/recensement` ni remplir leur fiche : ils voient seulement leur fiche (bouton « Ma fiche de citoyen »).
- La profession est automatique : le secteur de l'entreprise active du citoyen (propriétaire, sinon gérant, sinon employé), « Sans emploi » sinon. Un agent peut fixer une profession à la main.
- Tant que sa fiche n'est pas validée, le joueur reçoit un rappel à chaque connexion l'invitant à se présenter à un employé de la couronne.
- L'agent peut enregistrer, valider, rouvrir ou effacer une fiche. Chaque action est inscrite au journal et notifiée au joueur.
- Les fiches sont affichées dans la liste Tab par le mod **Citizen Tab** (à la place du pseudo), via l'API `fr.citizen.economy.api.Recensement`.

## Personnel du royaume et recensement

**Employés de la couronne** : Administration → *Personnel du royaume* (ou panneau web du Conseil royal). Recruter un joueur (pseudo ou n° de hibou), lui donner un titre (ex. Greffier) et un salaire en PO par période de paie. Le salaire est versé par la **trésorerie royale** à chaque paie, une seule fois par période, comme les salaires d'entreprise. Si la trésorerie est vide, le salaire est impayé et l'employé est prévenu.

**Recensement** : seuls les employés de la couronne, les admins et le Conseil royal peuvent recenser. `/recensement` est refusée aux autres joueurs. L'agent ouvre la liste des citoyens, remplit la fiche (prénom, nom, âge, profession facultative), enregistre puis valide. Un joueur voit seulement sa propre fiche, sans pouvoir la modifier.

## Emblèmes des entreprises (restrictions de Citizen Métiers)

L'emblème d'une entreprise est son **métier**. Citizen Métiers s'en sert : les joueurs qui n'appartiennent pas à une entreprise du bon métier sont bloqués pour les fabrications réservées.

| Emblème | Fabrications réservées |
|---|---|
| `FOR` (forgeron) | armures, lettres beuglantes, cartons |
| `MEN` (menuisier) | lettres beuglantes, colis, portes à clé, clés |
| `RES` (restaurant) | toute la nourriture |
| `TAV` (taverne) | nourriture et boissons |
| `POT` (potionniste) | potions et totems |
| `POI` (poissonnier) | préparation du poisson |
| `BAN` (banque) | aucune restriction |
| `AUC` (aucun) | aucune restriction |
| *(vide)* | Citizen Métiers devine d'après le secteur et le nom |

À la création d'une entreprise le joueur saisit l'un de ces codes à 3 lettres (les noms complets sont aussi acceptés et convertis) (ou le laisse vide). **Entreprises déjà créées** : Administration → Entreprises, écrire l'emblème dans le champ du bas puis cliquer sur « Emblème » à côté de l'entreprise (aussi possible depuis le panneau web du Conseil royal). Un patron ne peut pas changer l'emblème de sa propre entreprise. Pour qu'une entreprise n'ait aucune restriction pour ses employés, mettre `AUC` (ou `BAN`).

## Caisse, stock et comptabilité (portail web et en jeu)
Chaque entreprise a une **Caisse**, un **Stock**, une **Comptabilité** et un écran **Rachat de minerais**.
- Permissions (à donner aux employés) : *Tenir la caisse*, *Gérer le stock*, *Comptabilité*. Le patron et les gérants les ont toutes. Un employé qui n'a que « annonces » n'a pas accès au portail web.
- **Article** : prix de vente, coût, stock, seuil d'alerte, prix de rachat (facultatif) et **marge en %** (facultatif). Avec une marge, le prix de vente suit le coût moyen pondéré à chaque réapprovisionnement.
- **Vente** : sans client = vente au comptoir (encaissée tout de suite en comptabilité, les pièces ne sont pas déposées automatiquement) ; avec un client (pseudo ou @matricule) = facture ; le stock est réservé et rendu si la facture est refusée ou annulée.
- **Rachat de minerais** : le patron fixe le prix de rachat de chaque article. Un employé avec « Gérer le stock » choisit le vendeur et la quantité : l'entreprise paie le joueur par virement bancaire, le stock augmente au prix de rachat, une facture R-xxxxxx est enregistrée. Ce n'est ni une dépense ni une recette imposable (c'est du stock).
- **Comptabilité** : calculée depuis les vraies données (banque, ventes, stock, factures, impôts dus), avec journal en partie double et écritures manuelles (qui ne bougent jamais l'argent de la banque).

## Connexion Discord du portail et métiers dans Discord
1. Portail développeur Discord → votre application → OAuth2 : ajoutez la redirection `<publicUrl>/auth/discord/callback` et recopiez Client ID / Client Secret dans la section `discord` de la config de Citizen Economy (`clientId`, `clientSecret`, `redirectUri` si différent). Sans Client ID, le bouton n'apparaît pas.
2. Le joueur doit avoir fait `/pseudo` (Citizen Discord) : c'est la seule liaison utilisée. La connexion Discord ne donne jamais l'accès Conseil/admin et exige un droit de travail dans une entreprise.
3. Métiers → rôles Discord (Citizen Discord 1.4.0+) : désactivé par défaut (`roles_metiers_actif`). Créez les rôles à la main, placez le rôle du bot au-dessus d'eux ; le bot ne crée ni ne supprime de rôle. Seules les entreprises avec un emblème (FOR, MEN, RES, TAV, POT, POI) donnent un métier. Envoi toutes les `syncMinutes` minutes, ou tout de suite avec `/ceconomy discord`.

## Coffres d'entreprise
Un coffre ordinaire marqué au nom d'une entreprise : seuls ses membres (patron, gérants, employés) l'ouvrent. Rien à installer côté joueur (console comprise).
- **Donner un coffre (admin)** : `/ceconomy chest give <joueur> <entreprise>` (nom ou numéro). Le coffre est nommé « Coffre de <entreprise> » et brille.
- **Poser** : seuls le patron ou un gérant de cette entreprise peuvent le poser ; il est enregistré à cet instant. Un coffre posé contre lui (grand coffre) en devient une moitié, protégée aussi.
- **Ouvrir** : tous les membres actifs. Les autres reçoivent un message et le coffre reste fermé. Les admins peuvent toujours l'ouvrir.
- **Casser** : patron, gérants et admins seulement. Les explosions ne détruisent pas ces coffres. Un entonnoir ne peut pas être posé contre eux par un non-gérant.
- Un licenciement ou une embauche prend effet en moins de 5 secondes (cache rafraîchi régulièrement).
- **Limites** : un entonnoir posé par un membre, un piston ou un autre mod peut encore déplacer le contenu. Un coffre cassé laisse un coffre ordinaire (non lié) : redonnez-en un si besoin.
