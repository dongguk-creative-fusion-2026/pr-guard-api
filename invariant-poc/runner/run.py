"""Isolated sandbox adapter. stdout contains one final result JSON; build logs go to stderr."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

TOOLS = Path(__file__).resolve().parent
ROOT = Path(os.environ.get('INVARIANT_WORKDIR', str(Path(tempfile.gettempdir()) / 'prguard-invariant-work')))


def command(args, cwd=None, env=None, timeout=180):
    subprocess.run(args, cwd=cwd, env=env, check=True, timeout=timeout,
                   stdout=sys.stderr, stderr=sys.stderr)


def side(name, sha):
    repo = ROOT / name
    command(['git', 'clone', '--no-hardlinks', str(ROOT / 'repo'), str(repo)])
    command(['git', 'checkout', '--detach', sha], cwd=repo)
    target = repo / 'src/test/java/com/example/board/PrGuardInvariantBridgeTest.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(TOOLS / 'PrGuardInvariantBridgeTest.java', target)
    result = ROOT / f'{name}.json'
    env = {**os.environ, 'SIDE': name, 'CASE_PATH': str(ROOT / 'case.json'), 'RESULT_PATH': str(result),
           'PRGUARD_PYTHON': sys.executable, 'PRGUARD_SIDE_DRIVER': str(TOOLS / 'side_driver.py')}
    wrapper = ['cmd', '/d', '/c', 'gradlew.bat'] if os.name == 'nt' else ['bash', './gradlew']
    command([*wrapper, 'test', '--tests', 'com.example.board.PrGuardInvariantBridgeTest',
             '--no-daemon', '-I', str(TOOLS / 'invariant.gradle')], cwd=repo, env=env, timeout=300)
    return json.loads(result.read_text())


def compare(base, head):
    b, h = base['observations'], head['observations']
    def broken(obs):
        if obs['status'] != 200:
            raise ValueError('adapter did not complete update')
        return obs['author_before'] != obs['author_after']
    # Generated database IDs may differ between reset transactions: compare the property outcomes.
    bo, ho = [broken(x) for x in b], [broken(x) for x in h]
    if len(set(bo)) > 1 or len(set(ho)) > 1 or not all(ho):
        state = 'flaky'
    elif all(bo):
        state = 'existing_violation'
    else:
        state = 'new_violation'
    return {'status': state, 'input': head['input'], 'base': b, 'head': h}


def main(plan):
    if plan.get('ruleId') != 'post-author-preserved-on-update' or plan.get('version') != 1:
        raise ValueError('unsupported rule')
    for name in ('headSha', 'baseSha'):
        if not re.fullmatch('[0-9a-f]{40}', plan[name]):
            raise ValueError('invalid SHA')
    ROOT.mkdir(parents=True, exist_ok=True)
    # Fixture override is only used by the optional direct integration test.
    source = os.environ.get('INVARIANT_FIXTURE_REPO', 'https://github.com/dongguk-creative-fusion-2026/pr-guard-sandbox.git')
    command(['git', 'clone', '--no-hardlinks', source, str(ROOT / 'repo')])
    for sha in (plan['headSha'], plan['baseSha']):
        command(['git', 'fetch', 'origin', sha], cwd=ROOT / 'repo')
    merge_base = subprocess.check_output(['git', 'merge-base', plan['baseSha'], plan['headSha']], cwd=ROOT / 'repo', text=True).strip()
    head = side('HEAD', plan['headSha'])
    result = head if head['status'] == 'no_counterexample' else compare(side('BASE', merge_base), head)
    return {**result, 'baseSha': merge_base, 'headSha': plan['headSha'],
            'ruleId': plan['ruleId'], 'baseExecuted': head['status'] != 'no_counterexample',
            'database': 'H2 PostgreSQL mode', 'target': 'Spring PostService.update'}


if __name__ == '__main__':
    try:
        print(json.dumps(main(json.load(sys.stdin))))
    except Exception as error:
        print(json.dumps({'status': 'execution_error', 'error': str(error)[:1000]}))
        sys.exit(1)
