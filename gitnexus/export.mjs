// 레포를 GitNexus 로 인덱싱하고, 파일 단위 의존성 그래프를 JSON 으로 쓴다.
//
//   node export.mjs <레포 디렉터리> <출력 JSON> [최대 노드 수]
//
// GitNexus CLI 의 cypher 출력은 마크다운 표라서, 인덱스(LadybugDB)를 같은 패키지의 어댑터로 직접 읽는다.
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const [repoArg, outArg, maxArg] = process.argv.slice(2);
if (!repoArg || !outArg) {
  console.error("usage: node export.mjs <repo-dir> <out.json> [max-nodes]");
  process.exit(2);
}
const repo = resolve(repoArg);
const maxNodes = Number(maxArg ?? 400);

// 파일 사이 의존으로 보는 관계. 구조(CONTAINS, DEFINES, HAS_METHOD …)와 클러스터·흐름(MEMBER_OF, STEP_IN_PROCESS)은 뺀다
const DEP_TYPES = ["IMPORTS", "CALLS", "INJECTS", "EXTENDS", "IMPLEMENTS", "METHOD_IMPLEMENTS", "METHOD_OVERRIDES",
  "ACCESSES", "USES"];

const require = createRequire(import.meta.url);
const cli = require.resolve("gitnexus/dist/cli/index.js");
// 레지스트리(~/.gitnexus)는 실행마다 따로 두고 끝나면 지운다
const home = mkdtempSync(join(tmpdir(), "gitnexus-home-"));
// 분석기 자기 식별(설치 파일 해시) 결과를 실행 사이에 재사용한다. 없으면 매번 수십 초~수 분을 쓴다
const identityCache = process.env.GITNEXUS_ANALYZER_IDENTITY_CACHE_DIR ?? join(tmpdir(), "prguard-gitnexus-identity");
mkdirSync(identityCache, { recursive: true });
const env = {
  ...process.env,
  GITNEXUS_HOME: home,
  // 심볼릭 링크·8.3 짧은 이름이 섞인 경로는 GitNexus 가 거부한다
  GITNEXUS_ANALYZER_IDENTITY_CACHE_DIR: realpathSync.native(identityCache),
  SCARF_ANALYTICS: "false",
  DO_NOT_TRACK: "1",
  // GitNexus 는 DB 버퍼 풀(호스트 RAM 의 80%, 최대 2GB)과 파서 워커 수(코어 수 - 1)를 컨테이너가 아니라 호스트 기준으로 잡는다.
  // 컨테이너 한도를 넘겨 강제 종료되지 않게 작게 고정한다
  GITNEXUS_LBUG_BUFFER_POOL_SIZE: process.env.GITNEXUS_LBUG_BUFFER_POOL_SIZE ?? String(256 * 1024 * 1024),
  GITNEXUS_WORKER_POOL_SIZE: process.env.GITNEXUS_WORKER_POOL_SIZE ?? "2",
};
// 아래에서 인덱스를 읽을 때도 같은 버퍼 풀 한도를 쓴다
process.env.GITNEXUS_LBUG_BUFFER_POOL_SIZE = env.GITNEXUS_LBUG_BUFFER_POOL_SIZE;

