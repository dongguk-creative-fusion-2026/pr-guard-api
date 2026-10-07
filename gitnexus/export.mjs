// 레포를 GitNexus 로 인덱싱하고, 의존성 그래프를 JSON 으로 쓴다.
// 파일 단위 의존(nodes, edges)과 그 파일들 안의 함수 · 함수 간 호출(functions, calls)을 같이 담는다.
//
//   node export.mjs <레포 디렉터리> <출력 JSON> [최대 파일 수] [최대 함수 수]
//
// GitNexus CLI 의 cypher 출력은 마크다운 표라서, 인덱스(LadybugDB)를 같은 패키지의 어댑터로 직접 읽는다.
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { extractInfra, loadYamlLib } from "./infra.mjs";

const [repoArg, outArg, maxArg, maxFunctionsArg] = process.argv.slice(2);
if (!repoArg || !outArg) {
  console.error("usage: node export.mjs <repo-dir> <out.json> [max-files] [max-functions]");
  process.exit(2);
}
const repo = resolve(repoArg);
const maxNodes = Number(maxArg ?? 400);
const maxFunctions = Number(maxFunctionsArg ?? 2000);
const FUNCTION_LABELS = ["Method", "Function", "Constructor"];
// git 이력: 최근 커밋 수
const MAX_COMMITS = 1000;
// 한 번에 많은 파일을 바꾼 커밋(일괄 포맷 · 이름 변경 등)은 "같이 바뀐다"의 근거가 약해서 동시 변경에서 뺀다
const MAX_FILES_PER_COMMIT = 40;
const MIN_CO_SUPPORT = 2;
const MAX_CO_PAIRS = 400;


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
      // Java record 의 컴포넌트도 Method 로 잡히는데 본문에 괄호가 없다. 그건 뺀다
      functions: await q(`MATCH (n) WHERE label(n) IN ${cypherList(FUNCTION_LABELS)} AND n.filePath IS NOT NULL
                            AND n.content CONTAINS '('
                          RETURN n.id AS id, n.name AS name, label(n) AS kind, n.filePath AS file,
                                 n.startLine AS line, n.endLine AS endLine`),
      // API 엔드포인트와 그걸 처리하는 파일 (인프라 지도에서 요청이 들어오는 코드)
      routes: await q("MATCH (rt:Route) RETURN rt.method AS method, rt.name AS path, rt.filePath AS file"),
      calls: await q(`MATCH (a)-[r:CodeRelation]->(b) WHERE r.type = 'CALLS'
                        AND label(a) IN ${cypherList(FUNCTION_LABELS)} AND label(b) IN ${cypherList(FUNCTION_LABELS)}
                        AND a.id <> b.id
                      RETURN a.id AS source, b.id AS target, count(*) AS n`),
    };
  } finally {
    await closeLbug(id).catch(() => {});
  }
  const graph = toGraph(rows, analyzeMs);
  // 파일 줄 수와 git 이력 (코드 시티 · 핫스팟 · 숨은 결합)
  for (const node of graph.nodes) node.lines = countLines(join(repo, node.id));
  graph.history = readHistory(repo, new Set(graph.nodes.map((n) => n.id)));
  // 인프라 지도: 요청 경로(client → DNS → Tunnel → 프록시 → 앱)와 DB · 외부 API, 그리고 그걸 쓰는 코드 파일
  await loadYamlLib();
  graph.infra = extractInfra(repo, graph.nodes.map((n) => n.id), rows.routes.filter((r) => r.file));
  writeFileSync(outArg, JSON.stringify(graph));
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

function countLines(path) {
  try {
    const text = readFileSync(path, "utf8");
    return text.length === 0 ? 0 : text.split("\n").length - (text.endsWith("\n") ? 1 : 0);
  } catch {
    return 0;
  }
}

/**
 * 최근 커밋의 파일별 변경 통계와, 같이 바뀐 파일 쌍을 센다. 그래프에 있는 파일만.
 * 얕은 clone 이면 가장 오래된 커밋은 레포 전체가 추가된 것처럼 보이므로 뺀다.
 */
