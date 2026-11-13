package fr.citizen.economy.domain;
import java.util.UUID;
public record Member(long employeeId, long companyId, UUID player, String name, CompanyRole role, String grade,
                     long salary, int permissions, long hiredAt) { }
