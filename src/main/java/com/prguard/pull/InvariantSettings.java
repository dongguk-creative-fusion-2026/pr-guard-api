package com.prguard.pull;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Shared request/response contract for project defaults and PR-specific drafts. */
public record InvariantSettings(@NotNull @Size(max = 4000) String rules) {}
