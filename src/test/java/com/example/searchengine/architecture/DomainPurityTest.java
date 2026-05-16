package com.example.searchengine.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit tests enforcing domain purity — the scoring engine (and the entire domain layer)
 * must remain free of framework dependencies.
 *
 * Validates: Requirements 6.1, 22.2
 */
@AnalyzeClasses(packages = "com.example.searchengine", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainPurityTest {

    /**
     * REQ 6.1, 22.2 — The Scoring Engine is a pure-Java domain component with no Spring,
     * JPA, Jackson, or XML framework dependencies.
     */
    @ArchTest
    static final ArchRule scoring_engine_has_no_framework_dependencies = noClasses()
            .that().resideInAPackage("..domain.scoring..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "com.fasterxml.jackson..",
                    "jakarta.xml..");

    /**
     * REQ 22.1 — The entire domain layer must not depend on application, infrastructure, or web layers.
     */
    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..",
                    "..infrastructure..",
                    "..web..");

    /**
     * REQ 22.2 — The entire domain layer must not depend on Spring or JPA frameworks.
     */
    @ArchTest
    static final ArchRule domain_has_no_spring_or_jpa_dependencies = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..");
}
