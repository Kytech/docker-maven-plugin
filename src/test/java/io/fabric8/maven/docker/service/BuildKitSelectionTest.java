package io.fabric8.maven.docker.service;

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

import io.fabric8.maven.docker.access.DockerAccess;
import io.fabric8.maven.docker.access.DockerAccessException;
import io.fabric8.maven.docker.config.BuildKitMode;
import io.fabric8.maven.docker.util.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests the decision whether a build sent to the daemon should use BuildKit.
 */
@ExtendWith(MockitoExtension.class)
class BuildKitSelectionTest {

    @Mock
    private DockerAccess docker;

    @Mock
    private Logger log;

    @Test
    void testBuildKitRequestedByMode() throws DockerAccessException {
        Assertions.assertFalse(BuildService.isBuildKitRequested(BuildKitMode.FALSE, "1", docker, log));
        Assertions.assertTrue(BuildService.isBuildKitRequested(BuildKitMode.TRUE, "0", docker, log));
        // Neither asks the daemon
        Mockito.verify(docker, Mockito.never()).getBuilderVersion();
    }

    @Test
    void testAutoBuildKitFollowsDockerBuildKitEnv() throws DockerAccessException {
        for (String on : new String[] { "1", "t", "T", "true", "TRUE", "True", " 1 " }) {
            Assertions.assertTrue(BuildService.isBuildKitRequested(BuildKitMode.AUTO, on, docker, log), on);
        }
        for (String off : new String[] { "0", "f", "F", "false", "FALSE", "False" }) {
            Assertions.assertFalse(BuildService.isBuildKitRequested(BuildKitMode.AUTO, off, docker, log), off);
        }
        // The environment decides, the daemon doesn't need to be asked
        Mockito.verify(docker, Mockito.never()).getBuilderVersion();
    }

    @Test
    void testAutoBuildKitFollowsDaemonDefault() throws DockerAccessException {
        Mockito.doReturn("2").when(docker).getBuilderVersion();
        Assertions.assertTrue(BuildService.isBuildKitRequested(BuildKitMode.AUTO, null, docker, log));

        Mockito.doReturn("1").when(docker).getBuilderVersion();
        Assertions.assertFalse(BuildService.isBuildKitRequested(BuildKitMode.AUTO, null, docker, log));
    }

    @Test
    void testAutoBuildKitIgnoresInvalidDockerBuildKitEnv() throws DockerAccessException {
        Mockito.doReturn("2").when(docker).getBuilderVersion();

        Assertions.assertTrue(BuildService.isBuildKitRequested(BuildKitMode.AUTO, "maybe", docker, log));
        Mockito.verify(log).warn(Mockito.contains("invalid value"), Mockito.eq("maybe"), Mockito.eq("DOCKER_BUILDKIT"));
    }
}
