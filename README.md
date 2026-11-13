# Citizen Economy

Mod économique RP pour le serveur **Citizen** — Minecraft Java **1.21.1**, **NeoForge 21.1.215** (exactement), **Java 21**, base **SQLite**.
ID du mod : `citizeneconomy`. Monnaie : **PO** (pièces bronze 1, argent 10, or 100, platine 1000).

> **ÉTAT HONNÊTE DU PROJET**
> - La logique métier (entreprises, banque, taxes, trésor, annonces, factures, notifications, planificateur, moteur d'interface côté serveur) est écrite **et testée** : 41 tests passent sur une vraie base SQLite (voir `docs/TESTS.md`).
> - Le code qui touche Minecraft/NeoForge (`platform/`, `client/`, `CitizenEconomy.java`) a été écrit **sans pouvoir être compilé** ici (le réseau du bac à sable bloque les dépôts NeoForge/Mojang). Il suit l'API NeoForge 21.1 mais **peut contenir des erreurs de compilation** à corriger au premier build.
> - **Aucun JAR n'a pu être produit** dans cet environnement. Vous le compilez vous-même (étapes ci-dessous). Si le build échoue, envoyez-moi le message d'erreur : la correction est rapide.
> - Les écrans, commandes et pièces n'ont **jamais été essayés en jeu**. Les tests en jeu sont à faire par vous (liste de contrôle dans `docs/TESTS.md`).

## Compiler le mod (une seule fois, sur votre PC)

Prérequis : **JDK 21** (Temurin 21), connexion internet (téléchargement de NeoForge et Minecraft par Gradle).

1. Dézippez le projet.
2. Il manque le *wrapper* Gradle (je n'ai pas pu le télécharger). Deux possibilités :
   - copiez les fichiers `gradlew`, `gradlew.bat` et le dossier `gradle/` depuis le MDK officiel NeoForge pour 1.21.1 ; **ou**
   - installez Gradle 8.8 ou plus récent, puis dans le dossier du projet : `gradle wrapper`.
3. Lancez :
   - Windows : `gradlew.bat build`
   - Linux / macOS : `./gradlew build`
4. Le JAR est dans `build/libs/citizeneconomy-0.1.0.jar` (le pilote SQLite est embarqué via JarJar).

Si Gradle dit que le plugin `net.neoforged.moddev` version `2.0.78` est introuvable, ouvrez `build.gradle` du MDK NeoForge 1.21.1 officiel et reprenez la version du plugin indiquée. **Ne changez jamais** `neo_version=21.1.215` ni la version de Minecraft sans accord explicite.

Pour lancer les tests unitaires : `gradlew test`.

## Installer

| Où | Quoi |
|---|---|
| **Serveur** | Java 21 + NeoForge 21.1.215. Copier le JAR dans `mods/`. Démarrer une fois. |
| **Chaque joueur** | Même JAR dans `.minecraft/mods/` (NeoForge 21.1.215 aussi). Un client sans le mod est refusé. |

Au premier démarrage le serveur crée, dans le dossier du monde :
- `citizeneconomy/citizeneconomy.db` (base SQLite, tables et index créés automatiquement),
- `citizeneconomy/backups/` (sauvegardes automatiques toutes les 6 h, 30 conservées),
- `serverconfig/citizeneconomy-server.toml` (configuration).

### Rendre quelqu'un administrateur
Par défaut, tout opérateur (niveau 4) est administrateur de l'économie. Pour ajouter des pseudos (sans LuckPerms), éditez `serverconfig/citizeneconomy-server.toml` :

```toml
[admin]
adminPermissionLevel = 4
adminPlayers = ["Pseudo1", "uuid-du-joueur"]
```
Redémarrez (ou rechargez) pour appliquer. Les droits sont toujours revérifiés **côté serveur** à chaque action.

## Utilisation rapide
- Touche **K** ou `/ceconomy` : menu principal. Tout se fait dans les écrans.
- Les joueurs déposent/retirent de l'argent avec les pièces (créatives : onglet « Citizen Economy » ; en jeu, l'administration les distribue via le trésor royal → « Retirer en espèces »).
- Détail des commandes : `docs/COMMANDES.md` ; administration : `docs/ADMIN.md` ; technique : `docs/TECHNIQUE.md`.
