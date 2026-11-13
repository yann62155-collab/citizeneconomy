# Documentation technique

## Pile
Java 21, NeoForge 21.1.215, Minecraft 1.21.1, SQLite (`org.xerial:sqlite-jdbc` 3.46.x embarqué par JarJar). Gradle avec `net.neoforged.moddev`.

## Couches (`fr.citizen.economy`)
- `domain` : enregistrements et énumérations purs (Actor, Company, Member, Permission, TxType, Tax*, Invoice…).
- `persistence` : `Database` (un seul thread d'écriture `BEGIN IMMEDIATE`, un thread de lecture, WAL, `synchronous=FULL`, sauvegardes `VACUUM INTO`), `Migrator` (migrations numérotées `db/migration/V*.sql`, refuse une base plus récente).
- `service` : logique métier, **aucune dépendance Minecraft** (donc testable) : CompanyManager, EmployeeManager, PermissionManager, BankManager, TransactionManager, PayrollManager, TaxManager/TaxPeriods, TreasuryManager, AnnouncementManager, InvoiceManager, NotificationManager, PlayerManager, AdminManager, EconomyScheduler, `Economy` (conteneur).
- `ui` : `UiService` (construit les écrans `View` et exécute les actions), `ViewCodec`/`Json` (JSON borné), `CashPlatform` (pont inventaire, interface).
- `platform` : colle NeoForge (config, objets, paquets, commandes, cycle de vie serveur, inventaire réel).
- `client` : `ViewScreen` (rendu médiéval : noir, or ancien, rouge sombre, parchemin dessiné procéduralement) et touche K.

## Sécurité
- **Interface pilotée par le serveur** : le client reçoit une `View` (JSON) et renvoie `(action, arguments)`. L'identité (UUID, pseudo, statut admin) est lue côté serveur dans `Admins.actor`, jamais dans le paquet.
- Chaque service revalide permissions, appartenance, statut d'entreprise, montants (entiers positifs bornés) et soldes **dans la transaction SQL**.
- Arguments : ≤ 40 clés, clé ≤ 32, valeur ≤ 1100 caractères, pas d'imbrication ; limitation à 8 actions/s/joueur.
- Argent : entiers `long`, grand livre à double entrée, `tx_uuid UNIQUE` (idempotence : un double clic ou un rejeu ne déplace rien), soldes jamais négatifs (contrainte + vérification), `verifyBalances`.
- Les actions `adm.*` exigent `Actor.admin` avant tout traitement ; les services refont la vérification.
- Dépôt : pièces retirées d'abord, rendues si la transaction échoue ou est un rejeu. Retrait : débit d'abord, pièces ensuite, compensation si le joueur est parti.

## Réseau
Deux paquets (version de protocole `"1"`) : `citizeneconomy:view` (S→C), `citizeneconomy:action` (C→S, ≤ 8192 caractères). Le travail bloquant (SQL) tourne sur un pool de 2 threads, les accès inventaire sont renvoyés sur le thread serveur.

## Fichiers de données
`<monde>/citizeneconomy/citizeneconomy.db`, `backups/`, `<monde>/serverconfig/citizeneconomy-server.toml`.

## Ajouter une migration
Créer `V3__nom.sql` dans `src/main/resources/db/migration/` et l'ajouter à `Migrator.FILES`.
