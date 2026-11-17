package ma.nuzul;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** ADR-1: a module reaching into another module's internals fails the build. */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(NuzulApiApplication.class);

    @Test
    void modulesRespectBoundaries() {
        MODULES.verify();
    }

    @Test
    void writeModuleDocs() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
