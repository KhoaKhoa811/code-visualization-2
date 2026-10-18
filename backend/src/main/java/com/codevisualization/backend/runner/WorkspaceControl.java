package com.codevisualization.backend.runner;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Container boundary for traced runs (Part 7c). Files are staged through
 * {@code docker exec -i} stdin, never host mounts; commands come from a fixed
 * internal vocabulary; reads are bounded and never mutate ownership state.
 */
interface WorkspaceControl {
    /** Create and start the owned idle container, then stage each file into /work. */
    void prepareWorkspace(Map<String, byte[]> files, Duration remaining) throws IOException;

    /** Docker exec arguments for a fixed command inside the running container. */
    List<String> workspaceCommand(List<String> fixedCommand) throws IOException;

    /**
     * Reads a fixed /work file. With {@code killFirst}, every process of the run
     * user except the container's init is killed before reading, so no record is
     * appended after the read starts. Failure never quarantines the container.
     */
    String readFile(String path, int limit, Duration remaining, boolean killFirst) throws IOException;

    void stop() throws IOException;
    void remove() throws IOException;
    ContainerProfile.Identity identity();
    String containerId();
}