function readHistory(repoDir, files) {
  const git = (...args) => spawnSync("git", ["-C", repoDir, ...args], { encoding: "utf8", maxBuffer: 512 * 1024 * 1024 });
  const log = git("log", "--no-merges", "--no-renames", `-n${MAX_COMMITS}`, "--format=%x1e%H%x1f%an%x1f%at", "--numstat");
  if (log.status !== 0) return null;
  const chunks = log.stdout.split("\x1e").filter((c) => c.trim());
  if (git("rev-parse", "--is-shallow-repository").stdout.trim() === "true" && chunks.length > 1) chunks.pop();

  const stats = new Map();
  const pairs = new Map();
  // 시간 여행용 커밋별 변경. 경로 · 작성자는 번호로 줄인다
  const fileIndex = new Map([...files].map((path, i) => [path, i]));
  const authorIndex = new Map();
  const timeline = [];
  let since = null;
  let until = null;
  for (const chunk of chunks) {
    const [header, ...lines] = chunk.split("\n");
    const [, author, at] = header.split("\x1f");
    const time = Number(at) * 1000;
    until ??= time;
    since = time;
    const touched = [];
    const changes = [];
    for (const line of lines) {
      const m = /^(\d+|-)\t(\d+|-)\t(.+)$/.exec(line);
      if (!m || !files.has(m[3])) continue;
      const path = m[3];
      // 로그는 최신 커밋부터 나온다
      const s = stats.get(path) ?? { commits: 0, additions: 0, deletions: 0, authors: new Map(), lastAt: time, firstAt: time };
      s.commits++;
      s.additions += m[1] === "-" ? 0 : Number(m[1]);
      s.deletions += m[2] === "-" ? 0 : Number(m[2]);
      s.authors.set(author, (s.authors.get(author) ?? 0) + 1);
      s.firstAt = time;
      stats.set(path, s);
      touched.push(path);
      changes.push([fileIndex.get(path), m[1] === "-" ? 0 : Number(m[1]), m[2] === "-" ? 0 : Number(m[2])]);
    }
    if (changes.length > 0) {
      if (!authorIndex.has(author)) authorIndex.set(author, authorIndex.size);
      timeline.push({ at: Number(at), a: authorIndex.get(author), c: changes });
    }
    if (touched.length < 2 || touched.length > MAX_FILES_PER_COMMIT) continue;
    touched.sort();
    for (let i = 0; i < touched.length; i++) {
      for (let j = i + 1; j < touched.length; j++) {
        const key = `${touched[i]}\n${touched[j]}`;
        pairs.set(key, (pairs.get(key) ?? 0) + 1);
      }
    }
  }

  const coChanges = [...pairs.entries()]
    .filter(([, n]) => n >= MIN_CO_SUPPORT)
    .map(([key, support]) => {
      const [a, b] = key.split("\n");
      // 둘 중 덜 바뀐 파일 기준으로, 그 파일이 바뀔 때 다른 파일도 같이 바뀐 비율
      const confidence = support / Math.min(stats.get(a).commits, stats.get(b).commits);
      return { a, b, support, confidence: Math.round(confidence * 100) / 100 };
    })
    .sort((x, y) => y.support - x.support || y.confidence - x.confidence)
    .slice(0, MAX_CO_PAIRS);

  const fileStats = {};
  for (const [path, s] of stats) {
    const [topAuthor] = [...s.authors.entries()].sort((a, b) => b[1] - a[1])[0];
    fileStats[path] = {
      commits: s.commits,
      additions: s.additions,
      deletions: s.deletions,
      authors: s.authors.size,
      topAuthor,
      lastAt: new Date(s.lastAt).toISOString(),
      firstAt: new Date(s.firstAt).toISOString(),
    };
  }
  return {
    commits: chunks.length,
    since: since === null ? null : new Date(since).toISOString(),
    until: until === null ? null : new Date(until).toISOString(),
    files: fileStats,
    coChanges,
    // 오래된 커밋부터. c = [[파일 번호, 추가, 삭제]], 파일 번호는 timelineFiles, 작성자 번호는 authors
    timelineFiles: [...files],
    authors: [...authorIndex.keys()],
    // git log 순서와 작성 시각이 어긋나는 커밋(리베이스 등)이 있어 시각으로 정렬한다
    timeline: timeline.sort((x, y) => x.at - y.at),
  };
}

function cypherList(values) {
  return `[${values.map((v) => `'${v}'`).join(", ")}]`;
}

function toGraph({ files, symbols, deps, members, functions, calls }, analyzeMs) {
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
  const ranked = [...degree.keys()].sort((a, b) => degree.get(b) - degree.get(a) || a.localeCompare(b)).slice(0, maxNodes);
  const keep = new Set(ranked);

  // 함수: 남긴 파일 안의 것만, 연결 많은 파일부터 maxFunctions 개까지 (한 파일은 통째로 넣거나 뺀다)
  const functionsByFile = new Map();
  for (const f of functions) {
    if (!keep.has(f.file)) continue;
    functionsByFile.set(f.file, [...(functionsByFile.get(f.file) ?? []), f]);
  }
  const keptFunctions = [];
  for (const file of ranked) {
    const list = (functionsByFile.get(file) ?? []).sort((a, b) => num(a.line) - num(b.line));
    if (keptFunctions.length + list.length > maxFunctions) continue;
    keptFunctions.push(...list);
  }
  const functionIds = new Set(keptFunctions.map((f) => f.id));
  const keptCalls = calls
    .filter((c) => functionIds.has(c.source) && functionIds.has(c.target))
    .map((c) => ({ source: c.source, target: c.target, weight: num(c.n) }));

  const nodes = [...ranked].sort().map((file) => ({
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
    // kind: Method, Function, Constructor. line·endLine: 1 부터
    functions: keptFunctions.map((f) => ({
      id: f.id,
      name: f.name,
      kind: f.kind,
      file: f.file,
      line: num(f.line),
      endLine: num(f.endLine),
    })),
    calls: keptCalls,
    stats: {
      files: files.length,
      connectedFiles: degree.size,
      shownFiles: nodes.length,
      edges: edges.size,
      shownEdges: keptEdges.length,
      truncated: degree.size > nodes.length,
      functions: functions.length,
      shownFunctions: keptFunctions.length,
      shownCalls: keptCalls.length,
      analyzeMs,
    },
  };
}
