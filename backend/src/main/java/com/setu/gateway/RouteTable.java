package com.setu.gateway;

import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Maps a request path to the route that owns it. Every route lists the patterns it owns; when several routes match, the most specific
 * pattern wins (Spring's own specificity order, so {@code /auth/login} beats {@code /auth/**} beats {@code /**}), independent of the order
 * the routes are written in.
 */
public class RouteTable {

    /** @param isPublic true when the matching request needs no token at the gateway */
    public record Match(Route route, boolean isPublic) {
    }

    public record Route(String id, String service, Integer ratePerMinute, boolean websocket, java.time.Duration responseTimeout) {
    }

    private record Entry(PathPattern pattern, Route route) {
    }

    private final List<Entry> owned = new ArrayList<>();
    private final List<Entry> publicOwned = new ArrayList<>();

    public RouteTable(List<GatewayProperties.RouteProperties> routes) {
        PathPatternParser parser = new PathPatternParser();
        for (GatewayProperties.RouteProperties properties : routes) {
            Route route = new Route(properties.id(), properties.service(), properties.ratePerMinute(), properties.websocket(), properties.responseTimeout());
            for (String path : properties.paths()) {
                owned.add(new Entry(parser.parse(path), route));
            }
            for (String path : properties.publicPaths()) {
                publicOwned.add(new Entry(parser.parse(path), route));
            }
        }
    }

    public Optional<Match> match(String path) {
        var container = org.springframework.http.server.PathContainer.parsePath(path);
        Optional<Entry> best = owned.stream()
            .filter(e -> e.pattern().matches(container))
            .min(Comparator.comparing(Entry::pattern, PathPattern.SPECIFICITY_COMPARATOR));
        return best.map(entry -> {
            boolean isPublic = publicOwned.stream()
                .anyMatch(p -> p.route() == entry.route() && p.pattern().matches(container));
            return new Match(entry.route(), isPublic);
        });
    }
}
