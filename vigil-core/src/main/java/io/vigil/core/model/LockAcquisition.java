package io.vigil.core.model;

import java.util.UUID;

public record LockAcquisition(long fencingToken, UUID runId) {}
