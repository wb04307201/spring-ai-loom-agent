package cn.wubo.loom.http.core.endpoint;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.system.SystemService;
import cn.wubo.loom.http.core.util.JsonMappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class EndpointServiceTest {
    @TempDir Path tmp;
    SystemService systemService;
    EndpointService endpointService;
    System system;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("systems"));
        HttpStorage sc = new HttpStorage(tmp);
        systemService = new SystemService(sc, new HttpConfig());
        endpointService = new EndpointService(systemService);
        system = new System();
        system.setName("svc");
        system.setBaseUrl("https://s.example.com");
        systemService.save(system);
    }

    @Test
    void addAppendsToSystem() {
        Endpoint e = new Endpoint();
        e.setMethod("POST");
        e.setPath("/foo");
        endpointService.add(system, e);

        assertThat(system.getEndpoints()).hasSize(1);
        assertThat(system.getEndpoints().get(0).getPath()).isEqualTo("/foo");
        assertThat(system.getEndpoints().get(0).getMethod()).isEqualTo("POST");
    }

    @Test
    void addSetsLastModified() throws Exception {
        Instant before = Instant.now();
        Endpoint e = new Endpoint();
        e.setMethod("GET");
        e.setPath("/foo");
        endpointService.add(system, e);
        Instant after = Instant.now();

        assertThat(e.getLastModified()).isBetween(before, after);
    }

    @Test
    void updatePartialChangesOnlyPatchedFields() {
        Endpoint e = new Endpoint();
        e.setMethod("GET");
        e.setPath("/foo");
        e.setSummary("Original");
        endpointService.add(system, e);

        Map<String, Object> patch = new HashMap<>();
        patch.put("summary", "Updated");
        patch.put("description", "New desc");

        Endpoint updated = endpointService.update(system, "GET", "/foo", patch);

        assertThat(updated.getSummary()).isEqualTo("Updated");
        assertThat(updated.getDescription()).isEqualTo("New desc");
        assertThat(updated.getMethod()).isEqualTo("GET");
        assertThat(updated.getPath()).isEqualTo("/foo");
    }

    @Test
    void updateRefreshesLastModified() throws Exception {
        Endpoint e = new Endpoint();
        e.setMethod("GET");
        e.setPath("/foo");
        e.setLastModified(Instant.EPOCH);
        endpointService.add(system, e);

        Map<String, Object> patch = Map.of("summary", "updated");
        Endpoint updated = endpointService.update(system, "GET", "/foo", patch);

        assertThat(updated.getLastModified()).isAfter(Instant.EPOCH);
    }

    @Test
    void updateMissingThrows() {
        assertThatThrownBy(() -> endpointService.update(system, "GET", "/nope", Map.of()))
            .isInstanceOf(EndpointNotFoundException.class);
    }

    @Test
    void removeDeletes() {
        Endpoint e = new Endpoint();
        e.setMethod("DELETE");
        e.setPath("/foo");
        endpointService.add(system, e);

        endpointService.remove(system, "DELETE", "/foo");

        assertThat(system.getEndpoints()).isEmpty();
    }

    @Test
    void removeMissingThrows() {
        assertThatThrownBy(() -> endpointService.remove(system, "GET", "/nope"))
            .isInstanceOf(EndpointNotFoundException.class);
    }

    @Test
    void removeIsMethodCaseInsensitive() {
        Endpoint e = new Endpoint();
        e.setMethod("GET");
        e.setPath("/foo");
        endpointService.add(system, e);

        endpointService.remove(system, "get", "/foo");

        assertThat(system.getEndpoints()).isEmpty();
    }

    @Test
    void listManualReturnsAll() {
        Endpoint e1 = new Endpoint();
        e1.setMethod("GET");
        e1.setPath("/a");
        Endpoint e2 = new Endpoint();
        e2.setMethod("POST");
        e2.setPath("/b");
        endpointService.add(system, e1);
        endpointService.add(system, e2);

        List<Endpoint> result = endpointService.listManual(system);
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Endpoint::getPath).contains("/a", "/b");
    }

    @Test
    void listManualOnEmptySystemReturnsEmpty() {
        assertThat(endpointService.listManual(system)).isEmpty();
    }

    @Test
    void endpointJsonRoundTripsThroughSystem() {
        Endpoint e = new Endpoint();
        e.setMethod("POST");
        e.setPath("/internal/notify");
        e.setSummary("Internal notify");
        endpointService.add(system, e);

        systemService.reload();
        System reloaded = systemService.get("svc");
        assertThat(reloaded.getEndpoints()).hasSize(1);
        assertThat(reloaded.getEndpoints().get(0).getPath()).isEqualTo("/internal/notify");
        assertThat(reloaded.getEndpoints().get(0).getMethod()).isEqualTo("POST");
    }

    @Test
    void systemJsonContainingFullEndpointDeserializes() {
        String json = """
            {
              "name": "svc",
              "baseUrl": "https://s.example.com",
              "endpoints": [
                {
                  "method": "GET",
                  "path": "/users/{id}",
                  "summary": "Get user by ID",
                  "parameters": [
                    {"name":"id","in":"path","required":true,"schema":{"type":"string"}}
                  ],
                  "responses": {
                    "200": {"description":"OK"}
                  },
                  "lastModified": "2026-08-22T10:30:00Z"
                }
              ]
            }
            """;
        System loaded = JsonMappers.parse(json, System.class);
        assertThat(loaded.getEndpoints()).hasSize(1);
        Endpoint ep = loaded.getEndpoints().get(0);
        assertThat(ep.getMethod()).isEqualTo("GET");
        assertThat(ep.getPath()).isEqualTo("/users/{id}");
        assertThat(ep.getParameters()).hasSize(1);
        assertThat(ep.getParameters().get(0).getName()).isEqualTo("id");
        assertThat(ep.getParameters().get(0).isRequired()).isTrue();
        assertThat(ep.getResponses()).containsKey("200");
        assertThat(ep.getResponses().get("200").getDescription()).isEqualTo("OK");
        assertThat(ep.getLastModified()).isNotNull();
    }
}
