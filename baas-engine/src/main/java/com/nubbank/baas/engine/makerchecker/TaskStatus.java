package com.nubbank.baas.engine.makerchecker;

/** Open enum: a future EXPIRED state (TTL seam) may be appended — see spec §10. */
public enum TaskStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }
