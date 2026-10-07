// 레포 안 인프라 정의 파일에서 "요청이 어떻게 들어와서 무엇과 연결되는지"를 뽑는다.
//   client → DNS → Cloudflare Tunnel → 프록시/Ingress → 앱 → DB · 캐시 · 큐 · 외부 API
//
// 읽는 것: docker-compose, 쿠버네티스 매니페스트, cloudflared 설정, nginx · Caddy, Dockerfile,
//          Spring 설정(application*.yml|properties), .env.example, 배포 플랫폼 설정, GitHub Actions
// 코드와 잇는 것: 진입점(GitNexus 라우트를 처리하는 파일), DB 를 쓰는 파일, 외부 API 를 부르는 파일
//
// 모든 노드 · 연결에는 근거 파일과 줄을 남기고, 근거 수준을 표시한다
//   file = 설정 파일에 그대로 적혀 있음, inferred = 이름 · 종류로 추정, missing = 레포에 없음(대시보드 등)
import { readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join, relative } from "node:path";

const SKIP_DIRS = new Set([".git", "node_modules", "build", "target", "dist", "out", ".gitnexus", ".next", "vendor", ".gradle", ".idea"]);
const MAX_DEPTH = 6;
const MAX_FILE_BYTES = 512 * 1024;

/** 이미지 · 이름으로 종류를 정한다 (먼저 맞는 것) */
const KIND_BY_IMAGE = [
  ["tunnel", /cloudflared|cloudflare\/|ngrok|tailscale/],
  ["proxy", /(^|\/)(nginx|traefik|caddy|envoy|haproxy|kong)(:|$|-)/],
  ["database", /(^|\/)(postgres|postgis|timescale|mysql|mariadb|mongo|cassandra|cockroach|mssql|oracle|neo4j|clickhouse|elasticsearch|opensearch)/],
  ["cache", /(^|\/)(redis|valkey|memcached|keydb|dragonfly)/],
  ["queue", /(^|\/)(rabbitmq|kafka|nats|redpanda|activemq|pulsar|zookeeper)/],
  ["storage", /(^|\/)(minio|localstack|azurite)/],
  ["monitoring", /(^|\/)(prometheus|grafana|loki|jaeger|zipkin|otel|tempo)/],
];

