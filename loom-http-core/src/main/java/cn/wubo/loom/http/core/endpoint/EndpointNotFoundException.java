package cn.wubo.loom.http.core.endpoint;

public class EndpointNotFoundException extends RuntimeException {
    public EndpointNotFoundException(String method, String path) {
        super("Endpoint not found: " + method + " " + path);
    }
}
