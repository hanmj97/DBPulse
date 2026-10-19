package com.dbpulse.service;

import java.util.EnumMap;

/** Consecutive-sample confirmation, not a determination of root cause. */
public final class IncidentSignalTracker {
    public enum Type { BLOCKING, LONG_RUNNING, COLLECTION_FAILURE }
    public enum Action { NONE, OPEN, RESOLVE }
    private final EnumMap<Type, Integer> positives = new EnumMap<>(Type.class);
    private final EnumMap<Type, Integer> negatives = new EnumMap<>(Type.class);

    public Action observe(Type type, boolean problem) {
        if (problem) {
            negatives.put(type, 0);
            int consecutive = Math.min(2, positives.getOrDefault(type, 0) + 1);
            positives.put(type, consecutive);
            return consecutive >= 2 ? Action.OPEN : Action.NONE;
        }
        positives.put(type, 0);
        int consecutive = Math.min(2, negatives.getOrDefault(type, 0) + 1);
        negatives.put(type, consecutive);
        return consecutive >= 2 ? Action.RESOLVE : Action.NONE;
    }
}
