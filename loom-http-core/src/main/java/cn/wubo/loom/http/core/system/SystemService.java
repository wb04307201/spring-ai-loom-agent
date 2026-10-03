package cn.wubo.loom.http.core.system;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.util.JsonMappers;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class SystemService {
    private final HttpStorage storage;
    private final HttpConfig global;
    private final Map<String, System> cache = new ConcurrentHashMap<>();

    public SystemService(HttpStorage storage, HttpConfig global) {
        this.storage = storage;
        this.global = global;
        reload();
    }

    public System get(String name) {
        System s = cache.get(name);
        if (s == null) throw new SystemNotFoundException(name);
        return s;
    }

    public List<System> listAll() { return new ArrayList<>(cache.values()); }

    public System save(System s) {
        try {
            Files.createDirectories(storage.systemsDir());
            Path file = storage.systemsDir().resolve(s.getName() + ".json");
            String json = JsonMappers.toJson(s);
            Files.writeString(file, json);
            try {
                Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {}
            cache.put(s.getName(), s);
            return s;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save system " + s.getName(), e);
        }
    }

    public void delete(String name) {
        try {
            Files.deleteIfExists(storage.systemsDir().resolve(name + ".json"));
            cache.remove(name);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete system " + name, e);
        }
    }

    public void reload() {
        cache.clear();
        Path dir = storage.systemsDir();
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(this::loadFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to list systems", e);
        }
    }

    private void loadFile(Path file) {
        try {
            System s = JsonMappers.parse(Files.readString(file), System.class);
            cache.put(s.getName(), s);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load system " + file, e);
        }
    }
}
