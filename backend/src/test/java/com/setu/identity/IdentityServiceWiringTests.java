package com.setu.identity;

import com.setu.shared.authorization.GuardianDelegationChecker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Identity service starts with only the identity module and the shared kernel: no bean from any other module is in its context, it
 * publishes its keys, and it denies guardian delegation (no guardian-access module here) instead of failing to start.
 */
@SpringBootTest(classes = IdentityServiceApplication.class, properties = {"setu.service=identity", "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class IdentityServiceWiringTests {

    private static final List<String> OTHER_MODULES = List.of("institution", "catalog", "enrollment", "attendance", "learning", "assessment",
        "record", "finance", "career", "risk", "aiexplain", "notification", "analytics", "directory", "guardianaccess");

    @Autowired
    ApplicationContext context;
    @Autowired
    MockMvc mockMvc;

    @Test
    void noBeanFromAnotherModuleIsPresent() {
        List<String> foreign = Arrays.stream(context.getBeanDefinitionNames())
            .map(name -> context.getType(name))
            .filter(type -> type != null && type.getName().startsWith("com.setu."))
            .map(Class::getName)
            .filter(className -> OTHER_MODULES.stream().anyMatch(module -> className.startsWith("com.setu." + module + ".")))
            .toList();
        assertThat(foreign).isEmpty();
    }

    @Test
    void theCrossModuleSharedPartsAreNotLoaded() {
        List<String> names = Arrays.stream(context.getBeanDefinitionNames())
            .map(name -> context.getType(name)).filter(type -> type != null).map(Class::getName).toList();
        assertThat(names).noneMatch(n -> n.startsWith("com.setu.shared.admin.") || n.startsWith("com.setu.shared.seed."));
    }

    @Test
    void guardianDelegationIsUnavailableHere() {
        assertThat(context.getBeanProvider(GuardianDelegationChecker.class).getIfAvailable()).isNull();
    }

    @Test
    void itPublishesItsKeysAndStillProtectsEverythingElse() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk());
        mockMvc.perform(get("/institution/colleges")).andExpect(status().isUnauthorized());
    }
}
