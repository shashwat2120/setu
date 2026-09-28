package com.setu;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Enforces the module boundaries documented in docs/TRD.md &sect;2 and
 * docs/table-ownership-map.md at build time. This is the test AGENTS.md
 * prompt 0.2 requires: a module boundary violation (one module's code
 * reaching directly into another module's {@code domain}/{@code
 * infrastructure} packages instead of going through its {@code application}
 * layer) fails the build here, not in code review.
 * <p>
 * Does not require a database &mdash; this only inspects the compiled
 * package structure, so it runs even before Sprint 5B's persistence layer
 * exists.
 */
class ModularityTests {

    ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    @Test
    void moduleStructureIsRespected() {
        modules.verify();
    }

    @Test
    void writeDocumentationSnapshot() {
        new Documenter(modules)
            .writeDocumentation()
            .writeIndividualModulesAsPlantUml();
    }
}
