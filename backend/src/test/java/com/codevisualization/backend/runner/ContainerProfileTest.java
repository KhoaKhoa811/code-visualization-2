package com.codevisualization.backend.runner;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ContainerProfileTest {
    @Test void pinMatchesRunnerAndCreateArgumentsAreImmutable() throws Exception {
        var pin = JsonMapper.shared().readTree(Path.of("../runner/image.json").toFile());
        assertEquals(pin.path("image").asString(), ContainerProfile.IMAGE);
        var args = ContainerProfile.createArguments(ContainerProfile.Identity.fresh());
        assertThrows(UnsupportedOperationException.class, () -> args.add("--privileged"));
        assertTrue(args.contains("--pull=never"));
        assertEquals(pin.path("platform").asString(), args.get(args.indexOf("--platform") + 1));
    }
}