const DB_SCHEME = [
  ["postgres", /jdbc:postgresql|postgres(ql)?:\/\//i],
  ["mysql", /jdbc:mysql|jdbc:mariadb|mysql:\/\//i],
  ["mongo", /mongodb(\+srv)?:\/\//i],
  ["redis", /redis(s)?:\/\//i],
  ["h2", /jdbc:h2/i],
];

// 코드에서 DB 를 쓰는 흔적 · HTTP 클라이언트를 쓰는 흔적
const SOURCE_FILE = /\.(java|kt|kts|scala|groovy|ts|tsx|js|jsx|mjs|cjs|py|go|rs|cs|rb|php)$/;
const DB_CODE = /\b(Jpa|Crud|PagingAndSorting|ListCrud|Mongo|Reactive\w*)Repository\b|JdbcTemplate|JdbcClient|NamedParameterJdbcTemplate|EntityManager|@Query\b|@Repository\b|DataSource\b|prisma\.|mongoose\.|sqlalchemy|knex\(|createPool\(|new Pool\(/;
const HTTP_CODE = /RestClient|WebClient|RestTemplate|HttpClient|OkHttp|Feign|fetch\(|axios|requests\.(get|post)|http\.Get\(/;
const BRANDS = ["github", "openai", "anthropic", "slack", "stripe", "discord", "google", "kakao", "naver", "aws", "s3", "sentry", "twilio", "sendgrid", "firebase", "supabase", "cloudflare"];

export function extractInfra(repoDir, codeFiles, routes) {
  tunnelTokenSeen = null;
  const files = walk(repoDir);
  const text = new Map();
  const read = (rel) => {
    if (!text.has(rel)) {
      try {
        const p = join(repoDir, rel);
        text.set(rel, statSync(p).size > MAX_FILE_BYTES ? "" : readFileSync(p, "utf8"));
      } catch {
        text.set(rel, "");
      }
    }
    return text.get(rel);
  };

  const nodes = new Map();
  const links = [];
  const linkKeys = new Set();
  const node = (id, init) => {
    const existing = nodes.get(id);
    if (existing) {
      for (const s of init.sources ?? []) if (!existing.sources.some((x) => x.file === s.file && x.line === s.line)) existing.sources.push(s);
      if (rank(init.confidence) > rank(existing.confidence)) existing.confidence = init.confidence;
      existing.detail ??= init.detail;
      return existing;
    }
    const n = { id, kind: init.kind, label: init.label, detail: init.detail ?? null, sources: init.sources ?? [], confidence: init.confidence ?? "file", env: init.env ?? null, dir: init.dir ?? null };
    nodes.set(id, n);
    return n;
  };
  const link = (from, to, label, confidence, source) => {
    if (!from || !to || from === to) return;
    const key = `${from}>${to}`;
    if (linkKeys.has(key)) {
      const l = links.find((x) => x.from === from && x.to === to);
      if (rank(confidence) > rank(l.confidence)) l.confidence = confidence;
      return;
    }
    linkKeys.add(key);
    links.push({ from, to, label: label ?? null, confidence, source: source ?? null });
  };
  // 서비스 이름(호스트 이름) → 노드 id
  const hosts = new Map();
  const secrets = new Map(); // 쿠버네티스 Secret · ConfigMap 이름 → 값 문자열 모음

  // 1. docker-compose
  for (const rel of files.filter((f) => /(^|\/)(docker-)?compose(\.[\w-]+)?\.ya?ml$/.test(f))) {
    const doc = loadYaml(read(rel))[0];
    const services = doc?.services;
    if (!services || typeof services !== "object") continue;
    for (const [name, svc] of Object.entries(services)) {
      if (!svc || typeof svc !== "object") continue;
      const image = String(svc.image ?? "");
      const kind = svc.build ? "app" : kindOfImage(image) ?? "app";
      const id = `svc:${name}`;
      const ports = (svc.ports ?? []).map(String);
      node(id, {
        kind,
        label: name,
        detail: [image || (svc.build ? "직접 빌드" : null), ports.length ? `포트 ${ports.join(", ")}` : null].filter(Boolean).join(" · ") || null,
        sources: [{ file: rel, line: lineOf(read(rel), `${name}:`) }],
        dir: svc.build ? buildDir(rel, svc.build) : null,
        env: "docker-compose",
      });
      nodes.get(id).published = ports.length > 0;
      nodes.get(id).volumes = (svc.volumes ?? []).map((v) => String(typeof v === "string" ? v : v.source ?? "").split(":")[0].replace(/^\.\//, ""));
      hosts.set(name, id);
      for (const alias of svc.container_name ? [svc.container_name] : []) hosts.set(String(alias), id);
    }
    for (const [name, svc] of Object.entries(services)) {
      if (!svc || typeof svc !== "object") continue;
      const deps = Array.isArray(svc.depends_on) ? svc.depends_on : Object.keys(svc.depends_on ?? {});
      for (const d of [...deps, ...(svc.links ?? []).map((l) => String(l).split(":")[0])]) {
        link(`svc:${name}`, hosts.get(String(d)), "depends_on", "file", { file: rel, line: lineOf(read(rel), "depends_on") });
      }
      // 주소 성격의 환경 변수(…HOST, …URL, scheme://…)에 다른 서비스 이름이 호스트로 나오면 연결
      for (const value of envValues(svc.environment)) {
        markTunnelToken(value, rel);
        if (!isAddressEntry(value)) continue;
        for (const [host, target] of hosts) {
          if (target !== `svc:${name}` && pointsAt(value, host)) link(`svc:${name}`, target, null, "file", { file: rel, line: lineOf(read(rel), value.slice(0, 40)) });
        }
      }
      for (const key of envKeys(svc.environment)) markTunnelToken(key, rel);
    }
  }

  // 2. 쿠버네티스 매니페스트
  const k8sDocs = [];
  for (const rel of files.filter((f) => /\.ya?ml$/.test(f) && !/(^|\/)(docker-)?compose/.test(f) && !f.startsWith(".github/"))) {
    const raw = read(rel);
    if (!/^\s*apiVersion:/m.test(raw) || !/^\s*kind:/m.test(raw) || raw.includes("{{")) continue;
    for (const doc of loadYaml(raw)) if (doc?.kind && doc?.metadata?.name) k8sDocs.push({ rel, doc, raw });
  }
  for (const { doc } of k8sDocs.filter((d) => ["Secret", "ConfigMap"].includes(d.doc.kind))) {
    // 주소 성격의 값만 (host, url …). database · username 같은 값은 서비스 이름과 우연히 같을 수 있다
    const values = { ...(doc.stringData ?? {}), ...(doc.data ?? {}) };
    secrets.set(doc.metadata.name, Object.entries(values).filter(([k, v]) => isAddressEntry(`${k}=${v}`)).map(([k, v]) => `${k}=${v}`));
  }
  const workloads = k8sDocs.filter((d) => ["Deployment", "StatefulSet", "DaemonSet"].includes(d.doc.kind));
  for (const { rel, doc, raw } of workloads) {
    const containers = doc.spec?.template?.spec?.containers ?? [];
    const image = containers.map((c) => c.image).filter(Boolean).join(", ");
    const kind = kindOfImage(image) ?? "app";
    const id = `k8s:${doc.metadata.name}`;
    node(id, {
      kind,
      label: doc.metadata.name,
      detail: [image, doc.spec?.replicas ? `레플리카 ${doc.spec.replicas}` : null, doc.kind].filter(Boolean).join(" · "),
      sources: [{ file: rel, line: lineOf(raw, `name: ${doc.metadata.name}`) }],
      env: "kubernetes",
    });
    hosts.set(doc.metadata.name, id);
  }
  // Service 는 셀렉터로 워크로드에 붙인다 (Service 이름도 호스트 이름이 된다)
  for (const { doc } of k8sDocs.filter((d) => d.doc.kind === "Service")) {
    const selector = doc.spec?.selector ?? {};
    const target = workloads.find((w) => Object.entries(selector).every(([k, v]) => (w.doc.spec?.template?.metadata?.labels ?? {})[k] === v));
    if (target) {
      hosts.set(doc.metadata.name, `k8s:${target.doc.metadata.name}`);
      if (["NodePort", "LoadBalancer"].includes(doc.spec?.type)) nodes.get(`k8s:${target.doc.metadata.name}`).published = true;
    }
  }
  for (const { rel, doc, raw } of workloads) {
    const id = `k8s:${doc.metadata.name}`;
    const spec = doc.spec?.template?.spec ?? {};
    // 컨테이너 환경 변수 + 참조하는 Secret · ConfigMap 의 주소 성격 값에 다른 서비스 이름이 호스트로 나오면 연결
    const refs = JSON.stringify(spec).match(/"name":"([^"]+)"/g)?.map((m) => m.slice(8, -1)) ?? [];
    const entries = [
      ...(spec.containers ?? []).flatMap((c) => (c.env ?? []).map((e) => `${e.name}=${e.value ?? ""}`)),
      ...refs.flatMap((r) => secrets.get(r) ?? []),
    ];
    for (const value of entries) {
      markTunnelToken(value, rel);
      if (!isAddressEntry(value)) continue;
      for (const [host, target] of hosts) {
        if (target !== id && pointsAt(value, host)) link(id, target, null, "file", { file: rel, line: lineOf(raw, host) });
      }
    }
  }
  for (const { rel, doc, raw } of k8sDocs.filter((d) => d.doc.kind === "Ingress")) {
    const id = `ingress:${doc.metadata.name}`;
    const rules = doc.spec?.rules ?? [];
    node(id, {
      kind: "proxy",
      label: `Ingress ${doc.metadata.name}`,
      detail: rules.map((r) => r.host).filter(Boolean).join(", ") || null,
      sources: [{ file: rel, line: lineOf(raw, "kind: Ingress") }],
    });
    for (const r of rules) {
      if (r.host) {
        node(`dns:${r.host}`, { kind: "dns", label: r.host, sources: [{ file: rel, line: lineOf(raw, r.host) }] });
        link(`dns:${r.host}`, id, null, "file", { file: rel, line: lineOf(raw, r.host) });
      }
      for (const p of r.http?.paths ?? []) {
        const svc = p.backend?.service?.name ?? p.backend?.serviceName;
        link(id, hosts.get(svc), p.path ?? null, "file", { file: rel, line: lineOf(raw, svc) });
      }
    }
  }

  // 3. cloudflared 설정: tunnel + ingress (hostname → service)
  for (const rel of files.filter((f) => /\.ya?ml$/.test(f))) {
    const raw = read(rel);
    if (!/^\s*tunnel:/m.test(raw) || !/^\s*ingress:/m.test(raw)) continue;
    const doc = loadYaml(raw)[0];
    if (!doc?.ingress) continue;
    // compose · k8s 의 cloudflared 컨테이너가 있으면 같은 Tunnel 로 합친다
    const container = [...nodes.values()].find((n) => n.kind === "tunnel" && n.confidence !== "missing" && !n.id.startsWith("tunnel:"));
    const tunnelId = container?.id ?? `tunnel:${doc.tunnel}`;
    node(tunnelId, { kind: "tunnel", label: container?.label ?? "Cloudflare Tunnel", sources: [{ file: rel, line: lineOf(raw, "tunnel:") }] });
    nodes.get(tunnelId).detail = `Cloudflare Tunnel ${String(doc.tunnel).slice(0, 8)}`;
    for (const rule of doc.ingress) {
      const target = String(rule.service ?? "");
      const host = target.replace(/^\w+:\/\//, "").split(/[:/]/)[0];
      if (rule.hostname) {
        node(`dns:${rule.hostname}`, { kind: "dns", label: rule.hostname, detail: "Cloudflare DNS", sources: [{ file: rel, line: lineOf(raw, rule.hostname) }] });
        link(`dns:${rule.hostname}`, tunnelId, null, "file", { file: rel, line: lineOf(raw, rule.hostname) });
      }
      if (host && hosts.has(host)) link(tunnelId, hosts.get(host), rule.hostname ?? null, "file", { file: rel, line: lineOf(raw, target) });
      else if (host && host !== "localhost" && !target.startsWith("http_status")) {
        node(`svc:${host}`, { kind: "app", label: host, detail: target, confidence: "inferred", sources: [{ file: rel, line: lineOf(raw, target) }] });
        hosts.set(host, `svc:${host}`);
        link(tunnelId, `svc:${host}`, rule.hostname ?? null, "file", { file: rel, line: lineOf(raw, target) });
      }
    }
  }

  // 4. nginx · Caddy 리버스 프록시
  for (const rel of files.filter((f) => /(^|\/)(nginx[\w.-]*\.conf|default\.conf|Caddyfile)$/.test(f))) {
    const raw = read(rel);
    const upstreams = [...raw.matchAll(/(?:proxy_pass\s+https?:\/\/|reverse_proxy\s+)([\w.-]+)/g)].map((m) => m[1]);
    if (upstreams.length === 0) continue;
    // 이 설정 파일을 마운트하는 프록시 컨테이너가 있으면 그 노드에 붙인다
    const owner = [...nodes.values()].find((n) => n.kind === "proxy" && (n.volumes ?? []).some((v) => v && (rel === v || rel.startsWith(v.replace(/\/$/, "") + "/"))));
    const id = owner?.id ?? `proxy:${rel}`;
    node(id, { kind: "proxy", label: owner?.label ?? (basename(rel).startsWith("Caddy") ? "Caddy" : "nginx"), detail: owner?.detail ?? rel, sources: [{ file: rel, line: lineOf(raw, upstreams[0]) }] });
    for (const u of upstreams) link(id, hosts.get(u), null, hosts.has(u) ? "file" : "inferred", { file: rel, line: lineOf(raw, u) });
  }

  // 5. 앱: 위에서 못 찾았으면 이 레포 코드 자체를 앱 하나로 둔다
  const dockerfiles = files.filter((f) => /(^|\/)Dockerfile[\w.-]*$/.test(f) && !f.startsWith(".devcontainer/"));
  let apps = [...nodes.values()].filter((n) => n.kind === "app");
  if (apps.length === 0) {
    const df = dockerfiles[0];
    const raw = df ? read(df) : "";
    const expose = raw.match(/^\s*EXPOSE\s+(.+)$/m)?.[1];
    const base = [...raw.matchAll(/^\s*FROM\s+(\S+)/gm)].map((m) => m[1]).pop();
    node("app:repo", {
      kind: "app",
      label: "앱 (이 레포)",
      detail: [base, expose ? `포트 ${expose}` : null].filter(Boolean).join(" · ") || null,
      confidence: df ? "file" : "inferred",
      sources: df ? [{ file: df, line: lineOf(raw, "FROM") }] : [],
    });
    apps = [nodes.get("app:repo")];
  }
  const mainApp = apps.find((a) => a.dir === "" || a.dir === ".") ?? apps[0];

  // 6. 앱 설정: DB 주소 · 외부 API 주소
  const configs = files.filter((f) => /(^|\/)(application|bootstrap)[\w-]*\.(ya?ml|properties)$/.test(f) || /(^|\/)\.env\.(example|sample|template)$/.test(f));
  for (const rel of configs) {
    const raw = read(rel);
    for (const [kind, re] of DB_SCHEME) {
      const m = raw.match(new RegExp(`[^\\s"'=]*(${re.source})[^\\s"'}]*`, "i"));
      if (!m) continue;
      const url = m[0];
      const host = url.replace(/^.*?:\/\//, "").replace(/^[^@]*@/, "").split(/[:/?;]/)[0];
      const viaHost = host && hosts.get(host);
      // 설정에 적힌 호스트가 compose · k8s 서비스와 같으면 확정, 종류만 맞으면 추정
      const byKind = [...nodes.values()].find((n) => ["database", "cache"].includes(n.kind) && nameMatchesKind(n, kind));
      const target = viaHost ?? byKind?.id ?? (kind === "h2" ? null : node(`db:${kind}`, { kind: kind === "redis" ? "cache" : "database", label: kind, detail: url.slice(0, 60), sources: [{ file: rel, line: lineOf(raw, url.slice(0, 30)) }] }).id);
      if (!target) continue;
      for (const app of apps) {
        // 같은 종류 저장소에 이미 설정 파일로 확인된 연결이 있으면 추정 연결은 만들지 않는다
        const confirmed = links.some((l) => l.from === app.id && l.confidence === "file" && nameMatchesKind(nodes.get(l.to), kind));
        if (!viaHost && byKind && confirmed) continue;
        link(app.id, target, kind, viaHost ? "file" : byKind ? "inferred" : "file", { file: rel, line: lineOf(raw, url.slice(0, 30)) });
      }
    }
    for (const m of raw.matchAll(/https?:\/\/([a-z0-9.-]+\.[a-z]{2,})(?::\d+)?[^\s"'}]*/gi)) {
      const host = m[1].toLowerCase();
      if (/localhost|127\.0\.0\.1|example\.(com|org)|schemas?\.|w3\.org|xmlns|springframework\.org/.test(host) || hosts.has(host)) continue;
      const id = `ext:${host}`;
      node(id, { kind: "external", label: host, detail: "외부 API", sources: [{ file: rel, line: lineOf(raw, m[0]) }] });
      link(mainApp.id, id, null, "file", { file: rel, line: lineOf(raw, m[0]) });
    }
    markTunnelToken(raw, rel);
  }

  // 7. 배포 플랫폼 · CI
  const platforms = [
    [/^railway\.(json|toml)$/, "Railway"],
    [/^vercel\.json$/, "Vercel"],
    [/^fly\.toml$/, "Fly.io"],
    [/^render\.ya?ml$/, "Render"],
    [/^netlify\.toml$/, "Netlify"],
    [/^Procfile$/, "Heroku"],
    [/^app\.ya?ml$/, "App Engine"],
  ];
  for (const rel of files) {
    const hit = platforms.find(([re]) => re.test(rel));
    if (!hit) continue;
    node(`platform:${hit[1]}`, { kind: "platform", label: hit[1], detail: "배포 플랫폼", sources: [{ file: rel, line: 1 }] });
    link(`platform:${hit[1]}`, mainApp.id, "호스팅", "file", { file: rel, line: 1 });
  }
  const workflows = files.filter((f) => /^\.github\/workflows\/[^/]+\.ya?ml$/.test(f));
  if (workflows.length > 0) {
    node("ci:github-actions", { kind: "ci", label: "GitHub Actions", detail: workflows.map((w) => basename(w)).join(", "), sources: workflows.map((w) => ({ file: w, line: 1 })) });
    link("ci:github-actions", mainApp.id, "빌드 · 배포", "inferred", { file: workflows[0], line: 1 });
    for (const w of workflows) markTunnelToken(read(w), w);
  }

  // 8. 레포에 없는 Cloudflare 고리: 토큰 변수만 보이면 Tunnel 이 대시보드에 설정된 것으로 본다
  if (tunnelTokenSeen && ![...nodes.values()].some((n) => n.kind === "tunnel" && n.confidence !== "missing")) {
    node("tunnel:dashboard", { kind: "tunnel", label: "Cloudflare Tunnel", detail: "토큰만 있음 · 설정은 Cloudflare 대시보드", confidence: "missing", sources: [tunnelTokenSeen] });
    node("dns:dashboard", { kind: "dns", label: "Cloudflare DNS", detail: "레포에 없음", confidence: "missing", sources: [] });
    link("dns:dashboard", "tunnel:dashboard", null, "missing", null);
    const target = [...nodes.values()].find((n) => n.kind === "proxy") ?? mainApp;
    link("tunnel:dashboard", target.id, null, "missing", tunnelTokenSeen);
  }

  // 9. 바깥에서 들어오는 첫 고리에 client 를 붙인다
  const incoming = new Set(links.map((l) => l.to));
  const edgeNodes = [...nodes.values()].filter((n) => ["dns", "tunnel", "proxy", "app"].includes(n.kind) && !incoming.has(n.id));
  const entries = edgeNodes.filter((n) => n.kind === "dns").length
    ? edgeNodes.filter((n) => n.kind === "dns")
    : edgeNodes.filter((n) => n.kind === "tunnel" || n.kind === "proxy").length
      ? edgeNodes.filter((n) => n.kind === "tunnel" || n.kind === "proxy")
      : apps.filter((a) => a.published).length
        ? apps.filter((a) => a.published)
        : [mainApp];
  node("client", { kind: "client", label: "사용자", detail: "브라우저 · API 클라이언트", sources: [] });
  for (const e of entries) link("client", e.id, null, e.kind === "app" && !e.published ? "inferred" : "file", null);

  // 10. 코드와 잇기
  const code = codeFiles.filter((f) => SOURCE_FILE.test(f) && !/(^|\/)(test|tests|__tests__)\//.test(f));
  const routesByFile = new Map();
  for (const r of routes) routesByFile.set(r.file, [...(routesByFile.get(r.file) ?? []), `${r.method ?? ""} ${r.path ?? ""}`.trim()]);
  const dbFiles = code.filter((f) => DB_CODE.test(read(f)));
  const dataNodes = [...nodes.values()].filter((n) => ["database", "cache"].includes(n.kind));
  const externals = [...nodes.values()].filter((n) => n.kind === "external");
  const codeLinks = [];
  for (const [file, list] of routesByFile) codeLinks.push({ node: mainApp.id, file, kind: "entry", detail: list.slice(0, 5).join(", ") });
  // 앱이 실제로 연결된 DB 에 DB 코드를 붙인다
  const appData = links.filter((l) => apps.some((a) => a.id === l.from) && dataNodes.some((d) => d.id === l.to)).map((l) => l.to);
  for (const target of new Set(appData.length ? appData : dataNodes.filter((d) => d.kind === "database").map((d) => d.id))) {
    for (const file of dbFiles) codeLinks.push({ node: target, file, kind: "data", detail: null });
  }
  for (const ext of externals) {
    const brand = BRANDS.find((b) => ext.label.includes(b));
    for (const file of code) {
      const body = read(file);
      const byHost = body.includes(ext.label);
      const byName = brand && file.toLowerCase().includes(brand) && HTTP_CODE.test(body);
      if (byHost || byName) codeLinks.push({ node: ext.id, file, kind: "external", detail: byHost ? null : "이름 · HTTP 클라이언트로 추정" });
    }
  }

  const uniqueCode = [...new Map(codeLinks.map((c) => [`${c.node}|${c.file}`, c])).values()];
  return {
    nodes: [...nodes.values()].map(({ dir: _dir, published: _p, volumes: _v, ...n }) => n),
    links,
    codeLinks: uniqueCode,
    files: [...new Set([...nodes.values()].flatMap((n) => n.sources.map((s) => s.file)))].sort(),
  };

  function markTunnelToken(value, rel) {
    if (!tunnelTokenSeen && /TUNNEL_TOKEN|CLOUDFLARE_TUNNEL|cloudflared/i.test(value)) tunnelTokenSeen = { file: rel, line: lineOf(read(rel), "TUNNEL") || lineOf(read(rel), "cloudflare") };
  }
}

let tunnelTokenSeen = null;

function walk(root) {
  const out = [];
  const visit = (dir, depth) => {
    if (depth > MAX_DEPTH) return;
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      if (e.isDirectory()) {
        if (!SKIP_DIRS.has(e.name)) visit(join(dir, e.name), depth + 1);
      } else if (e.isFile()) out.push(relative(root, join(dir, e.name)).replaceAll("\\", "/"));
    }
  };
  visit(root, 0);
  return out;
}

let yamlLib = null;
export async function loadYamlLib() {
  yamlLib ??= await import("js-yaml");
}

function loadYaml(raw) {
  try {
    return yamlLib.loadAll(raw).filter(Boolean);
  } catch {
    return [];
  }
}

function kindOfImage(image) {
  const lower = image.toLowerCase();
  return KIND_BY_IMAGE.find(([, re]) => re.test(lower))?.[0] ?? null;
}

function nameMatchesKind(node, kind) {
  const text = `${node.label} ${node.detail ?? ""}`.toLowerCase();
  if (kind === "postgres") return text.includes("postgres");
  if (kind === "mysql") return text.includes("mysql") || text.includes("mariadb");
  if (kind === "mongo") return text.includes("mongo");
  if (kind === "redis") return text.includes("redis") || text.includes("valkey");
  return false;
}

function envValues(env) {
  if (!env) return [];
  if (Array.isArray(env)) return env.map(String);
  return Object.entries(env).map(([k, v]) => `${k}=${v ?? ""}`);
}

function envKeys(env) {
  if (!env) return [];
  return Array.isArray(env) ? env.map((e) => String(e).split("=")[0]) : Object.keys(env);
}

/** "KEY=value" 가 주소 성격인지: 키 이름이 HOST · URL · ADDR … 이거나 값에 scheme:// 가 있다 */
function isAddressEntry(entry) {
  const [key, ...rest] = String(entry).split("=");
  const value = rest.join("=");
  return /host|url|uri|addr|endpoint|server|dsn|broker|upstream/i.test(key) || /:\/\//.test(value);
}

/** 값이 그 호스트를 가리키는지: 값 전체가 호스트이거나, //호스트 · @호스트 · 호스트:포트 */
function pointsAt(entry, host) {
  if (!host || host.length < 2) return false;
  const text = String(entry);
  const value = text.includes("=") ? text.split("=").slice(1).join("=") : text;
  const escaped = host.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return value.trim() === host || new RegExp(`(//|@)${escaped}([:/?]|$)|(^|[\\s,])${escaped}:\\d`).test(value);
}

function buildDir(composeFile, build) {
  const ctx = typeof build === "string" ? build : build?.context ?? ".";
  const base = composeFile.includes("/") ? composeFile.slice(0, composeFile.lastIndexOf("/")) : "";
  return join(base, ctx).replaceAll("\\", "/").replace(/^\.\/?/, "");
}

function lineOf(raw, needle) {
  if (!needle) return 1;
  const i = raw.indexOf(needle);
  return i < 0 ? 1 : raw.slice(0, i).split("\n").length;
}

function rank(confidence) {
  return { missing: 0, inferred: 1, file: 2 }[confidence] ?? 0;
}
