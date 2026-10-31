package com.prguard.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;

/** Uses a real child process with controlled output; does not represent a live malware scan. */
public final class GuardDogFixtureClient extends GuardDogCliClient {
    private final String mode;

    public GuardDogFixtureClient(GuardDogProperties props, ObjectMapper mapper, String mode) {
        super(props, mapper);
        this.mode = mode;
    }

    @Override Process start(ProcessBuilder requested) throws IOException {
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classes = Path.of(GuardDogFixtureProcess.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
            return new ProcessBuilder(java, "-cp", classes, GuardDogFixtureProcess.class.getName(), mode)
                    .directory(requested.directory()).start();
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }
}
