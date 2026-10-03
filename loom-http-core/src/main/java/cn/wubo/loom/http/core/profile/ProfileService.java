package cn.wubo.loom.http.core.profile;

import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpStorage;
import cn.wubo.loom.http.core.util.JsonMappers;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class ProfileService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProfileService.class);

    private final HttpStorage storage;
    private final HttpConfig global;
    /**
     * Validator instance. Held as a field (not a constructor parameter) so
     * Spring can use the single-arg constructor and so existing tests that
     * wire ProfileService manually keep working without modification.
     */
    private final ProfileValidator validator = new ProfileValidator();
    private final Map<String, Profile> cache = new ConcurrentHashMap<>();

    public ProfileService(HttpStorage storage, HttpConfig global) {
        this.storage = storage;
        this.global = global;
        reload();
    }

    public Profile get(String name) {
        Profile p = cache.get(name);
        if (p == null) throw new ProfileNotFoundException(name);
        return p;
    }

    public List<Profile> listAll() { return new ArrayList<>(cache.values()); }

    public Profile save(Profile p) {
        try {
            Files.createDirectories(storage.profilesDir());
            Path file = storage.profilesDir().resolve(p.getName() + ".json");
            String json = JsonMappers.toJson(p);
            Files.writeString(file, json);
            try {
                Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {}
            cache.put(p.getName(), p);
            return p;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save profile " + p.getName(), e);
        }
    }

    public void delete(String name) {
        try {
            Files.deleteIfExists(storage.profilesDir().resolve(name + ".json"));
            cache.remove(name);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete profile " + name, e);
        }
    }

    public void reload() {
        cache.clear();
        Path dir = storage.profilesDir();
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(this::loadFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to list profiles", e);
        }
    }

    private void loadFile(Path file) {
        try {
            Profile p = JsonMappers.parse(Files.readString(file), Profile.class);
            // Soft validation on load: errors are logged but never block
            // (we want existing files to keep working even if they were
            // authored under an older rule set). New writes go through the
            // strict path in ProfileTools / SystemTools.
            for (ProfileValidator.ValidationError f : validator.validate(p)) {
                log.warn("Profile '{}' at {}: {}", p.getName(), file, f);
            }
            cache.put(p.getName(), p);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load profile " + file, e);
        }
    }
}
