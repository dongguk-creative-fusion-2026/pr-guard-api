package com.prguard.analysis;

import com.fasterxml.jackson.databind.JsonNode;

/** Registry package only: implementations must not install or execute dependency code. */
public interface GuardDogClient {
    JsonNode scan(String ecosystem, String name, String version, java.time.Duration timeout);
}
