package com.p2pclient.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Loads configuration with a preference for an external file next to the packaged app
 * (e.g., <installDir>/config/config.properties) while falling back to the classpath resource.
 */
public final class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

    private ConfigLoader() {}

    public static Properties load(String fileName) {
        Properties props = new Properties();

        Path external = findExternalConfig(fileName);
        if (external != null && Files.exists(external)) {
            try (InputStream is = Files.newInputStream(external)) {
                props.load(is);
                logger.info("Loaded config from {}", external);
                return props;
            } catch (IOException e) {
                logger.warn("Failed to load external config at {}. Falling back to classpath.", external, e);
            }
        }

        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName)) {
            if (is != null) {
                props.load(is);
                logger.info("Loaded config from classpath resource {}", fileName);
            } else {
                logger.warn("Config resource {} not found on classpath", fileName);
            }
        } catch (IOException e) {
            logger.warn("Could not load config from classpath", e);
        }
        return props;
    }

    private static Path findExternalConfig(String fileName) {
        // 1) System property override: -Dgview.config=<file or dir>
        String sysProp = System.getProperty("gview.config");
        if (sysProp != null && !sysProp.isBlank()) {
            Path p = Paths.get(sysProp);
            if (Files.isDirectory(p)) {
                p = p.resolve(fileName);
            }
            return p;
        }

        // 2) Directory containing the running JAR/exe (app image)
        try {
            Path jarDir = Paths.get(ConfigLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            if (jarDir != null) {
                Path candidate = jarDir.resolve("config").resolve(fileName);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        } catch (URISyntaxException e) {
            logger.debug("Unable to resolve jar directory for config lookup", e);
        }

        // 3) Working directory ./config
        Path cwdCandidate = Paths.get("").toAbsolutePath().resolve("config").resolve(fileName);
        if (Files.exists(cwdCandidate)) {
            return cwdCandidate;
        }

        return null;
    }
}
