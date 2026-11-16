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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.fabric8.maven.docker.config.BuildKitMode.*;

class BuildKitModeTest {

    @Test
    void parse() {
        Object[] data = {
            null, FALSE,
            "", FALSE,
            "  ", FALSE,
            "false", FALSE,
            "FaLsE", FALSE,
            "true", TRUE,
            " TRUE ", TRUE,
            "auto", AUTO,
            "Auto", AUTO
        };

        for (int i = 0; i < data.length; i += 2) {
            Assertions.assertEquals(data[i + 1], BuildKitMode.parse((String) data[i]), "for '" + data[i] + "'");
        }
    }

    @Test
    void defaultIsOff() {
        Assertions.assertEquals(FALSE, BuildKitMode.DEFAULT);
    }

    @Test
    void parameterRoundTrip() {
        for (BuildKitMode mode : BuildKitMode.values()) {
            Assertions.assertEquals(mode, BuildKitMode.parse(mode.toParameter()));
        }
    }

    @Test
    void invalid() {
        String message = Assertions.assertThrows(IllegalArgumentException.class, () -> BuildKitMode.parse("blub")).getMessage();
        Assertions.assertTrue(message.contains("blub"));
        Assertions.assertTrue(message.contains("false"));
        Assertions.assertTrue(message.contains("true"));
        Assertions.assertTrue(message.contains("auto"));
    }
}
