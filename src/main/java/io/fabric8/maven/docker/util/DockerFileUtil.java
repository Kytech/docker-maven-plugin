package io.fabric8.maven.docker.util;/*
 *
 * Copyright 2015 Roland Huss
 *
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

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.maven.plugins.assembly.interpolation.AssemblyInterpolator;
import org.apache.maven.plugins.assembly.io.DefaultAssemblyReader;
import org.codehaus.plexus.interpolation.fixed.FixedStringSearchInterpolator;

import io.fabric8.maven.docker.assembly.DockerAssemblyConfigurationSource;
import org.yaml.snakeyaml.Yaml;

import static io.fabric8.maven.docker.util.EnvUtil.getUserHome;


/**
 * Utility class for dealing with dockerfiles
 * @author roland
 * @since 21/01/16
 */
public class DockerFileUtil {

    private static final String ARG_PATTERN_REGEX = "\\$([\\w|\\-|\\.]+)|\\$\\{([\\w|\\-|\\.]+)\\}";

    private DockerFileUtil() {}

    /**
     * Extract the base images from a dockerfile. All lines containing a <code>FROM</code> is
     * taken.
     *
     * Besides the images of the {@code FROM} instructions, also the images which other instructions take files
     * from are returned: {@code COPY --from=<image>} and {@code RUN --mount=...,from=<image>}. A reference to
     * a build stage (by its name or its index) is not an image and is skipped.
     *
     * @param dockerFile file from where to extract the base image
     * @param interpolator interpolator for replacing properties
     * @return LinkedList of base images name or empty collection if none is found.
     */
    public static List<String> extractBaseImages(File dockerFile, FixedStringSearchInterpolator interpolator, Map<String, String> argsFromBuildConfig) throws IOException {
        List<String[]> fromLines = extractLines(dockerFile, "FROM", interpolator);
        Map<String, String> args = extractArgs(dockerFile, argsFromBuildConfig, interpolator);
        Set<String> result = new LinkedHashSet<>();
        Set<String> fromAlias = new HashSet<>();
        Set<String> stageNames = new HashSet<>();
        for (String[] fromLine :  fromLines) {
            addStageName(stageNames, fromLine, args);
            if (fromLine.length > 1) {
                String from = extractValidFromLine(fromLine);
                if (!fromAlias.contains(from)) {
                    // Image is NOT referring to an already declared alias
                    result.add(resolveImageTagFromArgs(from, args));
                }
                if (fromLine.length == 4) { // FROM image:tag AS alias use case
                    // Image alias is declared - track it
                    fromAlias.add(resolveImageTagFromArgs(fromLine[3], args));
                }
            }
        }
        for (String reference : extractStageSourceReferences(dockerFile, interpolator)) {
            String image = resolveImageTagFromArgs(reference, args);
            if (isExternalImage(image, stageNames)) {
                result.add(image);
            }
        }
        return new ArrayList<>(result);
    }

    // The name of a stage is given with "FROM [--flags] image AS name". Docker handles stage names case insensitive.
    private static void addStageName(Set<String> stageNames, String[] fromLine, Map<String, String> args) {
        for (int i = 1; i < fromLine.length - 1; i++) {
            if (fromLine[i].equalsIgnoreCase("AS")) {
                stageNames.add(resolveImageTagFromArgs(fromLine[i + 1], args).toLowerCase(Locale.ROOT));
                return;
            }
        }
    }

    private static boolean isExternalImage(String reference, Set<String> stageNames) {
        return !reference.isEmpty()
            // Index of a stage
            && !reference.chars().allMatch(Character::isDigit)
            && !stageNames.contains(reference.toLowerCase(Locale.ROOT))
            // Can't be pulled
            && !reference.contains("$");
    }

    // Start of a here-document ("<<EOF", "<<-EOF", "<<'EOF'"), but not a here-string ("<<<")
    private static final Pattern HEREDOC_PATTERN = Pattern.compile("(?<!<)<<-?\\s*[\"']?([\\w.-]+)[\"']?");

    /**
     * Get what the {@code COPY --from=...} and the {@code RUN --mount=...,from=...} flags of a Dockerfile refer to.
     * This is an image or a build stage.
     */
    private static List<String> extractStageSourceReferences(File dockerFile, FixedStringSearchInterpolator interpolator) throws IOException {
        List<String> references = new ArrayList<>();
        for (String instruction : readInstructions(dockerFile, interpolator)) {
            String[] parts = instruction.split("\\s+");
            boolean isCopy = parts[0].equalsIgnoreCase("COPY");
            boolean isRun = parts[0].equalsIgnoreCase("RUN");
            if (!isCopy && !isRun) {
                continue;
            }
            // Flags come right after the instruction
            for (int i = 1; i < parts.length && parts[i].startsWith("--"); i++) {
                if (isCopy && parts[i].startsWith("--from=")) {
                    references.add(parts[i].substring("--from=".length()));
                } else if (isRun && parts[i].startsWith("--mount=")) {
                    for (String option : parts[i].substring("--mount=".length()).split(",")) {
                        if (option.startsWith("from=")) {
                            references.add(option.substring("from=".length()));
                        }
                    }
                }
            }
        }
        return references;
    }

