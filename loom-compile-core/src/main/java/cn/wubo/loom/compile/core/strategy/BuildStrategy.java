package cn.wubo.loom.compile.core.strategy;

import cn.wubo.loom.compile.core.CompileAndDeployOperations.ResolvedImage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Build strategy: defines the full pipeline for a stack (Java/Node/Python) -
 * find project -> compile -> find artifact -> write Dockerfile.
 */
public sealed interface BuildStrategy
 permits MavenBuildStrategy, NpmBackendBuildStrategy, NpmFrontendBuildStrategy, PythonBuildStrategy {

 List<String> markerFiles();

 List<List<String>> buildCommands();

 List<String> artifactCandidates();

 boolean isLongRunning();

 File writeDockerfile(Path projectDir, ResolvedImage image, int containerPort, String artifact) throws IOException;

 default Path findArtifact(Path candidateDir) {
 return candidateDir;
 }
}
