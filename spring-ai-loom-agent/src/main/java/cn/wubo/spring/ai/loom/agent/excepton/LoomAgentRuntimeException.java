package cn.wubo.spring.ai.loom.agent.excepton;

public class LoomAgentRuntimeException extends RuntimeException {
    private final Integer statusCode;

    public LoomAgentRuntimeException(String message) {
        super(message);
        this.statusCode = null;
    }

    public LoomAgentRuntimeException(Throwable cause) {
        super(cause);
        this.statusCode = null;
    }

    public LoomAgentRuntimeException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * Optional explicit HTTP status code associated with this exception.
     */
    public Integer getStatusCode() {
        return statusCode;
    }
}
