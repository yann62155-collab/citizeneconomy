package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class CompanyFlowTest {
    @TempDir Path tmp;
    Database db;
    CompanyManager companies;
    EmployeeManager employees;

    final Actor admin = new Actor(UUID.randomUUID(), "Admin", true);
    final Actor owner = new Actor(UUID.randomUUID(), "Owner", false);
    final Actor manager = new Actor(UUID.randomUUID(), "Manager", false);
    final Actor worker = new Actor(UUID.randomUUID(), "Worker", false);
    final Actor stranger = new Actor(UUID.randomUUID(), "Stranger", false);

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        companies = new CompanyManager(db);
        employees = new EmployeeManager(db);
    }

    @AfterEach
    void tearDown() { db.close(); }

    static String code(Supplier<?> s) {
        CompletionException e = assertThrows(CompletionException.class, s::get);
        return ((EconomyException) EconomyException.unwrap(e)).code();
    }

    long createCompany() {
        long req = companies.submitRequest(owner, "Forge Royale", "Armes et armures", "Artisanat", "").join();
        return companies.accept(admin, req, "").join();
    }

    @Test
    void creationAndValidationFlow() {
        long req = companies.submitRequest(owner, "Forge Royale", "desc", "Artisanat", "").join();
        assertEquals("REQUEST_EXISTS", code(() -> companies.submitRequest(owner, "Autre", "", "Commerce", "").join()));
        assertEquals("FORBIDDEN", code(() -> companies.accept(owner, req, "").join())); // un joueur ne s'auto-valide pas
        long id = companies.accept(admin, req, "").join();
        Company co = companies.get(owner, id).join();
        assertEquals(CompanyStatus.ACTIVE, co.status());
        assertEquals(owner.id(), co.owner());
        assertEquals("BAD_STATE", code(() -> companies.accept(admin, req, "").join())); // pas de double validation
        assertEquals("NAME_TAKEN", code(() -> companies.submitRequest(stranger, "FORGE ROYALE", "", "x1", "").join()));
    }

    @Test
    void refusalAndChangesRequested() {
        long req = companies.submitRequest(owner, "Taverne", "", "Restauration", "").join();
        companies.requestChanges(admin, req, "Precisez la description").join();
        companies.updateRequest(owner, req, "Taverne du Lion", "Auberge", "Restauration", "").join();
        companies.refuse(admin, req, "Nom non conforme").join();
        assertEquals("BAD_STATE", code(() -> companies.updateRequest(owner, req, "X Y Z", "", "ab", "").join()));
    }

    @Test
    void hierarchyAndPermissions() {
        long id = createCompany();
        employees.hire(owner, id, manager.id(), manager.name(), 100).join();
        employees.hire(owner, id, worker.id(), worker.name(), 50).join();
        employees.promoteToManager(owner, id, manager.id()).join();

        // un employe ne peut rien faire de reserve aux gerants
        assertEquals("FORBIDDEN", code(() -> employees.hire(worker, id, stranger.id(), "S", 0).join()));
        assertEquals("FORBIDDEN", code(() -> employees.setSalary(worker, id, worker.id(), 9999).join()));
        assertEquals("FORBIDDEN", code(() -> employees.setPermissions(worker, id, worker.id(), PermissionManager.ALL).join()));
        // un etranger n'a aucun acces
        assertEquals("FORBIDDEN", code(() -> employees.members(stranger, id).join()));
        // le gerant gere les employes mais ni le proprietaire ni lui-meme
        employees.setSalary(manager, id, worker.id(), 80).join();
        assertEquals("FORBIDDEN", code(() -> employees.setSalary(manager, id, manager.id(), 5000).join()));
        assertEquals("FORBIDDEN", code(() -> employees.fire(manager, id, owner.id()).join()));
        // permission reservee impossible a un employe : filtree
        employees.setPermissions(manager, id, worker.id(), PermissionManager.ALL).join();
        Member w = employees.members(owner, id).join().stream().filter(m -> m.player().equals(worker.id())).findFirst().orElseThrow();
        assertEquals(Permission.PUBLISH_ANNOUNCEMENTS.bit() | Permission.VIEW_FINANCES.bit() | Permission.ISSUE_INVOICES.bit(), w.permissions());
        assertEquals(2, employees.salaryHistory(owner, id, worker.id()).join().size());
    }

    @Test
    void fireAndSuspension() {
        long id = createCompany();
        employees.hire(owner, id, worker.id(), worker.name(), 10).join();
        companies.setStatus(admin, id, CompanyStatus.SUSPENDED, "Enquete").join();
        assertEquals("COMPANY_NOT_ACTIVE", code(() -> employees.fire(owner, id, worker.id()).join()));
        companies.setStatus(admin, id, CompanyStatus.ACTIVE, "Fin enquete").join();
        employees.fire(owner, id, worker.id()).join();
        assertEquals("FORBIDDEN", code(() -> employees.members(worker, id).join()));
    }
}
