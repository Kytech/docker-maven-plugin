package io.fabric8.maven.docker.access.chunked;

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.gson.JsonObject;
import com.google.protobuf.CodedOutputStream;
import io.fabric8.maven.docker.access.DockerAccessException;
import io.fabric8.maven.docker.util.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.fabric8.maven.docker.util.Logger.LogVerboseCategory.BUILD;

class BuildJsonResponseHandlerTest {

    private Logger log;

    private BuildJsonResponseHandler handler;

    @BeforeEach
    void setup() {
        log = Mockito.mock(Logger.class);
        handler = new BuildJsonResponseHandler(log);
    }

    @Test
    void classicBuilderStreamIsLogged() throws Exception {
        process("{\"stream\":\"Step 1/2 : FROM scratch\"}\n{\"stream\":\"\\n\"}\n");

        Mockito.verify(log).verbose(BUILD, "%s", "Step 1/2 : FROM scratch");
    }

    @Test
    void errorFails() {
        DockerAccessException exception = Assertions.assertThrows(DockerAccessException.class, () ->
            process("{\"error\":\"boom\",\"errorDetail\":{\"message\":\"boom\"}}\n"));

        Assertions.assertTrue(exception.getMessage().contains("boom"));
    }

    // Captured from a build of a Dockerfile with FROM busybox and two RUN, with BuildKit of Docker 29.9.2
    @Test
    void buildKitProgressIsLogged() throws Exception {
        process(resource("buildkit-build-ok.ndjson"));

        Mockito.verify(log).verbose(BUILD, "%s", "[2/3] RUN echo \"hello from buildkit\"");
        Mockito.verify(log).verbose(BUILD, "%s", "hello from buildkit");
        Mockito.verify(log).verbose(BUILD, "%s", "[3/3] RUN echo \"second step\" && echo \"to stderr\" >&2");
        Mockito.verify(log).verbose(BUILD, "%s", "second step");
        Mockito.verify(log).verbose(BUILD, "%s", "to stderr");
        // Every step once, although it is reported several times
        Mockito.verify(log, Mockito.times(1)).verbose(BUILD, "%s", "[internal] load remote build context");
        Mockito.verify(log, Mockito.never()).error(Mockito.anyString(), Mockito.any());
    }

    @Test
    void failedBuildKitStepShowsItsOutput() throws Exception {
        DockerAccessException exception = Assertions.assertThrows(DockerAccessException.class,
            () -> process(resource("buildkit-build-failed.ndjson")));

        Assertions.assertTrue(exception.getMessage().contains("exit code: 3"));
        Mockito.verify(log).error("Output of the failed build step %s:",
            "[3/3] RUN echo \"about to fail\" && echo \"stderr line\" >&2 && exit 3");
        Mockito.verify(log).error("  %s", "about to fail");
        Mockito.verify(log).error("  %s", "stderr line");
        // Not for the steps which are fine
        Mockito.verify(log, Mockito.times(3)).error(Mockito.anyString(), Mockito.any());
    }

    @Test
    void undecodableBuildKitProgressIsIgnored() throws Exception {
        process("{\"id\":\"moby.buildkit.trace\",\"aux\":\"not base64!\"}\n"
                + "{\"id\":\"moby.buildkit.trace\",\"aux\":\"AQID\"}\n"
                + "{\"id\":\"moby.buildkit.trace\",\"aux\":{\"an\":\"object\"}}\n"
                + "{\"id\":\"moby.image.id\",\"aux\":{\"ID\":\"sha256:123\"}}\n");

        Mockito.verify(log, Mockito.never()).verbose(Mockito.any(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void outputOfAFailedStepIsLimited() throws Exception {
        // Without a progress of the step in between, the steps are only known by their digest
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            lines.append(trace(log("sha256:s1", "line " + i + "\n"))).append('\n');
        }
        lines.append(trace(failedVertex("sha256:s1", "[1/1] RUN many"))).append('\n');

        process(lines.toString());

        Mockito.verify(log, Mockito.times(51)).error(Mockito.anyString(), Mockito.any());
        Mockito.verify(log, Mockito.never()).error("  %s", "line 29");
        Mockito.verify(log).error("  %s", "line 30");
        Mockito.verify(log).error("  %s", "line 79");
    }

    private void process(String ndjson) throws IOException {
        EntityStreamReaderUtil.processJsonStream(handler, new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8)));
    }

    private void process(InputStream ndjson) throws IOException {
        EntityStreamReaderUtil.processJsonStream(handler, ndjson);
    }

    private static InputStream resource(String name) {
        return BuildJsonResponseHandlerTest.class.getResourceAsStream(name);
    }

    // {"id":"moby.buildkit.trace","aux":"<base64 of the message>"}
    private static String trace(byte[] statusResponse) {
        JsonObject json = new JsonObject();
        json.addProperty("id", "moby.buildkit.trace");
        json.addProperty("aux", Base64.getEncoder().encodeToString(statusResponse));
        return json.toString();
    }

    private static byte[] log(String vertex, String message) throws IOException {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        CodedOutputStream logOut = CodedOutputStream.newInstance(log);
        logOut.writeString(1, vertex);
        logOut.writeInt64(3, 1);
        logOut.writeByteArray(4, message.getBytes(StandardCharsets.UTF_8));
        logOut.flush();
        return statusResponse(3, log.toByteArray());
    }

    private static byte[] failedVertex(String digest, String name) throws IOException {
        ByteArrayOutputStream vertex = new ByteArrayOutputStream();
        CodedOutputStream vertexOut = CodedOutputStream.newInstance(vertex);
        vertexOut.writeString(1, digest);
        vertexOut.writeString(3, name);
        vertexOut.writeString(7, "failed");
        vertexOut.flush();
        return statusResponse(1, vertex.toByteArray());
    }

    private static byte[] statusResponse(int field, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeByteArray(field, content);
        out.flush();
        return bytes.toByteArray();
    }
}
