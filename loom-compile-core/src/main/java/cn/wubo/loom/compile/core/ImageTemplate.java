package cn.wubo.loom.compile.core;

import java.util.List;

/**
 * 基础镜像模板。
 *
 * @param image   完整镜像名（如 "eclipse-temurin:17-jre-alpine"）
 * @param command exec 形式启动命令（如 ["java", "-jar", "app.jar"]）
 */
public record ImageTemplate(String image, List<String> command) {
}
