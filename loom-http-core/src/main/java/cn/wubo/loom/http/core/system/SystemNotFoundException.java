package cn.wubo.loom.http.core.system;

public class SystemNotFoundException extends RuntimeException {
    public SystemNotFoundException(String name) {
        super("System not found: " + name);
    }
}
