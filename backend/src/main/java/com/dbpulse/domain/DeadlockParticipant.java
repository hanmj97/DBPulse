package com.dbpulse.domain;

/** A session participating in the configured database; no SQL text or user names. */
public record DeadlockParticipant(int sessionId, boolean victim) {}
