package ma.nuzul;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** ADR-2: domain code is pure Java; the domain never depends on adapters. */
@AnalyzeClasses(packages = "ma.nuzul", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainHasNoFrameworkDependencies = noClasses()
            .that()
            .resideInAPackage("..domain..")
            // package-info only carries module metadata (@NamedInterface); it holds no domain code.
            .and()
            .doNotHaveSimpleName("package-info")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainDoesNotDependOnAdapters = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .allowEmptyShould(true);
}
