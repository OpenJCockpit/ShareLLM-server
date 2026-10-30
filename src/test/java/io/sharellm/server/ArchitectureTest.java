package io.sharellm.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the clean-architecture dependency rule: dependencies only point inwards. */
@AnalyzeClasses(packages = "io.sharellm.server", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule layersOnlyDependInwards = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            .layer("InboundAdapters").definedBy("..adapter.inbound..")
            .layer("OutboundAdapters").definedBy("..adapter.outbound..")
            .layer("Infrastructure").definedBy("..infrastructure..")
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
            .whereLayer("InboundAdapters").mayNotBeAccessedByAnyLayer()
            .whereLayer("OutboundAdapters").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("InboundAdapters", "OutboundAdapters", "Infrastructure")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers(
                    "Application", "InboundAdapters", "OutboundAdapters", "Infrastructure");

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages("..domain..", "java..");

    @ArchTest
    static final ArchRule applicationIsFrameworkFree = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideOutsideOfPackages("..application..", "..domain..", "java..");

    @ArchTest
    static final ArchRule inboundAdaptersUseOnlyInboundPorts = noClasses().that().resideInAPackage("..adapter.inbound..")
            .should().dependOnClassesThat().resideInAnyPackage("..application.port.outbound..", "..application.service..");
}
