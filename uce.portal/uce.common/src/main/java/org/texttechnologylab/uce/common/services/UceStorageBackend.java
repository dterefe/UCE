package org.texttechnologylab.uce.common.services;

import java.util.Locale;

public enum UceStorageBackend {
    POSTGRES,
    DUA;

    public static final String ENV_NAME = "UCE_STORAGE_BACKEND";
    public static final String PROPERTY_NAME = "uce.storage.backend";

    public static UceStorageBackend configured() {
        String raw = System.getProperty(PROPERTY_NAME);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(ENV_NAME);
        }
        if (raw == null || raw.isBlank()) {
            return POSTGRES;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "dua" -> DUA;
            case "postgres", "postgresql", "pg" -> POSTGRES;
            default -> throw new IllegalArgumentException("Unsupported " + ENV_NAME + " value: " + raw);
        };
    }
}
