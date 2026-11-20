package com.example.customer_retail_system.system;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import org.springframework.modulith.core.ApplicationModules;

/** Enforces module boundaries (CLAUDE.md "Modules"). */
class ModularityTest {

	static final ApplicationModules MODULES = ApplicationModules.of(SystemApplication.class);

	@Test
	void verifiesModuleStructure() {
		MODULES.verify();
	}

	@Test
	void portalNeverDependsOnGatewayMock() {
		JavaClasses portal = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.example.customer_retail_system.system");
		noClasses().should().dependOnClassesThat()
				.resideInAPackage("com.example.customer_retail_system.gateway.mock..")
				.check(portal);
	}
}
