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

import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

class BuildKitStatusDecoderTest {

    @Test
    void decodesVertex() throws IOException {
        byte[] vertex = vertex("sha256:abc", "[2/3] RUN echo hi", true, true, true, "failed");

        BuildKitStatusDecoder.Status status = BuildKitStatusDecoder.decode(message(1, vertex));

        Assertions.assertEquals(1, status.vertexes.size());
        BuildKitStatusDecoder.Vertex decoded = status.vertexes.get(0);
        Assertions.assertEquals("sha256:abc", decoded.digest);
        Assertions.assertEquals("[2/3] RUN echo hi", decoded.name);
        Assertions.assertTrue(decoded.cached);
        Assertions.assertTrue(decoded.started);
        Assertions.assertTrue(decoded.completed);
        Assertions.assertEquals("failed", decoded.error);
        Assertions.assertTrue(status.logs.isEmpty());
    }

    @Test
    void vertexDefaults() throws IOException {
        BuildKitStatusDecoder.Status status = BuildKitStatusDecoder.decode(message(1, new byte[0]));

        BuildKitStatusDecoder.Vertex decoded = status.vertexes.get(0);
        Assertions.assertEquals("", decoded.digest);
        Assertions.assertEquals("", decoded.name);
        Assertions.assertFalse(decoded.cached);
        Assertions.assertFalse(decoded.started);
        Assertions.assertFalse(decoded.completed);
        Assertions.assertEquals("", decoded.error);
    }

    @Test
    void decodesLog() throws IOException {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(log);
        out.writeString(1, "sha256:abc");
        out.writeInt64(3, 2);
        out.writeByteArray(4, "ünï\ncode\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        BuildKitStatusDecoder.Status status = BuildKitStatusDecoder.decode(message(3, log.toByteArray()));

        Assertions.assertEquals(1, status.logs.size());
        Assertions.assertEquals("sha256:abc", status.logs.get(0).vertex);
        Assertions.assertEquals(2, status.logs.get(0).stream);
        Assertions.assertEquals("ünï\ncode\n", status.logs.get(0).message);
    }

    @Test
    void skipsWhatIsNotNeeded() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        // statuses, warnings and a field of a future version
        out.writeByteArray(2, new byte[] { 1, 2, 3 });
        out.writeByteArray(4, new byte[] { 1, 2, 3 });
        out.writeInt32(99, 7);
        out.writeByteArray(1, vertex("sha256:abc", "step", false, false, false, ""));
        out.flush();

        BuildKitStatusDecoder.Status status = BuildKitStatusDecoder.decode(bytes.toByteArray());

        Assertions.assertEquals(1, status.vertexes.size());
        Assertions.assertEquals("step", status.vertexes.get(0).name);
    }

    @Test
    void emptyMessage() throws IOException {
        BuildKitStatusDecoder.Status status = BuildKitStatusDecoder.decode(new byte[0]);

        Assertions.assertTrue(status.vertexes.isEmpty());
        Assertions.assertTrue(status.logs.isEmpty());
    }

    @Test
    void truncatedMessageFails() {
        byte[] message = message(1, vertex("sha256:abc", "step", false, false, false, ""));
        byte[] truncated = new byte[message.length - 3];
        System.arraycopy(message, 0, truncated, 0, truncated.length);

        Assertions.assertThrows(IOException.class, () -> BuildKitStatusDecoder.decode(truncated));
    }

    private static byte[] vertex(String digest, String name, boolean cached, boolean started, boolean completed, String error) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            out.writeString(1, digest);
            out.writeString(3, name);
            out.writeBool(4, cached);
            if (started) {
                // An embedded google.protobuf.Timestamp with seconds
                out.writeByteArray(5, new byte[] { 0x08, 0x01 });
            }
            if (completed) {
                out.writeByteArray(6, new byte[] { 0x08, 0x02 });
            }
            if (!error.isEmpty()) {
                out.writeString(7, error);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] message(int field, byte[] content) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            out.writeByteArray(field, content);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
