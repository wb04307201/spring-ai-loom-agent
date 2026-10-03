package cn.wubo.loom.http.core.invoke;

public class ExtractionException extends RuntimeException {
    public ExtractionException(String msg) { super(msg); }
    public ExtractionException(String msg, Throwable cause) { super(msg, cause); }
}