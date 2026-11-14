package com.daimon.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime settings needed to instantiate DAIMons.
 *
 * @param home         daemon home; workspaces live in {@code home/workspaces}
 * @param geminiAPIKey Gemini API key, or {@code null} to use the offline talk executor
 * @param geminiVersion Gemini model id, e.g. {@code gemini-2.5-flash}
 */
public record DAIMonRuntimeConfig(Path home, String geminiAPIKey, String geminiVersion) {

    public static final String DEFAULT_GEMINI_VERSION = "gemini-2.5-flash";

    public DAIMonRuntimeConfig {
        Objects.requireNonNull(home, "home");
        if (geminiAPIKey != null && geminiAPIKey.isBlank()) {
            geminiAPIKey = null;
        }
        geminiVersion = (geminiVersion == null || geminiVersion.isBlank()) ? DEFAULT_GEMINI_VERSION : geminiVersion;
    }

    /** Reads {@code DAIMON_HOME}, {@code GEMINI_API_KEY} and {@code DAIMON_GEMINI_MODEL}. */
    public static DAIMonRuntimeConfig fromEnvironment() {
        String home = System.getenv("DAIMON_HOME");
        return new DAIMonRuntimeConfig(
                home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".daimon"),
                System.getenv("GEMINI_API_KEY"),
                System.getenv("DAIMON_GEMINI_MODEL"));
    }

    public Path workspacesRoot() {
        return home.resolve("workspaces");
    }

    public Optional<String> geminiKey() {
        return Optional.ofNullable(geminiAPIKey);
    }

    public boolean geminiEnabled() {
        return geminiAPIKey != null;
    }

    @Override
    public String toString() {
        // Never print the key.
        return "DAIMonRuntimeConfig[home=" + home + ", gemini=" + (geminiEnabled() ? geminiVersion : "disabled") + "]";
    }
}
