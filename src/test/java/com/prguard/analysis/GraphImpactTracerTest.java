package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.diff.Patch;
import com.prguard.diff.PatchParser;
import com.prguard.graph.CallGraph;
import com.prguard.index.RepoIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphImpactTracerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * web/api.ts#fetchUser(1-10) ← web/page.tsx#load(1-20) ← web/app.tsx#main(1-30),
     * web/api.ts#other(12-20) 는 아무도 부르지 않음
     */
    private static CallGraph graph(String sha) throws Exception {
        return CallGraph.parse(sha, true, MAPPER.readTree("""
                {"files": ["web/api.ts", "web/page.tsx", "web/app.tsx"],
                 "functions": [[0, "fetchUser", 1, 10], [0, "other", 12, 20], [1, "load", 1, 20], [2, "main", 1, 30]],
                 "calls": [[2, 0], [3, 2]],
                 "truncated": false}
                """));
    }

    private static ChangedFile modified(String file, String patch) {
        return new ChangedFile(file, "modified", 1, 1, patch, null, PatchParser.parse(patch));
    }

    @Test
    void followsCallersAcrossFilesAndLanguages() throws Exception {
        ChangedFile api = modified("web/api.ts", """
                @@ -4,3 +4,3 @@
                 const a = 1;
                -return get(a);
                +return get(a, true);
                 }
                """);

        GraphImpact impact = GraphImpactTracer.trace(graph("abc"), List.of(api), "abc", RepoIndex.empty(), List.of());

        assertThat(impact.changed()).extracting(GraphImpact.Fn::name).containsExactly("fetchUser");
        assertThat(impact.impacted()).extracting(GraphImpact.Fn::name, GraphImpact.Fn::depth)
                .containsExactly(tuple("load", 1),
                        tuple("main", 2));
        assertThat(impact.impacted().get(1).via()).isEqualTo("web/page.tsx#load");
        assertThat(impact.impactedFiles()).containsExactly("web/page.tsx", "web/app.tsx");
        assertThat(impact.baseMatches()).isTrue();
        assertThat(impact.complete()).isTrue();
        assertThat(impact.unmapped()).isEmpty();
    }

    @Test
    void onlyFunctionsAroundRemovedLinesCount() throws Exception {
        ChangedFile api = modified("web/api.ts", """
                @@ -14,2 +14,2 @@
                -  x();
                +  y();
                 }
                """);

        GraphImpact impact = GraphImpactTracer.trace(graph("abc"), List.of(api), "def", RepoIndex.empty(), List.of());

        assertThat(impact.changed()).extracting(GraphImpact.Fn::name).containsExactly("other");
        assertThat(impact.impacted()).isEmpty();
        assertThat(impact.baseMatches()).isFalse();
    }

    @Test
    void newFilesAreUnmapped() throws Exception {
        ChangedFile added = new ChangedFile("web/new.ts", "added", 3, 0, "@@ -0,0 +1,1 @@\n+export {}\n", null,
                PatchParser.parse("@@ -0,0 +1,1 @@\n+export {}\n"));

        GraphImpact impact = GraphImpactTracer.trace(graph("abc"), List.of(added), "abc", RepoIndex.empty(), List.of());

        assertThat(impact.changed()).isEmpty();
        assertThat(impact.unmapped()).containsExactly("web/new.ts");
    }

    @Test
    void removedFileTouchesAllItsFunctions() throws Exception {
        ChangedFile removed = new ChangedFile("web/api.ts", "removed", 0, 20, null, null,
                Patch.EMPTY);

        GraphImpact impact = GraphImpactTracer.trace(graph("abc"), List.of(removed), "abc", RepoIndex.empty(),
                List.of());

        assertThat(impact.changed()).extracting(GraphImpact.Fn::name).containsExactly("fetchUser", "other");
        assertThat(impact.impacted()).extracting(GraphImpact.Fn::name).containsExactly("load", "main");
    }

    @Test
    void readsOldDisplayGraphFormat() throws Exception {
        CallGraph old = CallGraph.parse("abc", false, MAPPER.readTree("""
                {"functions": [{"id": "f1", "name": "a", "file": "A.java", "line": 1, "endLine": 5},
                               {"id": "f2", "name": "b", "file": "B.java", "line": 1, "endLine": 9}],
                 "calls": [{"source": "f2", "target": "f1", "weight": 1}]}
                """));

        assertThat(old.complete()).isFalse();
        CallGraph.Function a = old.functionsIn("A.java").get(0);
        assertThat(old.callersOf(a)).extracting(CallGraph.Function::name).containsExactly("b");
    }
}
