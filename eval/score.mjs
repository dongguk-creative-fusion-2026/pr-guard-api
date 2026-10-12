#!/usr/bin/env node
// 평가용 PR 세트(cases.json)에 대한 리뷰 결과를 채점한다.
//
//   기준선(diff 만 보는 LLM 리뷰):  node eval/score.mjs --baseline eval/baseline.json
//   PR Guard (배포된 API):          node eval/score.mjs --api https://... --project 1 [--run 2]
//
// 1차 자동 채점이다. 지적 하나(표의 한 행)가 정답의 파일 이름과 키워드를 함께 언급하면 "탐지"로 본다.
// 최종 결과는 사람이 지적 내용을 읽고 확정한다.
//
// 보는 것
// - 탐지 · 판정: 문제를 찾았나, 머지 여부를 맞게 판단했나
// - 병목: 정상 PR 을 "머지 가능"으로 통과시켜 사람이 깊게 볼 PR 을 줄였나, 그러면서 문제 PR 을 흘리지 않았나
// - 계층별 기여: 정적 분석 · 실행 검증 · AI 리뷰 중 어디서 잡았나 (API 결과만)
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []),
);
const here = dirname(fileURLToPath(import.meta.url));
const cases = JSON.parse(readFileSync(args.cases ?? join(here, 'cases.json'), 'utf8')).cases;
const SEVERITIES = ['BLOCKER', 'MAJOR', 'MINOR'];
const LAYERS = ['정적', '실행', 'AI'];

/** 리뷰 하나 → { verdict, seconds, units: [{ text, severity, layer }] } */
async function loadReviews() {
  if (args.baseline) {
    const baseline = JSON.parse(readFileSync(args.baseline, 'utf8'));
    return new Map(baseline.reviews.map((r) => [r.pr, fromMarkdown(r.result)]));
  }
  if (!args.api || !args.project) {
    console.error('사용법: --baseline <file> 또는 --api <url> --project <id> [--run <n>]');
    process.exit(1);
  }
  const list = await (await fetch(`${args.api}/api/projects/${args.project}/reviews?limit=200`)).json();
  const result = new Map();
  for (const c of cases) {
    const candidates = list
      .filter((r) => r.prNumber === c.pr && r.status === 'DONE')
      .filter((r) => !args.run || r.run === Number(args.run))
      .sort((a, b) => b.id - a.id);
    if (candidates.length === 0) continue;
    const detail = await (await fetch(`${args.api}/api/reviews/${candidates[0].id}`)).json();
    const { startedAt, finishedAt } = detail.review;
    result.set(c.pr, {
      verdict: verdictLabel(detail.review.verdict),
      seconds: startedAt && finishedAt ? (Date.parse(finishedAt) - Date.parse(startedAt)) / 1000 : null,
      units: detail.findings.map((f) => ({
        text: [f.file, f.title, f.message, f.evidence].filter(Boolean).join(' '),
        severity: f.severity,
        layer: layerOf(f.ruleId),
      })),
    });
  }
  return result;
}

/** 지적을 만든 계층. LLM_REVIEW 는 AI, EXEC_* 는 실행 검증, 나머지(AST_* · 참조 검사)는 정적 분석. */
function layerOf(ruleId) {
  if (ruleId === 'LLM_REVIEW') return 'AI';
  if (ruleId?.startsWith('EXEC_')) return '실행';
  return '정적';
}

function verdictLabel(v) {
  return { MERGEABLE: '머지 가능', NEEDS_CHANGES: '수정 후 머지', NOT_RECOMMENDED: '머지 비권장' }[v] ?? v ?? '-';
}

/** 기준선 리뷰는 마크다운이다: 지적 사항 표의 행과 "### 판정" 다음 줄을 읽는다. */
function fromMarkdown(md) {
  const lines = md.split('\n');
  const units = lines
    .filter((l) => l.startsWith('|') && SEVERITIES.some((s) => l.includes(s)))
    .map((l) => ({ text: l, severity: SEVERITIES.find((s) => l.includes(s)), layer: 'AI' }));
  const at = lines.findIndex((l) => l.trim().startsWith('### 판정'));
  const verdictLine = at >= 0 ? lines.slice(at + 1).find((l) => l.trim()) ?? '' : '';
  const verdict = ['머지 비권장', '수정 후 머지', '머지 가능'].find((v) => verdictLine.includes(v)) ?? '-';
  return { verdict, seconds: null, units };
}

function matches(unit, expected) {
  const text = unit.text.toLowerCase();
  return (
    expected.files.some((f) => text.includes(f.toLowerCase())) &&
    expected.keywords.some((k) => text.includes(k.toLowerCase()))
  );
}

