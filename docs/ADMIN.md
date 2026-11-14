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
