package com.prguard.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** Deliberately small parser: registry dependencies, with exact versions only. */
final class DependencyManifest {
    private static final int MAX_BYTES = 1_048_576;
    private static final Pattern NPM_NAME = Pattern.compile("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*");
    private static final Pattern PYPI_NAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");
    private static final Pattern NPM_VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?");
    private static final Pattern PYPI_VERSION = Pattern.compile("[0-9][A-Za-z0-9.!+_-]*");
    private static final Pattern REQUIREMENT = Pattern.compile(
            "^([A-Za-z0-9][A-Za-z0-9._-]*)(?:\\[[A-Za-z0-9_, .-]+])?\\s*==\\s*([0-9][A-Za-z0-9.!+_-]*)(?:\\s*;.*)?$");
    private static final Pattern REQUIREMENT_PREFIX = Pattern.compile(
            "^([A-Za-z0-9][A-Za-z0-9._-]*)(?:\\[.*?])?\\s*(?:[<>=!~;@]|$)");

    record Dependency(String ecosystem, String name, String version) {
        String key() { return ecosystem + ":" + name + "@" + version; }
    }

    record Unresolved(String ecosystem, String name, String identity) { }
    record Manifest(boolean exists, Map<String, Dependency> dependencies, List<Unresolved> unresolved) { }

    static boolean validPackage(String ecosystem, String name, String version) {
        if (name.length() > 214 || version.length() > 128) return false;
        return switch (ecosystem) {
            case "npm" -> NPM_NAME.matcher(name).matches() && !name.contains("..")
                    && NPM_VERSION.matcher(version).matches();
            case "pypi" -> PYPI_NAME.matcher(name).matches() && PYPI_VERSION.matcher(version).matches();
            default -> false;
        };
    }

    static boolean supported(String path) {
        return path.equals("package.json") || path.endsWith("/package.json")
                || path.equals("package-lock.json") || path.endsWith("/package-lock.json")
                || path.equals("requirements.txt") || path.endsWith("/requirements.txt");
    }

    static String manifestPath(String path) {
        return path.endsWith("package-lock.json")
                ? path.substring(0, path.length() - "package-lock.json".length()) + "package.json" : path;
    }

    static Manifest read(Path root, String relative, boolean includeDev, ObjectMapper mapper) throws IOException {
        String content = readFile(root, relative);
        Map<String, Dependency> result = new LinkedHashMap<>();
        List<Unresolved> unresolved = new ArrayList<>();
        if (content == null) return new Manifest(false, Map.of(), List.of());
        if (relative.endsWith("package.json")) {
            JsonNode manifest = mapper.readTree(content);
            if (manifest == null || !manifest.isObject()) throw new IOException("package.json 형식 불일치");
            String lockPath = relative.substring(0, relative.length() - "package.json".length()) + "package-lock.json";
            String lockContent = readFile(root, lockPath);
            JsonNode lock = lockContent == null ? mapper.createObjectNode() : mapper.readTree(lockContent);
            if (lock == null || !lock.isObject()) throw new IOException("package-lock.json 형식 불일치");
            for (String section : includeDev ? List.of("dependencies", "devDependencies", "optionalDependencies")
                    : List.of("dependencies", "optionalDependencies")) {
                JsonNode dependencies = manifest.path(section);
                if (!dependencies.isMissingNode() && !dependencies.isObject()) {
                    throw new IOException("package.json 의존성 형식 불일치");
                }
                for (var field : dependencies.properties()) {
                    String name = field.getKey();
                    if (!field.getValue().isTextual()) {
                        unresolved.add(unresolved("npm", name, name + "=" + field.getValue()));
                        continue;
                    }
                    String spec = field.getValue().asText("");
                    String version = spec;
                    // npm v2/v3 lockfile: require root spec agreement before using a resolved version.
                    if (!NPM_VERSION.matcher(spec).matches() && spec.matches("[~^]?[0-9][0-9A-Za-z.*+<>=|~^ -]*")
                            && (lock.path("lockfileVersion").asInt() == 2 || lock.path("lockfileVersion").asInt() == 3)
                            && lock.path("packages").path("").path(section).path(name).asText().equals(spec)) {
                        version = lock.path("packages").path("node_modules/" + name).path("version").asText();
                    }
                    if (validPackage("npm", name, version)) {
                        Dependency dependency = new Dependency("npm", name, version);
                        result.put(dependency.key(), dependency);
                    } else {
                        unresolved.add(unresolved("npm", name, name + "=" + spec));
                    }
                }
            }
        } else {
            for (String raw : content.lines().toList()) {
                String line = raw.replaceFirst("\\s+#.*$", "").strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                var match = REQUIREMENT.matcher(line);
                if (!match.matches()) {
                    var prefix = REQUIREMENT_PREFIX.matcher(line);
                    unresolved.add(unresolved("pypi", prefix.find() ? prefix.group(1).toLowerCase(Locale.ROOT)
                            .replaceAll("[-_.]+", "-") : null, line));
                    continue;
                }
                String name = match.group(1).toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
                if (!validPackage("pypi", name, match.group(2))) {
                    unresolved.add(unresolved("pypi", name, line));
                    continue;
                }
                Dependency dependency = new Dependency("pypi", name, match.group(2));
                result.put(dependency.key(), dependency);
            }
        }
        return new Manifest(true, Collections.unmodifiableMap(result), List.copyOf(unresolved));
    }

    private static Unresolved unresolved(String ecosystem, String name, String declaration) {
        try {
            // Compare declarations without retaining URLs, credentials or arbitrary raw requirement text.
            String identity = ecosystem + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(declaration.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new Unresolved(ecosystem, name != null && validPackage(ecosystem, name, "1.0.0") ? name : null, identity);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readFile(Path root, String relative) throws IOException {
        Path absoluteRoot = root.toRealPath();
        Path path = absoluteRoot.resolve(relative).normalize();
        if (!path.startsWith(absoluteRoot)) throw new IOException("의존성 파일 경로가 소스 밖을 가리킴");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!path.toRealPath().startsWith(absoluteRoot) || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) {
            throw new IOException("의존성 파일 경로 또는 크기 제한 위반");
        }
        // Bounded read even if a checkout changes while being read.
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("의존성 파일 크기 제한 초과");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
