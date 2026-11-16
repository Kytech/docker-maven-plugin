package io.fabric8.maven.docker.assembly;

import io.fabric8.maven.docker.util.Logger;
import org.codehaus.plexus.archiver.tar.TarArchiver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The build context sent to the Docker daemon must not start with a PAX extended header. The BuildKit builder
 * (build API {@code version=2}) fails to find the Dockerfile in such an archive, reporting
 * {@code unknown instruction: ./PaxHeaders.X/...}. The archiver emits PAX headers when the entries have a
 * sub-second modification time and the archiver uses the POSIX long file mode.
 */
class BuildContextTarFormatTest {

    private static final int TAR_BLOCK_SIZE = 512;
    private static final int TYPE_FLAG_OFFSET = 156;

    @TempDir
    Path tmpDir;

    @Test
    void permissionNormalizedContextHasNoPaxHeaders() throws Exception {
        TarArchiver original = new TarArchiver();
        original.setDestFile(tmpDir.resolve("context.tar").toFile());
        original.addFile(createFileWithSubSecondMtime("Dockerfile"), "Dockerfile");

        TarArchiver customized = new AllFilesExecCustomizer(Mockito.mock(Logger.class)).customize(original);
        customized.createArchive();

        assertNoPaxHeaders(customized.getDestFile());
    }

    @Test
    void contextLongFileModeProducesNoPaxHeaders() throws Exception {
        TarArchiver archiver = new TarArchiver();
        archiver.setLongfile(DockerAssemblyManager.BUILD_CONTEXT_LONG_FILE_MODE);
        archiver.setDestFile(tmpDir.resolve("context.tar").toFile());
        archiver.addFile(createFileWithSubSecondMtime("Dockerfile"), "Dockerfile");
        archiver.addFile(createFileWithSubSecondMtime("long"), repeat('d', 120) + ".txt");
        archiver.createArchive();

        assertNoPaxHeaders(archiver.getDestFile());
    }

    private File createFileWithSubSecondMtime(String name) throws IOException {
        Path file = tmpDir.resolve(name);
        Files.write(file, "FROM scratch\n".getBytes(StandardCharsets.UTF_8));
        Assertions.assertTrue(file.toFile().setLastModified(1_700_000_000_500L));
        return file.toFile();
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void assertNoPaxHeaders(File tar) throws IOException {
        byte[] content = Files.readAllBytes(tar.toPath());
        // Walk the entry headers; each header is followed by the data padded to the block size
        int offset = 0;
        while (offset + TAR_BLOCK_SIZE <= content.length && content[offset] != 0) {
            String name = new String(content, offset, 100, StandardCharsets.ISO_8859_1).trim();
            char typeFlag = (char) content[offset + TYPE_FLAG_OFFSET];
            Assertions.assertNotEquals('x', typeFlag, "PAX extended header found: " + name);
            Assertions.assertNotEquals('g', typeFlag, "PAX global header found: " + name);
            Assertions.assertFalse(name.contains("PaxHeaders"), "PAX header entry found: " + name);

            long size = Long.parseLong(new String(content, offset + 124, 11, StandardCharsets.ISO_8859_1).trim(), 8);
            long dataBlocks = (size + TAR_BLOCK_SIZE - 1) / TAR_BLOCK_SIZE;
            offset += TAR_BLOCK_SIZE + (int) (dataBlocks * TAR_BLOCK_SIZE);
        }
    }
}
