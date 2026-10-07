package cn.wubo.loom.http.core.invoke;

public class HeaderResolutionException extends RuntimeException {
    public HeaderResolutionException(String msg) { super(msg); }
    public HeaderResolutionException(String msg, Throwable cause) { super(msg, cause); }
}