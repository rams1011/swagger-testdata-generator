package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AddMemberRequest;
import com.tx.api.test.generator.studio.dto.LoginRequest;
import com.tx.api.test.generator.studio.dto.MemberView;
import com.tx.api.test.generator.studio.dto.RegisterRequest;
import com.tx.api.test.generator.studio.dto.UpdateSubscriptionRequest;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantIsolationTest {

    private DataSource dataSource;
    private JdbcStudioStorage storage;
    private AuthService authService;
    private StudioProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("saas_tenant_" + System.nanoTime()),
                "sa",
                "");
        h2.setDriverClassName(H2Database.DRIVER);
        dataSource = h2;
        try (Connection connection = dataSource.getConnection();
             Reader schema = new InputStreamReader(
                     new ClassPathResource("ddl/schema.sql").getInputStream(), StandardCharsets.UTF_8)) {
            RunScript.execute(connection, schema);
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        storage = new JdbcStudioStorage(jdbcTemplate);
        properties = new StudioProperties();
        properties.getSaas().setEnabled(true);
        TenantContext.setSaasEnabled(true);
        authService = new AuthService(properties, new FixedStore(new JdbcTenantStore(jdbcTemplate)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        TenantContext.setSaasEnabled(false);
        if (dataSource != null) {
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
    }

    @Test
    void specsAreNotVisibleAcrossWorkspaces() {
        var acme = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        var globex = authService.register(new RegisterRequest("qa@globex.test", "password1", "Globex QA", "Globex"));

        TenantContext.runAs(acme.tenant().id(), () -> {
            storage.saveSpec(spec("spec-acme", "employee-service"));
            assertEquals(1, storage.listSpecs().size());
        });
        TenantContext.runAs(globex.tenant().id(), () -> {
            assertTrue(storage.listSpecs().isEmpty());
            assertTrue(storage.findSpec("spec-acme").isEmpty());
            storage.saveSpec(spec("spec-globex", "employee-service"));
            assertEquals("employee-service", storage.listSpecs().get(0).getName());
        });
        TenantContext.runAs(acme.tenant().id(), () -> {
            assertEquals(1, storage.listSpecs().size());
            assertEquals("spec-acme", storage.listSpecs().get(0).getSpecId());
            assertTrue(storage.findSpec("spec-globex").isEmpty());
        });
    }

    @Test
    void loginReturnsOnlyTheCallersWorkspaces() {
        authService.register(new RegisterRequest("one@example.com", "password1", "One", "One"));
        var two = authService.login(new LoginRequest("one@example.com", "password1", null));
        assertEquals("one", two.tenant().slug());
        assertEquals(1, two.tenants().size());
    }

    @Test
    void addedMemberSeesTheSameWorkspaceSpecs() {
        var owner = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        var globex = authService.register(new RegisterRequest("qa@globex.test", "password1", "Globex QA", "Globex"));

        TenantContext.runAs(owner.tenant().id(), owner.account().id(), "OWNER", () -> {
            storage.saveSpec(spec("spec-acme", "employee-service"));
            MemberView teammate = authService.addMember(
                    new AddMemberRequest("dev@acme.test", "Acme Dev", "password1", "MEMBER"));
            assertEquals("dev@acme.test", teammate.email());
            assertEquals("MEMBER", teammate.role());
            assertEquals(2, authService.listMembers().size());
        });
        TenantContext.runAs(globex.tenant().id(), globex.account().id(), "OWNER", () -> {
            storage.saveSpec(spec("spec-globex", "employee-service"));
        });

        var teammate = authService.login(new LoginRequest("dev@acme.test", "password1", owner.tenant().id()));
        assertEquals(owner.tenant().id(), teammate.tenant().id());
        TenantContext.runAs(teammate.tenant().id(), () -> {
            assertEquals(1, storage.listSpecs().size());
            assertEquals("spec-acme", storage.listSpecs().get(0).getSpecId());
            assertTrue(storage.findSpec("spec-globex").isEmpty());
        });
    }

    @Test
    void existingAccountCanJoinAnotherWorkspaceWithoutANewPassword() {
        var acme = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        var globex = authService.register(new RegisterRequest("qa@globex.test", "password1", "Globex QA", "Globex"));

        TenantContext.runAs(acme.tenant().id(), acme.account().id(), "OWNER", () -> {
            MemberView invited = authService.addMember(
                    new AddMemberRequest("qa@globex.test", null, null, "ADMIN"));
            assertEquals(globex.account().id(), invited.accountId());
            assertEquals("ADMIN", invited.role());
        });

        var switched = authService.login(new LoginRequest("qa@globex.test", "password1", acme.tenant().id()));
        assertEquals(acme.tenant().id(), switched.tenant().id());
        assertEquals("ADMIN", switched.tenant().role());
        assertEquals(2, switched.tenants().size());
    }

    @Test
    void memberCannotManageMembersAndLastOwnerCannotBeRemoved() {
        var owner = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        TenantContext.runAs(owner.tenant().id(), owner.account().id(), "OWNER", () -> {
            authService.addMember(new AddMemberRequest("dev@acme.test", "Dev", "password1", "MEMBER"));
            ForbiddenException lastOwner = org.junit.jupiter.api.Assertions.assertThrows(
                    ForbiddenException.class, () -> authService.removeMember(owner.account().id()));
            assertTrue(lastOwner.getMessage().contains("at least one owner"));
        });

        var member = authService.login(new LoginRequest("dev@acme.test", "password1", owner.tenant().id()));
        TenantContext.runAs(member.tenant().id(), member.account().id(), "MEMBER", () -> {
            org.junit.jupiter.api.Assertions.assertThrows(ForbiddenException.class, authService::listMembers);
            org.junit.jupiter.api.Assertions.assertThrows(ForbiddenException.class, () ->
                    authService.addMember(new AddMemberRequest("other@acme.test", "Other", "password1", "MEMBER")));
        });
    }

    @Test
    void newWorkspacesStayLockedUntilSaasAdminEnablesThem() {
        properties.getSaas().setAdminEmail("saas.admin@local.test");
        properties.getSaas().setAdminPassword("saas-admin-1");
        assertEquals("saas.admin@local.test", authService.ensureSaasAdmin());

        var customer = authService.register(new RegisterRequest("qa@acme.test", "password1", "Acme QA", "Acme"));
        assertFalse(customer.tenant().ready());
        assertFalse(customer.saasAdmin());

        var admin = authService.login(new LoginRequest("saas.admin@local.test", "saas-admin-1", null));
        assertTrue(admin.saasAdmin());
        assertTrue(admin.tenant().ready());

        TenantContext.runAs(admin.tenant().id(), admin.account().id(), "OWNER", () -> {
            var pending = authService.listSubscriptions().stream()
                    .filter(item -> item.tenantId().equals(customer.tenant().id()))
                    .findFirst()
                    .orElseThrow();
            assertFalse(pending.ready());
            assertEquals("qa@acme.test", pending.ownerEmail());
            var enabled = authService.updateSubscription(
                    customer.tenant().id(), new UpdateSubscriptionRequest(true));
            assertTrue(enabled.ready());
        });

        var unlocked = authService.login(new LoginRequest("qa@acme.test", "password1", customer.tenant().id()));
        assertTrue(unlocked.tenant().ready());

        TenantContext.runAs(customer.tenant().id(), customer.account().id(), "OWNER", () -> {
            assertThrows(ForbiddenException.class, authService::listSubscriptions);
        });
    }

    @Test
    void saasAdminCannotDisableThePlatformWorkspace() {
        properties.getSaas().setAdminEmail("saas.admin@local.test");
        properties.getSaas().setAdminPassword("saas-admin-1");
        authService.ensureSaasAdmin();
        var admin = authService.login(new LoginRequest("saas.admin@local.test", "saas-admin-1", null));
        TenantContext.runAs(admin.tenant().id(), admin.account().id(), "OWNER", () -> {
            ForbiddenException denied = assertThrows(ForbiddenException.class, () ->
                    authService.updateSubscription(admin.tenant().id(), new UpdateSubscriptionRequest(false)));
            assertTrue(denied.getMessage().contains("cannot be disabled"));
        });
    }

    private static StoredSpec spec(String id, String name) {
        StoredSpec spec = new StoredSpec();
        spec.setSpecId(id);
        spec.setName(name);
        spec.setTitle(name);
        spec.setLabel("General");
        spec.setBaseUrl("http://localhost:8080");
        return spec;
    }

    private static final class FixedStore implements ObjectProvider<TenantStore> {
        private final TenantStore store;

        private FixedStore(TenantStore store) {
            this.store = store;
        }

        @Override
        public TenantStore getObject(Object... args) throws BeansException {
            return store;
        }

        @Override
        public TenantStore getIfAvailable() {
            return store;
        }

        @Override
        public TenantStore getIfAvailable(Supplier<TenantStore> defaultSupplier) {
            return store;
        }

        @Override
        public TenantStore getIfUnique() {
            return store;
        }

        @Override
        public TenantStore getIfUnique(Supplier<TenantStore> defaultSupplier) {
            return store;
        }

        @Override
        public Stream<TenantStore> stream() {
            return Stream.of(store);
        }

        @Override
        public Stream<TenantStore> orderedStream() {
            return Stream.of(store);
        }

        @Override
        public TenantStore getObject() throws BeansException {
            return store;
        }

        @Override
        public Iterator<TenantStore> iterator() {
            return List.of(store).iterator();
        }

        @Override
        public void forEach(Consumer<? super TenantStore> action) {
            action.accept(store);
        }
    }
}
