package io.fabric8.maven.docker.access.chunked;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.fabric8.maven.docker.access.DockerAccessException;
import io.fabric8.maven.docker.util.Logger;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class BuildJsonResponseHandler implements EntityStreamReaderUtil.JsonEntityResponseHandler {

    // Message of the progress which the BuildKit builder reports
    private static final String BUILDKIT_TRACE_ID = "moby.buildkit.trace";

    // How much of the output of a build step is kept for showing it if the step fails
    private static final int MAX_OUTPUT_LINES_PER_STEP = 50;

    private final Logger log;

    // Build steps which have been reported as started (by digest, there is one with the same digest per step)
    private final Set<String> startedSteps = new HashSet<>();
    private final Set<String> completedSteps = new HashSet<>();
    private final Set<String> failedSteps = new HashSet<>();
    private final Map<String, String> stepNames = new HashMap<>();
    private final Map<String, Deque<String>> stepOutputs = new HashMap<>();

    public BuildJsonResponseHandler(Logger log) {
        this.log = log;
    }
    
    @Override
    public void process(JsonObject json) throws DockerAccessException {
        if (json.has("error")) {
            String msg = json.get("error").getAsString();
            String detailMsg = "";
            if (json.has("errorDetail")) {
                JsonObject details = json.getAsJsonObject("errorDetail");
                detailMsg = details.get("message").getAsString();
            }
            throw new DockerAccessException("%s %s", json.get("error"),
                    (msg.equals(detailMsg) || "".equals(detailMsg) ? "" : "(" + detailMsg + ")"));
        } else if (isBuildKitTrace(json)) {
            processBuildKitTrace(json.get("aux").getAsString());
        } else if (json.has("stream")) {
            String message = json.get("stream").getAsString();
            log.verbose(Logger.LogVerboseCategory.BUILD,"%s", message.trim());
        } else if (json.has("status")) {
            String status = json.get("status").getAsString().trim();
            String id = json.has("id") ? json.get("id").getAsString() : null;
            if (status.matches("^.*(Download|Pulling).*")) {
                log.info("  %s%s",id != null ? id + " " : "",status);
            }
        }
    }

    private static boolean isBuildKitTrace(JsonObject json) {
        JsonElement id = json.get("id");
        JsonElement aux = json.get("aux");
        return id != null && id.isJsonPrimitive() && BUILDKIT_TRACE_ID.equals(id.getAsString())
            && aux != null && aux.isJsonPrimitive();
    }

    /**
     * Report the progress of a build with BuildKit like the classic builder does, as verbose output. The output of a
     * step which fails is shown regardless, as it tells why that happened (BuildKit's error message only has the
     * exit code).
     */
    private void processBuildKitTrace(String encodedStatus) {
        BuildKitStatusDecoder.Status status;
        try {
            status = BuildKitStatusDecoder.decode(Base64.getDecoder().decode(encodedStatus));
        } catch (IOException | IllegalArgumentException e) {
            // Progress is nice to have, a build is not to fail for it
            log.debug("Cannot decode the build progress: %s", e.getMessage());
            return;
        }

        for (BuildKitStatusDecoder.Vertex vertex : status.vertexes) {
            if (!vertex.name.isEmpty()) {
                stepNames.put(vertex.digest, vertex.name);
            }
            if (vertex.started && startedSteps.add(vertex.digest)) {
                log.verbose(Logger.LogVerboseCategory.BUILD, "%s", vertex.name);
            }
        }
        for (BuildKitStatusDecoder.VertexLog stepLog : status.logs) {
            for (String line : stepLog.message.split("\\r?\\n")) {
                if (!line.isEmpty()) {
                    log.verbose(Logger.LogVerboseCategory.BUILD, "%s", line);
                    keepOutput(stepLog.vertex, line);
                }
            }
        }
        for (BuildKitStatusDecoder.Vertex vertex : status.vertexes) {
            if (!vertex.error.isEmpty()) {
                reportFailedStep(vertex);
            } else if (vertex.completed && vertex.cached && completedSteps.add(vertex.digest)) {
                log.verbose(Logger.LogVerboseCategory.BUILD, "%s CACHED", stepNames.getOrDefault(vertex.digest, vertex.digest));
            }
        }
    }

    private void keepOutput(String stepDigest, String line) {
        Deque<String> output = stepOutputs.computeIfAbsent(stepDigest, k -> new ArrayDeque<>());
        if (output.size() >= MAX_OUTPUT_LINES_PER_STEP) {
            output.removeFirst();
        }
        output.addLast(line);
    }

    private void reportFailedStep(BuildKitStatusDecoder.Vertex vertex) {
        if (!failedSteps.add(vertex.digest)) {
            return;
        }
        Deque<String> output = stepOutputs.get(vertex.digest);
        if (output != null && !output.isEmpty()) {
            log.error("Output of the failed build step %s:", stepNames.getOrDefault(vertex.digest, vertex.digest));
            for (String line : output) {
                log.error("  %s", line);
            }
        }
    }

    // Lifecycle methods not needed ...
    @Override
    public void start() {}

    @Override
    public void stop() {}
}
