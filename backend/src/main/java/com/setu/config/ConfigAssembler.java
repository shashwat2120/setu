package com.setu.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Builds the configuration one application receives for a set of profiles, from files on disk.
 * <p>
 * Layers, lowest to highest precedence, first from the configuration directory and then, on top of it, from the secrets directory (same
 * names, so a secret can override a default and a missing file is simply skipped):
 * <ol>
 *   <li>{@code application.yml}: shared by every application</li>
 *   <li>{@code application-<profile>.yml}: shared, for each active profile in the order given</li>
 *   <li>{@code <application>.yml}</li>
 *   <li>{@code <application>-<profile>.yml}</li>
 * </ol>
 * Each file is one YAML document of nested keys (no multi-document files or profile activation inside a file: profiles are file names).
 * Values are returned as the strings they are; {@code ${...}} placeholders are left for the receiving application to resolve against its own
 * environment, so the server never needs to know a deployment's environment variables.
 * <p>
 * Application and profile names are checked against a strict pattern before they touch a file path, so a name can never reach outside the
 * two directories.
 */
public class ConfigAssembler {

    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,40}");

    /** @param version a short fingerprint of the properties (never of anything else), so a client can tell whether anything changed */
    public record Assembled(String application, List<String> profiles, Map<String, String> properties, String version, List<String> sources) {
    }

    private final Path configDir;
    private final Path secretsDir;

    public ConfigAssembler(Path configDir, Path secretsDir) {
        this.configDir = configDir;
        this.secretsDir = secretsDir;
    }

    public Assembled assemble(String application, List<String> profiles) {
        if (!NAME.matcher(application).matches()) {
            throw new IllegalArgumentException("not a valid application name");
        }
        for (String profile : profiles) {
            if (!NAME.matcher(profile).matches()) {
                throw new IllegalArgumentException("not a valid profile name");
            }
        }
        List<String> names = new ArrayList<>();
        names.add("application");
        profiles.forEach(p -> names.add("application-" + p));
        names.add(application);
        profiles.forEach(p -> names.add(application + "-" + p));

        Map<String, String> merged = new TreeMap<>();
        List<String> sources = new ArrayList<>();
        for (Path root : new Path[] {configDir, secretsDir}) {
            for (String name : names) {
                Path file = root.resolve(name + ".yml").normalize();
                if (file.startsWith(root.normalize()) && Files.isRegularFile(file)) {
                    load(file).forEach((k, v) -> merged.put(k, v));
                    sources.add((root == secretsDir ? "secrets/" : "config/") + name + ".yml");
                }
            }
        }
        return new Assembled(application, List.copyOf(profiles), merged, fingerprint(merged), List.copyOf(sources));
    }

    private static Map<String, String> load(Path file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(file));
        Properties properties = yaml.getObject();
        Map<String, String> out = new TreeMap<>();
        if (properties != null) {
            properties.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        }
        return out;
    }

    private static String fingerprint(Map<String, String> properties) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            properties.forEach((k, v) -> {
                digest.update(k.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(v.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            });
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
