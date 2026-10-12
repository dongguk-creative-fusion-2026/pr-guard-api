#!/usr/bin/env node
// 기준선 리뷰를 만든다: PR 정보와 diff 만 LLM 에 넣는다 (분석 파이프라인 도입 전 e8cdf17 의 OpenAiReviewer 와 같은 입력).
// baseline.json 에 없는 PR 만 리뷰해서 덧붙인다. 이미 있는 PR 은 --force 로 다시 만든다.
//
//   OPENAI_API_KEY=... [GITHUB_TOKEN=...] node eval/baseline.mjs [--model gpt-5-mini] [--force]
//
// 시스템 프롬프트는 그때 것을 baseline-system.md 로 고정해 두었다.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(`--${name}`);
const option = (name, fallback) => (argv.includes(`--${name}`) ? argv[argv.indexOf(`--${name}`) + 1] : fallback);

const here = dirname(fileURLToPath(import.meta.url));
const { repo, cases } = JSON.parse(readFileSync(join(here, 'cases.json'), 'utf8'));
const baselinePath = join(here, 'baseline.json');
const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));
const system = readFileSync(join(here, 'baseline-system.md'), 'utf8');
const model = option('model', 'gpt-5-mini');
const MAX_PATCH_CHARS = 60000;

if (!process.env.OPENAI_API_KEY) {
  console.error('OPENAI_API_KEY 가 필요하다');
  process.exit(1);
}

async function github(path) {
  const headers = { Accept: 'application/vnd.github+json' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const res = await fetch(`https://api.github.com/repos/${repo}${path}`, { headers });
  if (!res.ok) throw new Error(`GitHub ${path}: ${res.status}`);
  return res.json();
}

/** ReviewPrompt.user() 와 같은 형식 */
function userPrompt(pr, files) {
  let s = `레포: ${repo}\nPR #${pr.number}: ${pr.title}\n작성자: ${pr.user.login}\n브랜치: ${pr.head.ref} -> ${pr.base.ref}\n`;
  if (pr.body?.trim()) s += `\nPR 본문:\n${pr.body.trim()}\n`;
  s += `\n변경 파일 ${files.length}개\n`;
  let budget = MAX_PATCH_CHARS;
  for (const f of files) {
    s += `\n--- ${f.filename} (${f.status}, +${f.additions} -${f.deletions})\n`;
    if (f.patch == null) {
      s += '(patch 없음: 바이너리 또는 너무 큰 파일)\n';
    } else if (f.patch.length > budget) {
      s += '(patch 생략: 입력 길이 제한)\n';
    } else {
      s += `${f.patch}\n`;
      budget -= f.patch.length;
    }
  }
  return s;
}

async function review(input) {
  const res = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.OPENAI_API_KEY}` },
    body: JSON.stringify({ model, instructions: system, input }),
  });
  if (!res.ok) throw new Error(`OpenAI: ${res.status} ${await res.text()}`);
  const body = await res.json();
  const text = body.output
    .filter((o) => o.type === 'message')
    .flatMap((o) => o.content)
    .filter((c) => c.type === 'output_text')
    .map((c) => c.text)
    .join('')
    .trim();
  if (!text) throw new Error('OpenAI 응답에 텍스트가 없다');
  return text;
}

for (const c of cases) {
  const existing = baseline.reviews.findIndex((r) => r.pr === c.pr);
  if (existing >= 0 && !flag('force')) continue;
  const pr = await github(`/pulls/${c.pr}`);
  const files = await github(`/pulls/${c.pr}/files?per_page=100`);
  const entry = {
    pr: c.pr,
    headSha: pr.head.sha,
    reviewer: `openai:${model}`,
    finishedAt: null,
    result: await review(userPrompt(pr, files)),
  };
  entry.finishedAt = new Date().toISOString();
  if (existing >= 0) baseline.reviews[existing] = entry;
  else baseline.reviews.push(entry);
  baseline.reviews.sort((a, b) => a.pr - b.pr);
  writeFileSync(baselinePath, `${JSON.stringify(baseline, null, 2)}\n`);
  console.log(`#${c.pr} ${c.title} 완료`);
}