try {
  const started = Date.now();
  // AGENTS.md · 스킬 파일을 레포에 쓰지 않고(--index-only), 검색 확장도 받지 않는다(--skip-fts)
  // 힙 크기를 직접 주면 GitNexus 가 큰 힙으로 자기 자신을 다시 띄우지 않는다
  const analyze = spawnSync(process.execPath, [`--max-old-space-size=${process.env.GRAPH_HEAP_MB ?? 2048}`, cli, "analyze", repo, "--index-only", "--skip-fts", "--skip-git"],
    { cwd: repo, env, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  if (analyze.status !== 0) {
    // 경고 로그(JSON 한 줄씩)는 원인과 상관없어서 뺀다
    const tail = `${analyze.stderr ?? ""}\n${analyze.stdout ?? ""}`
      .split("\n")
      .filter((line) => !line.startsWith('{"level":40'))
      .join("\n")
      .trim()
      .slice(-1500);
    const oom = analyze.signal === "SIGKILL" ? ` — 메모리 부족으로 강제 종료된 것 같다 (${memoryInfo()})` : "";
    throw new Error(`gitnexus analyze 실패 (${analyze.status ?? analyze.signal})${oom}: ${tail}`);
  }
  const analyzeMs = Date.now() - started;

  process.env.GITNEXUS_HOME = home;
  const { initLbug, executeQuery, closeLbug } = await import("gitnexus/dist/core/lbug/pool-adapter.js");
  const id = "export";
  await initLbug(id, join(repo, ".gitnexus", "lbug"));
  let rows;
  try {
    const q = (cypher) => executeQuery(id, cypher);
    rows = {
      files: await q("MATCH (f:File) RETURN f.filePath AS file"),
      symbols: await q(`MATCH (f:File)-[r:CodeRelation]->(s) WHERE r.type = 'DEFINES'
                        RETURN f.filePath AS file, count(*) AS n`),
      deps: await q(`MATCH (a)-[r:CodeRelation]->(b)
                     WHERE r.type IN [${DEP_TYPES.map((t) => `'${t}'`).join(", ")}]
                       AND a.filePath IS NOT NULL AND b.filePath IS NOT NULL AND a.filePath <> b.filePath
                     RETURN a.filePath AS src, b.filePath AS dst, r.type AS type, count(*) AS n`),
      members: await q(`MATCH (s)-[r:CodeRelation]->(c:Community) WHERE r.type = 'MEMBER_OF'
                        RETURN s.filePath AS file, c.id AS id, c.label AS label, count(*) AS n`),
    };
  } finally {
    await closeLbug(id).catch(() => {});
  }
  writeFileSync(outArg, JSON.stringify(toGraph(rows, analyzeMs)));
} catch (e) {
  console.error(e instanceof Error ? e.message : String(e));
  process.exitCode = 1;
} finally {
  rmSync(home, { recursive: true, force: true });
}

/** 컨테이너(cgroup v2) 메모리 한도와 지금까지의 최대 사용량 */
function memoryInfo() {
  const mb = (file) => {
    try {
      const v = readFileSync(`/sys/fs/cgroup/${file}`, "utf8").trim();
      return v === "max" ? "무제한" : `${Math.round(Number(v) / 1048576)}MB`;
    } catch {
      return "?";
    }
  };
  return `컨테이너 한도 ${mb("memory.max")}, 최대 사용 ${mb("memory.peak")}`;
}

function toGraph({ files, symbols, deps, members }, analyzeMs) {
  const num = (v) => Number(v); // LadybugDB 는 count 를 BigInt 로 줄 수 있다

  // 파일 쌍마다 관계 종류별 개수를 합친다
  const edges = new Map();
  for (const { src, dst, type, n } of deps) {
    const key = `${src}\n${dst}`;
    const e = edges.get(key) ?? { source: src, target: dst, weight: 0, types: {} };
    e.weight += num(n);
    e.types[type] = (e.types[type] ?? 0) + num(n);
    edges.set(key, e);
  }

  // 파일의 클러스터 = 그 파일 심볼이 가장 많이 속한 GitNexus 커뮤니티
  const community = new Map();
  for (const { file, id, label, n } of members) {
    if (!file) continue;
    const best = community.get(file);
    if (!best || num(n) > best.n) community.set(file, { id, label, n: num(n) });
  }
  const symbolCount = new Map(symbols.map((r) => [r.file, num(r.n)]));

  const degree = new Map();
  for (const e of edges.values()) {
    degree.set(e.source, (degree.get(e.source) ?? 0) + e.weight);
    degree.set(e.target, (degree.get(e.target) ?? 0) + e.weight);
  }
  // 연결이 있는 파일만, 많으면 연결이 많은 순으로 자른다
  const kept = [...degree.keys()].sort((a, b) => degree.get(b) - degree.get(a) || a.localeCompare(b)).slice(0, maxNodes);
  const keep = new Set(kept);

  const nodes = kept.sort().map((file) => ({
    id: file,
    community: community.get(file)?.id ?? null,
    symbols: symbolCount.get(file) ?? 0,
  }));
  const usedCommunities = new Map();
  for (const n of nodes) {
    if (!n.community) continue;
    const c = community.get(n.id);
    const entry = usedCommunities.get(c.id) ?? { id: c.id, label: c.label, files: 0 };
    entry.files++;
    usedCommunities.set(c.id, entry);
  }
  const keptEdges = [...edges.values()].filter((e) => keep.has(e.source) && keep.has(e.target));

  return {
    nodes,
    edges: keptEdges,
    communities: [...usedCommunities.values()].sort((a, b) => b.files - a.files),
    stats: {
      files: files.length,
      connectedFiles: degree.size,
      shownFiles: nodes.length,
      edges: edges.size,
      shownEdges: keptEdges.length,
      truncated: degree.size > nodes.length,
      analyzeMs,
    },
  };
}
