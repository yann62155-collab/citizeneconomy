-- V2 : suivi de la remise des notifications en jeu (chat) distinct du statut "lu" de l'historique
ALTER TABLE notifications ADD COLUMN delivered_at INTEGER;

CREATE INDEX ix_notifications_undelivered ON notifications(delivered_at, player_uuid)
