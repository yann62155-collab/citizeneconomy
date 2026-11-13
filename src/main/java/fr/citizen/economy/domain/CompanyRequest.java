package fr.citizen.economy.domain;
import java.util.UUID;
public record CompanyRequest(long id, UUID applicant, String applicantName, String name, String description,
                             String sector, String emblem, RequestStatus status, String adminNote, long createdAt) { }
