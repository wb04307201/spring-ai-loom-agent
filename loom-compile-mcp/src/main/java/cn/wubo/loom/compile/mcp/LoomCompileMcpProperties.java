package cn.wubo.loom.compile.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "loom.compile.mcp")
public class LoomCompileMcpProperties {
 private String basePath = ".local/file";
 private String mavenHome;
 private long mavenTimeoutMs = 600000;
 private long dockerBuildTimeoutMs = 600000;
 private long dockerRunTimeoutMs = 60000;
 private long healthCheckMaxWaitMs = 60000;
 private long healthCheckIntervalMs = 2000;
 private boolean keepWorkspace = false;
 private List<String> extraRunArgs = new ArrayList<>();
 private Map<String, ImageTemplate> imageTemplates = defaultImageTemplates();

 @Data
 public static class ImageTemplate {
 private String image;
 private List<String> command;
 }

 private static Map<String, ImageTemplate> defaultImageTemplates() {
 Map<String, ImageTemplate> m = new HashMap<>();
 m.put("java17", template("eclipse-temurin:17-jre", List.of("java", "-jar")));
 m.put("java21", template("eclipse-temurin:21-jre", List.of("java", "-jar")));
 m.put("nginx", template("nginx:stable-alpine", null));
 m.put("python3", template("python:3.11-slim", List.of("python")));
 m.put("node20", template("node:20-alpine", List.of("node")));
 m.put("node20-serve", template("node:20-alpine", List.of("npx", "serve", "-s")));
 return m;
 }

 private static ImageTemplate template(String image, List<String> command) {
 ImageTemplate t = new ImageTemplate();
 t.setImage(image);
 t.setCommand(command);
 return t;
 }
}