    /**
     * Read the instructions of a Dockerfile: comments are left out, instructions continued on the next line
     * with a backslash are joined, and the content of here-documents isn't taken as instructions.
     */
    private static List<String> readInstructions(File dockerFile, FixedStringSearchInterpolator interpolator) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(dockerFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(interpolator.interpolate(line));
            }
        }

        List<String> instructions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            boolean continued = trimmed.endsWith("\\");
            current.append(continued ? trimmed.substring(0, trimmed.length() - 1) : trimmed).append(' ');
            if (continued) {
                continue;
            }
            String instruction = current.toString().trim();
            current.setLength(0);
            instructions.add(instruction);

            Matcher heredoc = HEREDOC_PATTERN.matcher(instruction);
            while (heredoc.find()) {
                i = skipHereDocument(lines, i, heredoc.group(1));
            }
        }
        if (current.length() > 0) {
            instructions.add(current.toString().trim());
        }
        return instructions;
    }

    // Returns the index of the line which ends the here-document. If there is no such line, what looked like
    // the start of a here-document isn't one (e.g. a shift in a shell expression) and nothing is skipped.
    private static int skipHereDocument(List<String> lines, int startIndex, String delimiter) {
        for (int i = startIndex + 1; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(delimiter)) {
                return i;
            }
        }
        return startIndex;
    }

    private static String extractValidFromLine(String[] fromLine) {
        String validFrom = fromLine[1];
        for (int i = 1; i < fromLine.length; i++) {
            if (!fromLine[i].startsWith("--")) {
                validFrom = fromLine[i];
                break;
            }
        }
        return validFrom;
    }

    /**
     * Extract Args from dockerfile. All lines containing ARG is taken.
     *
     * @param dockerfile Docker File
     * @param interpolator interpolator for replacement
     * @return HashMap of arguments or empty collection if none is found
     */
    public static Map<String, String> extractArgs(File dockerfile, Map<String, String> argsFromBuildConfig, FixedStringSearchInterpolator interpolator) throws IOException {
        return extractArgsFromLines(extractLines(dockerfile, "ARG", interpolator), argsFromBuildConfig);
    }

    /**
     * Extract all lines containing the given keyword
     *
     * @param dockerFile dockerfile to examine
     * @param keyword keyword to extract the lines for
     * @param interpolator interpolator for replacing properties
     * @return list of matched lines or an empty list
     */
    public static List<String[]> extractLines(File dockerFile, String keyword, FixedStringSearchInterpolator interpolator) throws IOException {
        List<String[]> ret = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(dockerFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lineInterpolated = interpolator.interpolate(line);
                String[] lineParts = lineInterpolated.split("\\s+");
                if (lineParts.length > 0 && lineParts[0].equalsIgnoreCase(keyword)) {
                    ret.add(lineParts);
                }
            }
        }
        return ret;
    }

    /**
     * Interpolate a docker file with the given properties and filter
     *
     * @param dockerFile docker file to interpolate
     * @param interpolator interpolator for replacing properties
     * @return The interpolated contents of the file.
     * @throws IOException
     */
    public static String interpolate(File dockerFile, FixedStringSearchInterpolator interpolator) throws IOException {
        StringBuilder ret = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(dockerFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ret.append(interpolator.interpolate(line)).append(System.lineSeparator());
            }
        }
        return ret.toString();
    }

    /**
     * Create an interpolator for the given maven parameters and filter configuration.
     *
     * @param params The maven parameters.
     * @param filter The filter configuration.
     * @return An interpolator for replacing maven properties.
     */
    public static FixedStringSearchInterpolator createInterpolator(MojoParameters params, String filter) {
        String[] delimiters = extractDelimiters(filter);
        if (delimiters == null) {
            // Don't interpolate anything
            return FixedStringSearchInterpolator.create();
        }

        DockerAssemblyConfigurationSource configSource = new DockerAssemblyConfigurationSource(params, null, null);
        // Patterned after org.apache.maven.plugins.assembly.interpolation.AssemblyExpressionEvaluator
        return AssemblyInterpolator
                .fullInterpolator(params.getProject(),
                        DefaultAssemblyReader.createProjectInterpolator(params.getProject())
                          .withExpressionMarkers(delimiters[0], delimiters[1]), configSource)
                .withExpressionMarkers(delimiters[0], delimiters[1]);
    }

    /**
     * Helper method for extractArgs(exposed for test)
     *
     * @param argLines list of string arrays containing lines with words
     * @return map of parsed arguments
     */
    static Map<String, String> extractArgsFromLines(List<String[]> argLines, Map<String, String> argsFromBuildConfig) {
        Map<String, String> result = new HashMap<>();
        for (String[] argLine : argLines) {
            if (argLine.length > 1) {
                updateMapWithArgValue(result, argsFromBuildConfig, argLine[1]);
            }
        }
        return result;
    }

    static String resolveImageTagFromArgs(String imageTagString, Map<String, String> args) {
        String resolvedImageString = imageTagString;
        Set<String> foundArgs = findAllArgs(imageTagString);
        for (String foundArg : foundArgs) {
            // check if the arg referenced in imageTagString exists in the Dockerfile
            if (args.containsKey(foundArg)) {
                // resolve args that are referenced by this arg
                String arg = resolveImageTagFromArgs(args.get(foundArg), args);
                // replace the arg by its value
                resolvedImageString = resolvedImageString.replaceFirst(String.format("\\$\\{*%s\\}*", foundArg), arg);
            }
        }
        return resolvedImageString;
    }

    static Set<String> findAllArgs(String imageTagString) {
        Matcher m = Pattern.compile(ARG_PATTERN_REGEX).matcher(imageTagString);
        Set<String> args = new HashSet<>();
        while(m.find()){
            if(m.group(1)!=null){
                args.add(m.group(1));
            }else if(m.group(2)!=null){
                args.add(m.group(2));
            }
        }
        return args;
    }

    private static Reader getFileReaderFromDir(File file) {
        if (file.exists() && file.length() != 0) {
            try {
                return new FileReader(file);
            } catch (FileNotFoundException e) {
                // Shouldnt happen. Nevertheless ...
                throw new IllegalStateException("Cannot find " + file,e);
            }
        } else {
            return null;
        }
    }

    public static JsonObject readDockerConfig() {
        String dockerConfig = EnvUtil.getEnv("DOCKER_CONFIG");

        Reader reader = dockerConfig == null
                ? getFileReaderFromDir(new File(getHomeDir(),".docker/config.json"))
                : getFileReaderFromDir(new File(dockerConfig,"config.json"));
        return reader != null ? new Gson().fromJson(reader, JsonObject.class) : null;
    }

    public static String[] extractDelimiters(String filter) {
        if (filter == null ||
            filter.equalsIgnoreCase("false") ||
            filter.equalsIgnoreCase("none")) {
            return null;
        }
        if (filter.contains("*")) {
            Matcher matcher = Pattern.compile("^(?<start>[^*]+)\\*(?<end>.*)$").matcher(filter);
            if (matcher.matches()) {
                return new String[] { matcher.group("start"), matcher.group("end") };
            }
        }
        return new String[] { filter, filter };
    }

    public static Map<String,?> readKubeConfig() {
        String kubeConfig = System.getenv("KUBECONFIG");

        Reader reader = kubeConfig == null
                ? getFileReaderFromDir(new File(getHomeDir(),".kube/config"))
                : getFileReaderFromDir(new File(kubeConfig));
        if (reader != null) {
            Yaml ret = new Yaml();
            return (Map<String, ?>) ret.load(reader);
        }
        return null;
    }

    private static File getHomeDir() {
        return new File(getUserHome());
    }

    private static void updateMapWithArgValue(Map<String, String> result, Map<String, String> args, String argString) {
        if (argString.contains("=") || argString.contains(":")) {
            String[] argStringParts = argString.split("[=:]");
            String argStringKey = argStringParts[0];
            String argStringValue = determineFinalArgValue(argString, argStringParts, args);
            if (argStringValue.startsWith("\"") || argStringValue.startsWith("'")) {
                // Strip any single or double quote characters (the anchored ^ and $ alternatives in the
                // previous "^\"|\"|'|'$" were redundant since the bare " and ' already match anywhere).
                argStringValue = argStringValue.replaceAll("[\"']", "");
            } else {
                validateArgValue(argStringValue);
            }
            result.put(argStringKey, argStringValue);
        } else {
            validateArgValue(argString);
            Map<String, String> argFromBuildConfig = fetchArgsFromBuildConfiguration(argString, args);
            if (argFromBuildConfig.isEmpty()) {
                // Keep earlier/default values for repeated ARG declarations with no value.
                // If no value is known yet, represent it as empty string.
                result.putIfAbsent(argString, "");
            } else {
                result.putAll(argFromBuildConfig);
            }
        }
    }

    private static String determineFinalArgValue(String argString, String[] argStringParts, Map<String, String> args) {
        String argStringValue = argString.substring(argStringParts[0].length() + 1);
        if(args == null || args.get(argStringParts[0]) == null){
            return argStringValue;
        }
        return args.getOrDefault(argStringParts[0], argStringValue);
    }

    private static Map<String, String> fetchArgsFromBuildConfiguration(String argString, Map<String, String> args) {
        Map<String, String> argFromBuildConfig = new HashMap<>();
        if (args != null && args.containsKey(argString) && args.get(argString) != null) {
            argFromBuildConfig.put(argString, args.get(argString));
        }
        return argFromBuildConfig;
    }

    private static void validateArgValue(String argStringParam) {
        String[] argStringParts = argStringParam.split("\\s+");
        if (argStringParts.length > 1) {
            throw new IllegalArgumentException("Dockerfile parse error: ARG requires exactly one argument. Provided : " + argStringParam);
        }
    }
}
