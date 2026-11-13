# Résultats des tests

## Ce qui a réellement été exécuté (dans l'environnement de développement)
**41 tests, 41 réussis, 0 échec**, sur une vraie base SQLite, avec Java 21.

Méthode et limites, à connaître :
- Le réseau de l'environnement bloquait JUnit/Gradle/NeoForge. Les tests ont donc été lancés avec un **mini-lanceur compatible JUnit 5** (annotations `@Test`, `@BeforeEach`, `@AfterEach`, `@TempDir`) et un pilote JDBC de test qui relaie les requêtes vers le **vrai moteur SQLite** (Python `sqlite3`). Les tests sont écrits pour le vrai JUnit 5 : `gradlew test` doit donner le même résultat chez vous avec le pilote `sqlite-jdbc`.
- Aucun test n'a pu tourner **avec Minecraft/NeoForge** : paquets, commandes, objets, écrans et inventaire réel ne sont pas testés (voir liste de contrôle ci-dessous).
- Vérification que les tests peuvent échouer : une attente volontairement fausse a bien produit un échec.

## Correspondance avec les 20 tests obligatoires
| # | Test | Couvert par |
|---|---|---|
| 1 | Création d'une entreprise | CompanyFlowTest.creationAndValidationFlow ; UiServiceTest.companyCreationFlowThroughUi |
| 2 | Validation administrative | CompanyFlowTest.creationAndValidationFlow, refusalAndChangesRequested |
| 3 | Recrutement | CompanyFlowTest.hierarchyAndPermissions ; UiServiceTest.companyCreationFlowThroughUi |
| 4 | Licenciement | CompanyFlowTest.fireAndSuspension |
| 5 | Modification d'un salaire | CompanyFlowTest.hierarchyAndPermissions, BankFlowTest.businessPermissionsAndPayroll |
| 6 | Paiement automatique | BankFlowTest.businessPermissionsAndPayroll ; FinalChecksTest.schedulerPaysSalariesOncePerSunday |
| 7 | Dépôt bancaire | BankFlowTest.depositWithdrawAndIdempotence ; UiServiceTest.depositWithChangeThenReplayIsRefunded (avec pièces) |
| 8 | Retrait bancaire | BankFlowTest.depositWithdrawAndIdempotence ; UiServiceTest.withdrawGivesCoinsAndCompensatesWhenOffline |
| 9 | Transfert entre comptes | BankFlowTest.transfersConserveMoney ; UiServiceTest.transferBetweenPlayers |
| 10 | Création d'une facture | AnnouncementInvoiceTest.invoiceLifecycleAndPayment ; UiServiceTest.companyCreationFlowThroughUi |
| 11 | Paiement d'une facture | AnnouncementInvoiceTest.invoiceLifecycleAndPayment, insufficientFundsKeepsInvoicePending_refuseAndOverdue |
| 12 | Taxe professionnelle | TaxFlowTest.weeklyDeclarationOnSunday_noRetroactivity_manualPaymentToTreasury |
| 13 | Taxe personnelle | FinalChecksTest.personalIncomeTaxOnSelectedIncomeWithoutDoubleTaxation |
| 14 | Modification des taxes | TaxFlowTest (pas de rétroactivité), UiServiceTest.adminTaxConfigurationAndExemption |
| 15 | Annonce interne | AnnouncementInvoiceTest.announcementPermissionsAndModeration |
| 16 | Annonce publique | AnnouncementInvoiceTest.announcementPermissionsAndModeration ; UiServiceTest.companyCreationFlowThroughUi |
| 17 | Contrôle des permissions | CompanyFlowTest.hierarchyAndPermissions, TaxFlowTest.onlyAdminsConfigureTaxes, UiServiceTest.adminOnlyActionsAndViewsAreForbiddenToPlayers, forgedClientDataCannotEscalate |
| 18 | Redémarrage du serveur | FinalChecksTest.dataSurvivesServerRestart (fermeture + réouverture de la base ; **pas** un vrai redémarrage de serveur Minecraft) |
| 19 | Conservation des données | FinalChecksTest.dataSurvivesServerRestart, DatabaseTest.backupProducesReadableCopy |
| 20 | Transactions simultanées | BankFlowTest.concurrentTransfersNeverOverdraw, DatabaseTest.concurrentWritesAreSerialized |

Autres : migrations idempotentes, rejet des `tx_uuid` en double, rollback des soldes négatifs, limitation de débit, codec JSON, calcul de monnaie, rendu de **tous** les écrans joueur et admin, notifications (livrées une seule fois).

## À tester en jeu (non vérifié)
1. Le mod se charge sur le serveur et sur un client (NeoForge 21.1.215), sans erreur dans `latest.log`.
2. Onglet créatif « Citizen Economy » : 4 pièces avec texture et nom.
3. Touche K et `/ceconomy` ouvrent l'écran ; défilement, champs de saisie, boutons, fenêtre de confirmation.
4. Dépôt de pièces (avec monnaie rendue), retrait en pièces, inventaire plein.
5. Demande d'entreprise → acceptation par un admin → recrutement → paie le dimanche.
6. Un joueur non admin ne voit pas et ne peut pas utiliser `/ceconomy admin` ni `/ceconomy treasury`.
7. Redémarrage du serveur : soldes et entreprises conservés ; notifications reçues à la connexion.
8. Deux joueurs cliquant en même temps sur des virements : soldes cohérents (Menu admin → Paramètres → « Vérifier les soldes »).
