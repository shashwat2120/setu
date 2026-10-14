package com.setu.audit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * AGENTS.md standing rule 4 and canonical spec §3/§10: the AI module never gets a datasource and never reads another module's
 * repository. Its only way to data is the application-owned, authorization-checked tool methods that call other modules' application
 * services. {@code ModularityTests} checks module boundaries in general; this pins the AI-specific ones down so they cannot be relaxed
 * by accident.
 */
class AiExplainBoundaryTests {

    private static final JavaClasses AI = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.setu.aiexplain");

    @Test
    void theAiModuleHasNoDatasourceAccessOfItsOwn() {
        noClasses().that().resideInAPackage("com.setu.aiexplain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jakarta.persistence..", "javax.persistence..", "org.hibernate..", "org.springframework.data.jpa..",
                "org.springframework.jdbc..", "org.springframework.orm..", "java.sql..", "javax.sql..")
            .because("the AI module must never get a datasource or raw SQL access")
            .check(AI);
    }

    @Test
    void theAiModuleDoesNotTouchAnyRepositoryOrAnotherModulesInternals() {
        noClasses().that().resideInAPackage("com.setu.aiexplain..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .because("data reaches the AI only through authorization-checked application services")
            .check(AI);
        noClasses().that().resideInAPackage("com.setu.aiexplain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.setu.identity.infrastructure..", "com.setu.institution.infrastructure..", "com.setu.directory.infrastructure..",
                "com.setu.catalog.infrastructure..", "com.setu.enrollment.infrastructure..", "com.setu.attendance.infrastructure..",
                "com.setu.learning.infrastructure..", "com.setu.assessment.infrastructure..", "com.setu.record.infrastructure..",
                "com.setu.finance.infrastructure..", "com.setu.career.infrastructure..", "com.setu.risk.infrastructure..",
                "com.setu.notification.infrastructure..", "com.setu.guardianaccess.infrastructure..")
            .check(AI);
    }

    @Test
    void theAiModuleHasNoJpaEntitiesAndNoSpringDataRepositories() {
        noClasses().that().resideInAPackage("com.setu.aiexplain..")
            .should().beAnnotatedWith("jakarta.persistence.Entity")
            .orShould().beAssignableTo("org.springframework.data.repository.Repository")
            .check(AI);
    }
}
