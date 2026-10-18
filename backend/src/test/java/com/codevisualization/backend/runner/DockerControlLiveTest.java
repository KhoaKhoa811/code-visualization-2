package com.codevisualization.backend.runner;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: creates one restricted idle container using the local pinned image. */
@EnabledIfSystemProperty(named = "codeviz.docker.live", matches = "true")
class DockerControlLiveTest {
    @Test void createsStartsStopsAndVerifiesRemoval() throws Exception {
        Path docker = Path.of(System.getProperty("codeviz.docker.executable"));
        try (var runner = new BoundedCommandRunner()) {
            var adapter = new DockerControlAdapter(docker, runner);
            System.out.println("Owned live probe: " + adapter.identity().name());
            try {
                adapter.create();
                adapter.startIdle();
                assertNotNull(adapter.containerId());
            } finally {
                if (adapter.containerId() != null && !adapter.quarantined()) {
                    adapter.stop();
                    adapter.remove();
                }
            }
            assertFalse(adapter.quarantined(), "Unresolved ownership must be reconciled explicitly");
        }
    }
}