const serious = (u) => u.severity === 'BLOCKER' || u.severity === 'MAJOR';
const ratio = (n, d) => `${n} / ${d}${d ? ` (${Math.round((n / d) * 100)}%)` : ''}`;

const reviews = await loadReviews();
let detected = 0;
let total = 0;
let correctVerdicts = 0;
let reviewed = 0;
let cleanPassed = 0;
let cleanReviewed = 0;
let riskyLeaked = 0;
let riskyReviewed = 0;
let unmatchedSerious = 0;
const seconds = [];
const byPattern = new Map(); // 유형 → { hit, total }
const byLayer = new Map(LAYERS.map((l) => [l, { hit: 0, only: 0 }]));
const rows = [];
for (const c of cases) {
  const review = reviews.get(c.pr);
  if (!review) {
    rows.push(`| #${c.pr} | ${c.title} | (리뷰 없음) | | |`);
    continue;
  }
  reviewed++;
  if (review.seconds != null) seconds.push(review.seconds);
  const verdictOk = c.clean ? review.verdict === '머지 가능' : review.verdict !== '머지 가능';
  if (verdictOk) correctVerdicts++;
  if (c.clean) {
    cleanReviewed++;
    if (verdictOk) cleanPassed++;
    const fp = review.units.filter(serious).length;
    unmatchedSerious += fp;
    rows.push(`| #${c.pr} | ${c.title} | 정상 PR · BLOCKER/MAJOR 오탐 ${fp}건 | ${review.verdict} | ${verdictOk ? '✅' : '❌'} |`);
    continue;
  }
  riskyReviewed++;
  if (!verdictOk) riskyLeaked++;
  unmatchedSerious += review.units.filter((u) => serious(u) && !c.expected.some((e) => matches(u, e))).length;
  const marks = c.expected.map((e) => {
    total++;
    const hit = review.units.filter((u) => matches(u, e));
    const stat = byPattern.get(e.pattern) ?? { hit: 0, total: 0 };
    stat.total++;
    byPattern.set(e.pattern, stat);
    if (hit.length === 0) return `❌ ${e.category}:${e.id}`;
    detected++;
    stat.hit++;
    const layers = LAYERS.filter((l) => hit.some((u) => u.layer === l));
    layers.forEach((l) => byLayer.get(l).hit++);
    if (layers.length === 1) byLayer.get(layers[0]).only++;
    const best = SEVERITIES.find((s) => hit.some((u) => u.severity === s));
    return `✅ ${e.category}:${e.id} (${best} · ${layers.join('+')})`;
  });
  rows.push(`| #${c.pr} | ${c.title} | ${marks.join('<br>')} | ${review.verdict} | ${verdictOk ? '✅' : '❌'} |`);
}

console.log(`# 평가 결과 (${args.baseline ? `기준선 ${args.baseline}` : `${args.api} project=${args.project}${args.run ? ` run=${args.run}` : ''}`})\n`);
console.log('| PR | 제목 | 탐지 | 판정 | 판정 적절 |');
console.log('|---|---|---|---|---|');
rows.forEach((r) => console.log(r));

console.log('\n## 요약\n');
console.log(`- 리뷰된 PR: ${reviewed} / ${cases.length}`);
console.log(`- 탐지: ${ratio(detected, total)}`);
console.log(`- 판정 적절: ${ratio(correctVerdicts, reviewed)} (문제 PR 은 "머지 가능"이 아니어야, 정상 PR 은 "머지 가능"이어야 적절)`);
console.log(`- 정답표 밖 BLOCKER/MAJOR: ${unmatchedSerious}건 (오탐 후보, 사람이 확인)`);

console.log('\n## 병목\n');
console.log(`- 정상 PR 자동 통과: ${ratio(cleanPassed, cleanReviewed)} — 사람이 깊게 보지 않아도 되는 PR`);
console.log(`- 문제 PR 누락: ${ratio(riskyLeaked, riskyReviewed)} — "머지 가능"으로 흘려보낸 문제 PR (0 이어야 한다)`);
if (seconds.length > 0) {
  const sorted = [...seconds].sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)];
  console.log(`- 리뷰 소요 시간: 중앙값 ${Math.round(median)}초 · 최대 ${Math.round(sorted.at(-1))}초`);
}

console.log('\n## AI 실수 유형별 탐지\n');
console.log('| 유형 | 탐지 |');
console.log('|---|---|');
for (const [pattern, s] of byPattern) console.log(`| ${pattern} | ${s.hit} / ${s.total} |`);

if (!args.baseline) {
  console.log('\n## 계층별 기여\n');
  console.log('| 계층 | 잡은 정답 | 그 계층만 잡은 정답 |');
  console.log('|---|---|---|');
  for (const [layer, s] of byLayer) console.log(`| ${layer} | ${s.hit} | ${s.only} |`);
}
