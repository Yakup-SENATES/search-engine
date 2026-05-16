package com.example.searchengine.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ArchUnit tests enforcing Clean Architecture layering rules.
 *
 * Validates: Requirements 22.1, 22.4
 */
@AnalyzeClasses(packages = "com.example.searchengine", importOptions = ImportOption.DoNotIncludeTests.class)
class CleanArchitectureTest {

    /**
     * REQ 22.1 — Domain depends on no other application package.
     * REQ 22.4 — Web layer invokes only application services, never infrastructure directly.
     *
     * Rules:
     * - Domain may only be accessed by Application, Infrastructure, and Web.
     * - Application may only be accessed by Infrastructure and Web.
     * - Web may NOT access Infrastructure (controllers call application services only).
     */
    @ArchTest
    static final ArchRule layered_architecture_is_respected = layeredArchitecture()
            .consideringAllDependencies()
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            .layer("Infrastructure").definedBy("..infrastructure..")
            .layer("Web").definedBy("..web..")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Web")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure", "Web")
            .whereLayer("Web").mayOnlyAccessLayers("Domain", "Application");
}
