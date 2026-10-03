package cn.wubo.loom.http.core.endpoint;

import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.system.SystemService;
import cn.wubo.loom.http.core.util.JsonMappers;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD over the manual endpoints stored inside System.endpoints.
 * Persists changes by delegating to SystemService.
 */
public class EndpointService {
    private final SystemService systemService;

    public EndpointService(SystemService systemService) {
        this.systemService = systemService;
    }

    public Endpoint add(System system, Endpoint endpoint) {
        if (endpoint.getLastModified() == null) {
            endpoint.setLastModified(Instant.now());
        }
        system.getEndpoints().add(endpoint);
        systemService.save(system);
        return endpoint;
    }

    public Endpoint update(System system, String method, String path, Map<String, Object> patch) {
        Endpoint existing = findByMethodAndPath(system, method, path)
            .orElseThrow(() -> new EndpointNotFoundException(method, path));
        try {
            String patchJson = JsonMappers.toJson(patch);
            JsonMappers.mapper().readerForUpdating(existing).readValue(patchJson);
        } catch (IOException e) {
            throw new RuntimeException("Failed to apply endpoint patch", e);
        }
        existing.setLastModified(Instant.now());
        systemService.save(system);
        return existing;
    }

    public void remove(System system, String method, String path) {
        boolean removed = system.getEndpoints().removeIf(
            e -> method.equalsIgnoreCase(e.getMethod()) && path.equals(e.getPath()));
        if (!removed) throw new EndpointNotFoundException(method, path);
        systemService.save(system);
    }

    public List<Endpoint> listManual(System system) {
        return new ArrayList<>(system.getEndpoints());
    }

    private Optional<Endpoint> findByMethodAndPath(System system, String method, String path) {
        return system.getEndpoints().stream()
            .filter(e -> method.equalsIgnoreCase(e.getMethod()) && path.equals(e.getPath()))
            .findFirst();
    }
}
