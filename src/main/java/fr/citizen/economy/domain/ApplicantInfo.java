package fr.citizen.economy.domain;
import java.util.UUID;
public record ApplicantInfo(UUID uuid, String name, long firstSeen, long lastSeen, int activeCompanies,
                            int refusedRequests) { }
