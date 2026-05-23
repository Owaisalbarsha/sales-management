// com/salesmanagement/ModularStructureTest.java
package com.salesmanagement;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularStructureTest {

    @Test
    void verifyModularStructure() {
        ApplicationModules.of(SalesManagementApplication.class).verify();
    }
}