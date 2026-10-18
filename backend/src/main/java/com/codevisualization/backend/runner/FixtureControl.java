package com.codevisualization.backend.runner;

import java.io.IOException;
import java.util.List;

/** Container ownership boundary used by the fixed-fixture coordinator. */
interface FixtureControl {
    void prepare(FixedFixture fixture) throws IOException;
    List<String> phaseCommand(FixedFixture fixture, boolean compilation) throws IOException;
    void stop() throws IOException;
    void remove() throws IOException;
    ContainerProfile.Identity identity();
    String containerId();
}
