package com.setu.audit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
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

    /** What the AI module may use of the kernel: the facts contract, the transport it publishes job status on, and the request plumbing every service shares. */
    private static final String[] ALLOWED_KERNEL = {
        "com.setu.shared.aifacts..", "com.setu.shared.websocket..", "com.setu.shared.resilience..", "com.setu.shared.web..",
        "com.setu.shared.authorization..", "com.setu.shared.security..", "com.setu.shared.config.."};

    /**
     * 6B.3: the AI advisor runs as its own service, so it may depend on no other module at all, not even on its application services: only on
     * itself and a short list of kernel contracts. Everything it learns about a person arrives through {@code AiFacts}.
     */
    @Test
    void theAiModuleDependsOnNoOtherModuleAndOnlyOnTheKernelContractsItNeeds() {
        DescribedPredicate<JavaClass> otherSetuCode = JavaClass.Predicates.resideInAPackage("com.setu..")
            .and(JavaClass.Predicates.resideOutsideOfPackages("com.setu.aiexplain.."))
            .and(JavaClass.Predicates.resideOutsideOfPackages(ALLOWED_KERNEL));
        noClasses().that().resideInAPackage("com.setu.aiexplain..")
            .should().dependOnClassesThat(otherSetuCode)
            .because("the AI service has no datasource and calls no other module; its facts come through com.setu.shared.aifacts.AiFacts")
            .check(AI);
    }

    @Test
    void theAiModuleDoesNotWriteAuditRowsOrEventsItself() {
        noClasses().that().resideInAPackage("com.setu.aiexplain..")
            .should().dependOnClassesThat().resideInAnyPackage("com.setu.shared.governance..", "com.setu.shared.readmodel..")
            .because("an audit record is written by the service that owns the audit log, on the advisor's behalf, for the token's own user")
            .check(AI);
    }
}
