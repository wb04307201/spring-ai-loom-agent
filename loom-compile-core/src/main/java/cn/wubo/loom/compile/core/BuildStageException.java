package cn.wubo.loom.compile.core;

/**
 * Thrown by build-stage implementations (e.g. Maven, npm) when the underlying
 * build command exits with a non-zero status.
 * <p>
 * Unlike a plain {@link RuntimeException}, this exception carries the captured
 * build output (stdout + stderr) so the caller can surface the actual compiler /
 * dependency errors to the LLM instead of returning a generic "Build failed"
 * message that leaves it with no diagnostic information.
 */
public class BuildStageException extends RuntimeException {

 @java.io.Serial
 private static final long serialVersionUID = 1L;

 /** Captured build output (stdout + stderr). May be {@code null} if the process produced none. */
 private final String buildOutput;

 public BuildStageException(String message, String buildOutput) {
 super(message);
 this.buildOutput = buildOutput;
 }

 /** @return captured build output; may be {@code null}. */
 public String buildOutput() {
 return buildOutput;
 }
}
