package com.setu.shared.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches an application's configuration from the Setu config server and hands it to Spring as one property source. The values rank where
 * an imported file ranks: above the application's own packaged files, below environment variables and the command line, so an operator can
 * always override what the server says.
 * <p>
 * <b>Failure is not all alike.</b> A server that cannot be reached, or does not know the application, is what {@code optional:} exists
 * for: the application starts on what it already has, with a warning. A refused token (401/403) is a misconfiguration that would otherwise
 * make an application quietly start without its secrets, so it fails start-up even when the import is optional. The token is sent only as
 * a bearer header and is never logged; a plain-http server on a non-loopback host is logged as a warning, because the token and the
 * secrets would cross the network in the clear.
 */
public class SetuConfigDataLoader implements ConfigDataLoader<SetuConfigDataResource> {

    private static final int TIMEOUT_MILLIS = 3000;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Log log;

    public SetuConfigDataLoader(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(SetuConfigDataLoader.class);
    }

    @Override
    public ConfigData load(ConfigDataLoaderContext context, SetuConfigDataResource resource) throws IOException, ConfigDataResourceNotFoundException {
        warnIfCleartextAcrossTheNetwork(resource);
        URI target = URI.create(stripTrailingSlash(resource.server().toString()) + "/config/" + encode(resource.application()) + "/"
            + encode(resource.profiles().isEmpty() ? "default" : String.join(",", resource.profiles())));

        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json");
            if (!resource.token().isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + resource.token());
            }
            int status;
            try {
                status = connection.getResponseCode();
            } catch (ConnectException | java.net.SocketTimeoutException | java.net.UnknownHostException e) {
                log.warn("config server " + resource.server() + " is not reachable (" + e.getClass().getSimpleName() + ")");
                throw new ConfigDataResourceNotFoundException(resource, e);
            }
            if (status == 401 || status == 403) {
                throw new IllegalStateException("the config server refused " + (status == 401 ? "the token (or none was sent)" : "this application's token")
                    + " for '" + resource.application() + "': set setu.config.token / SETU_CONFIG_TOKEN to the token configured for it");
            }
            if (status == 404) {
                throw new ConfigDataResourceNotFoundException(resource);
            }
            if (status != 200) {
                log.warn("config server " + resource.server() + " answered " + status + " for '" + resource.application() + "'");
                throw new ConfigDataResourceNotFoundException(resource);
            }
            JsonNode body = readJson(connection.getInputStream());
            Map<String, Object> properties = new LinkedHashMap<>();
            body.path("properties").fields().forEachRemaining(e -> properties.put(e.getKey(), e.getValue().asText()));
            String name = "setu-config[" + resource.application() + resource.profiles() + "@" + body.path("version").asText("?") + "]";
            log.info("loaded " + properties.size() + " properties for '" + resource.application() + "' from the config server (version "
                + body.path("version").asText("?") + ")");
            return new ConfigData(List.of(new MapPropertySource(name, properties)));
        } finally {
            connection.disconnect();
        }
    }

    private void warnIfCleartextAcrossTheNetwork(SetuConfigDataResource resource) {
        String host = resource.server().getHost();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if ("http".equals(resource.server().getScheme()) && !loopback) {
            log.warn("the config server " + resource.server() + " is reached over plain http: the token and every secret cross the network unencrypted; use https");
        }
    }

    private static JsonNode readJson(InputStream in) throws IOException {
        try (in) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("config server response larger than " + MAX_BYTES + " bytes");
            }
            return MAPPER.readTree(bytes);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%2C", ",");
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
