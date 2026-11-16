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

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the progress which the Docker daemon reports for a build with the BuildKit builder (build API
 * {@code version=2}). Each {@code moby.buildkit.trace} message of the build response carries a base64 encoded
 * {@code moby.buildkit.v1.StatusResponse} protocol buffers message.
 * <p>
 * Only what is needed for reporting the progress is decoded. The field numbers are the ones of
 * {@code api/services/control/control.proto} in the BuildKit repository (https://github.com/moby/buildkit):
 * <pre>
 * message StatusResponse {
 *   repeated Vertex vertexes = 1;
 *   repeated VertexStatus statuses = 2;
 *   repeated VertexLog logs = 3;
 *   repeated VertexWarning warnings = 4;
 * }
 * message Vertex {
 *   string digest = 1;
 *   repeated string inputs = 2;
 *   string name = 3;
 *   bool cached = 4;
 *   google.protobuf.Timestamp started = 5;
 *   google.protobuf.Timestamp completed = 6;
 *   string error = 7;
 * }
 * message VertexLog {
 *   string vertex = 1;
 *   google.protobuf.Timestamp timestamp = 2;
 *   int64 stream = 3;
 *   bytes msg = 4;
 * }
 * </pre>
 * Protocol buffers skips what it doesn't know, so fields which are added to the messages don't matter.
 */
final class BuildKitStatusDecoder {

    private static final int STATUS_VERTEXES = 1;
    private static final int STATUS_LOGS = 3;

    private static final int VERTEX_DIGEST = 1;
    private static final int VERTEX_NAME = 3;
    private static final int VERTEX_CACHED = 4;
    private static final int VERTEX_STARTED = 5;
    private static final int VERTEX_COMPLETED = 6;
    private static final int VERTEX_ERROR = 7;

    private static final int LOG_VERTEX = 1;
    private static final int LOG_STREAM = 3;
    private static final int LOG_MSG = 4;

    private BuildKitStatusDecoder() {
    }

    /**
     * A step of the build.
     */
    static final class Vertex {
        final String digest;
        final String name;
        final boolean cached;
        final boolean started;
        final boolean completed;
        // Empty if the step didn't fail
        final String error;

        Vertex(String digest, String name, boolean cached, boolean started, boolean completed, String error) {
            this.digest = digest;
            this.name = name;
            this.cached = cached;
            this.started = started;
            this.completed = completed;
            this.error = error;
        }
    }

    /**
     * Output of a step of the build.
     */
    static final class VertexLog {
        final String vertex;
        // 1: stdout, 2: stderr
        final long stream;
        final String message;

        VertexLog(String vertex, long stream, String message) {
            this.vertex = vertex;
            this.stream = stream;
            this.message = message;
        }
    }

    static final class Status {
        final List<Vertex> vertexes = new ArrayList<>();
        final List<VertexLog> logs = new ArrayList<>();
    }

    static Status decode(byte[] statusResponse) throws IOException {
        Status status = new Status();
        CodedInputStream in = CodedInputStream.newInstance(statusResponse);
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == STATUS_VERTEXES && isMessage(tag)) {
                status.vertexes.add(decodeVertex(in.readByteArray()));
            } else if (field == STATUS_LOGS && isMessage(tag)) {
                status.logs.add(decodeLog(in.readByteArray()));
            } else {
                in.skipField(tag);
            }
        }
        return status;
    }

    private static Vertex decodeVertex(byte[] message) throws IOException {
        String digest = "";
        String name = "";
        boolean cached = false;
        boolean started = false;
        boolean completed = false;
        String error = "";

        CodedInputStream in = CodedInputStream.newInstance(message);
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            int field = WireFormat.getTagFieldNumber(tag);
            int wireType = WireFormat.getTagWireType(tag);
            if (field == VERTEX_DIGEST && isMessage(tag)) {
                digest = in.readString();
            } else if (field == VERTEX_NAME && isMessage(tag)) {
                name = in.readString();
            } else if (field == VERTEX_CACHED && wireType == WireFormat.WIRETYPE_VARINT) {
                cached = in.readBool();
            } else if (field == VERTEX_STARTED && isMessage(tag)) {
                // The timestamp isn't of interest, it being set means that the step is started
                started = true;
                in.skipField(tag);
            } else if (field == VERTEX_COMPLETED && isMessage(tag)) {
                completed = true;
                in.skipField(tag);
            } else if (field == VERTEX_ERROR && isMessage(tag)) {
                error = in.readString();
            } else {
                in.skipField(tag);
            }
        }
        return new Vertex(digest, name, cached, started, completed, error);
    }

    private static VertexLog decodeLog(byte[] message) throws IOException {
        String vertex = "";
        long stream = 0;
        String msg = "";

        CodedInputStream in = CodedInputStream.newInstance(message);
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == LOG_VERTEX && isMessage(tag)) {
                vertex = in.readString();
            } else if (field == LOG_STREAM && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                stream = in.readInt64();
            } else if (field == LOG_MSG && isMessage(tag)) {
                msg = new String(in.readByteArray(), StandardCharsets.UTF_8);
            } else {
                in.skipField(tag);
            }
        }
        return new VertexLog(vertex, stream, msg);
    }

    // Strings, bytes and embedded messages are all length delimited
    private static boolean isMessage(int tag) {
        return WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED;
    }
}
