package com.prguard.analysis;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyManifestTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();

    @Test void pathsCannotEscapeCheckoutAndOversizedFilesAreRejected() throws Exception {
        assertThatThrownBy(() -> DependencyManifest.read(root, "../package.json", true, mapper))
                .isInstanceOf(java.io.IOException.class);
        Files.writeString(root.resolve("package.json"), "x".repeat(1_048_577));
        assertThatThrownBy(() -> DependencyManifest.read(root, "package.json", true, mapper))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test void unsupportedRequirementsAreNotSentToScanner() throws Exception {
        Files.writeString(root.resolve("requirements.txt"), """
                # comment
                Requests[security]==2.31.0 # pinned
                -r other.txt
                bad @ https://example.com/pkg.tgz
                unpinned>=1.0
                wildcard==1.*
                """);
        var manifest = DependencyManifest.read(root, "requirements.txt", true, mapper);
        assertThat(manifest.dependencies().keySet()).containsExactly("pypi:requests@2.31.0");
        assertThat(manifest.unresolved()).hasSize(4);
    }

    @Test void staleLockDoesNotResolveRangeAndDevCanBeExcluded() throws Exception {
        Files.writeString(root.resolve("package.json"), """
                {"dependencies":{"pkg":"^2.0.0"},"devDependencies":{"dev":"1.0.0"}}
                """);
        Files.writeString(root.resolve("package-lock.json"), """
                {"lockfileVersion":3,"packages":{"":{"dependencies":{"pkg":"^1.0.0"}},"node_modules/pkg":{"version":"1.5.0"}}}
                """);
        var manifest = DependencyManifest.read(root, "package.json", false, mapper);
        assertThat(manifest.dependencies()).isEmpty();
        assertThat(manifest.unresolved()).hasSize(1);
    }

    @Test void identifiersRejectOptionsUrlsAndLocalPaths() {
        assertThat(DependencyManifest.validPackage("npm", "@scope/pkg", "1.2.3-beta.1")).isTrue();
        for (String name : List.of("--help", "../pkg", "https://example.com/pkg", "@../pkg", "pkg;echo hi")) {
            assertThat(DependencyManifest.validPackage("npm", name, "1.0.0")).isFalse();
        }
        assertThat(DependencyManifest.validPackage("pypi", "pkg", "--help")).isFalse();
    }
}
