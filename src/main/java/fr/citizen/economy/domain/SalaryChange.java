package fr.citizen.economy.domain;
public record SalaryChange(long oldSalary, long newSalary, String changedBy, long changedAt) { }
