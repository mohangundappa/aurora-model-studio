package com.aurora.studio.app;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ModuleArchitectureTest {
  private static final String ROOT = "com.aurora.studio.";
  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.aurora.studio");

  @Test
  void modulesFollowTheDependencyDirection() {
    layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("common")
        .definedBy(ROOT + "common..")
        .layer("gateway")
        .definedBy(ROOT + "gateway..")
        .layer("knowledge")
        .definedBy(ROOT + "knowledge..")
        .layer("importer")
        .definedBy(ROOT + "importer..")
        .layer("extraction")
        .definedBy(ROOT + "extraction..")
        .layer("discovery")
        .definedBy(ROOT + "discovery..")
        .layer("initiative")
        .definedBy(ROOT + "initiative..")
        .layer("app")
        .definedBy(ROOT + "app..")
        .whereLayer("common")
        .mayNotAccessAnyLayer()
        .whereLayer("gateway")
        .mayOnlyAccessLayers("common")
        .whereLayer("knowledge")
        .mayOnlyAccessLayers("common")
        .whereLayer("importer")
        .mayOnlyAccessLayers("common", "knowledge")
        .whereLayer("extraction")
        .mayOnlyAccessLayers("common", "knowledge", "gateway")
        .whereLayer("discovery")
        .mayOnlyAccessLayers("common", "knowledge", "gateway")
        .whereLayer("initiative")
        .mayOnlyAccessLayers("common", "knowledge", "gateway", "discovery")
        .whereLayer("app")
        .mayNotBeAccessedByAnyLayer()
        .check(CLASSES);
  }

  @Test
  void modulesHaveNoCycles() {
    slices().matching(ROOT + "(*)..").should().beFreeOfCycles().check(CLASSES);
  }

  @TestFactory
  Stream<DynamicTest> repositoriesAreInternalToTheirModule() {
    return Stream.of(
            "common",
            "gateway",
            "knowledge",
            "importer",
            "extraction",
            "discovery",
            "initiative",
            "app")
        .map(
            module ->
                dynamicTest(
                    module + " owns its repositories",
                    () ->
                        ArchRuleDefinition.noClasses()
                            .that()
                            .resideOutsideOfPackage(ROOT + module + "..")
                            .should()
                            .dependOnClassesThat()
                            .haveNameMatching(
                                "com\\.aurora\\.studio\\." + module + "\\..*Repository(\\$.*)?")
                            .check(CLASSES)));
  }

  @Test
  void importerUsesKnowledgeContractsInsteadOfJdbc() {
    ArchRuleDefinition.noClasses()
        .that()
        .resideInAPackage(ROOT + "importer..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("java.sql..", "org.springframework.jdbc..")
        .check(CLASSES);
  }
}
