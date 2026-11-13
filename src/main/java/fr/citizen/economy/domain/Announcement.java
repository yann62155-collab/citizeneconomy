package fr.citizen.economy.domain;
import java.util.UUID;
public record Announcement(long id, long companyId, String companyName, UUID author, String authorName, String title,
                           String body, AnnouncementCategory category, AnnouncementVisibility visibility, String status,
                           long createdAt, long updatedAt, Long expiresAt, String moderatedBy) { }
