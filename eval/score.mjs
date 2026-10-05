#!/usr/bin/env node
// 평가용 PR 세트(cases.json)에 대한 리뷰 결과를 채점한다.
//
//   기준선(diff 만 보는 LLM 리뷰):  node eval/score.mjs --baseline eval/baseline.json
//   PR Guard (배포된 API):          node eval/score.mjs --api https://... --project 1 [--run 2]
//
// 1차 자동 채점이다. 지적 하나(표의 한 행)가 정답의 파일 이름과 키워드를 함께 언급하면 "탐지"로 본다.
// 최종 결과는 사람이 지적 내용을 읽고 확정한다.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []),
);
const here = dirname(fileURLToPath(import.meta.url));
const cases = JSON.parse(readFileSync(args.cases ?? join(here, 'cases.json'), 'utf8')).cases;
const SEVERITIES = ['BLOCKER', 'MAJOR', 'MINOR'];

/** 리뷰 하나 → { verdict, units: [{ text, severity }] } */
async function loadReviews() {
  if (args.baseline) {
    const baseline = JSON.parse(readFileSync(args.baseline, 'utf8'));
    return new Map(baseline.reviews.map((r) => [r.pr, fromMarkdown(r.result)]));
  }
  if (!args.api || !args.project) {
    console.error('사용법: --baseline <file> 또는 --api <url> --project <id> [--run <n>]');
    process.exit(1);
  }
  const list = await (await fetch(`${args.api}/api/projects/${args.project}/reviews?limit=100`)).json();
  const result = new Map();
  for (const c of cases) {
    const candidates = list
      .filter((r) => r.prNumber === c.pr && r.status === 'DONE')
      .filter((r) => !args.run || r.run === Number(args.run))
      .sort((a, b) => b.id - a.id);
    if (candidates.length === 0) continue;
    const detail = await (await fetch(`${args.api}/api/reviews/${candidates[0].id}`)).json();
    result.set(c.pr, {
      verdict: verdictLabel(detail.review.verdict),
      units: detail.findings.map((f) => ({
        text: [f.file, f.title, f.message, f.evidence].filter(Boolean).join(' '),
        severity: f.severity,
        source: f.source,
      })),
    });
  }
  return result;
}

function verdictLabel(v) {
  return { MERGEABLE: '머지 가능', NEEDS_CHANGES: '수정 후 머지', NOT_RECOMMENDED: '머지 비권장' }[v] ?? v ?? '-';
}

/** 기준선 리뷰는 마크다운이다: 지적 사항 표의 행과 "### 판정" 다음 줄을 읽는다. */
function fromMarkdown(md) {
  const lines = md.split('\n');
  const units = lines
    .filter((l) => l.startsWith('|') && SEVERITIES.some((s) => l.includes(s)))
    .map((l) => ({ text: l, severity: SEVERITIES.find((s) => l.includes(s)) }));
  const at = lines.findIndex((l) => l.trim().startsWith('### 판정'));
  const verdictLine = at >= 0 ? lines.slice(at + 1).find((l) => l.trim()) ?? '' : '';
  const verdict = ['머지 비권장', '수정 후 머지', '머지 가능'].find((v) => verdictLine.includes(v)) ?? '-';
  return { verdict, units };
}

function matches(unit, expected) {
  const text = unit.text.toLowerCase();
  return (
    expected.files.some((f) => text.includes(f.toLowerCase())) &&
    expected.keywords.some((k) => text.includes(k.toLowerCase()))
  );
}

const reviews = await loadReviews();
let detected = 0;
let total = 0;
let correctVerdicts = 0;
const rows = [];
for (const c of cases) {
  const review = reviews.get(c.pr);
  if (!review) {
    rows.push(`| #${c.pr} | ${c.title} | (리뷰 없음) | | |`);
    continue;
  }
  const verdictOk = c.clean ? review.verdict === '머지 가능' : review.verdict !== '머지 가능';
  if (verdictOk) correctVerdicts++;
  if (c.clean) {
    const fp = review.units.filter((u) => u.severity === 'BLOCKER' || u.severity === 'MAJOR').length;
    rows.push(`| #${c.pr} | ${c.title} | 정상 PR · BLOCKER/MAJOR 오탐 ${fp}건 | ${review.verdict} | ${verdictOk ? '✅' : '❌'} |`);
    continue;
  }
  const marks = c.expected.map((e) => {
    total++;
    const hit = review.units.filter((u) => matches(u, e));
    if (hit.length > 0) detected++;
    const best = SEVERITIES.find((s) => hit.some((u) => u.severity === s));
    return `${hit.length > 0 ? '✅' : '❌'} ${e.category}:${e.id}${best ? ` (${best})` : ''}`;
  });
  rows.push(`| #${c.pr} | ${c.title} | ${marks.join('<br>')} | ${review.verdict} | ${verdictOk ? '✅' : '❌'} |`);
}

console.log(`# 평가 결과 (${args.baseline ? `기준선 ${args.baseline}` : `${args.api} project=${args.project}${args.run ? ` run=${args.run}` : ''}`})\n`);
console.log('| PR | 제목 | 탐지 | 판정 | 판정 적절 |');
console.log('|---|---|---|---|---|');
rows.forEach((r) => console.log(r));
console.log(`\n- 탐지: ${detected} / ${total}`);
console.log(`- 판정 적절: ${correctVerdicts} / ${cases.length} (문제 PR 은 "머지 가능"이 아니어야, 정상 PR 은 "머지 가능"이어야 적절)`);
