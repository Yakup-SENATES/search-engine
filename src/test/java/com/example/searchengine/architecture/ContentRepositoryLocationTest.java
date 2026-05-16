package com.example.searchengine.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit tests enforcing that the ContentRepository interface lives in the domain layer
 * and that controllers do not return Content domain objects directly (they must use DTOs).
 *
 * Validates: Requirements 22.3, 22.5
 */
@AnalyzeClasses(packages = "com.example.searchengine", importOptions = ImportOption.DoNotIncludeTests.class)
class ContentRepositoryLocationTest {

    /**
     * REQ 22.3 — The ContentRepository interface must reside in the domain.content package.
     */
    @ArchTest
    static final ArchRule content_repository_interface_resides_in_domain = classes()
            .that().haveSimpleName("ContentRepository")
            .should().resideInAPackage("..domain.content..");

    /**
     * REQ 22.5 — Controllers in the web layer must not directly depend on the Content domain
     * aggregate class. They should use DTO classes for HTTP request and response payloads.
     * This ensures a clean separation between domain model and API contract.
     */
    @ArchTest
    static final ArchRule controllers_do_not_depend_on_content_entity = noClasses()
            .that().resideInAPackage("..web..")
            .and().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat(
                    DescribedPredicate.describe(
                            "are the Content domain class",
                            (JavaClass javaClass) -> javaClass.getSimpleName().equals("Content")
                                    && javaClass.getPackageName().contains("domain.content")));
}
