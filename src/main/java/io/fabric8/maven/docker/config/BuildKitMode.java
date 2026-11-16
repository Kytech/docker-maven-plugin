package io.fabric8.maven.docker.config;

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

import java.util.Locale;

/**
 * Whether the Docker daemon's BuildKit builder should be used for a build which is sent to the daemon through its
 * build API (i.e. a build which doesn't use {@code docker buildx}).
 */
public enum BuildKitMode {

    /**
     * Never use BuildKit, always use the classic builder. This is the default.
     */
    FALSE("false"),

    /**
     * Always use BuildKit.
     */
    TRUE("true"),

    /**
     * Use BuildKit if {@code docker build} on the same machine would do so: as the {@code DOCKER_BUILDKIT}
     * environment variable says, or if that is not set, if BuildKit is the default builder of the daemon.
     */
    AUTO("auto");

    public static final BuildKitMode DEFAULT = FALSE;

    private final String parameter;

    BuildKitMode(String parameter) {
        this.parameter = parameter;
    }

    public static BuildKitMode parse(String param) {
        if (param == null || param.trim().isEmpty()) {
            return DEFAULT;
        }
        String normalized = param.trim().toLowerCase(Locale.ROOT);
        for (BuildKitMode mode : values()) {
            if (mode.parameter.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Invalid buildkit mode " + param + " (should be one of: false/true/auto)");
    }

    public String toParameter() {
        return parameter;
    }
}
