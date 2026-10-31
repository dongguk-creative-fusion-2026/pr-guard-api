"""PR Guard adapter. Upstream detector is unmodified; coordinates are ancestor-side edits."""
import dataclasses
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from conflict_detector import detect_conflicts, cluster_conflicts
from pr_data import ChangedFile, PullRequestData


def old_ranges(patch):
    """Exclude context; use old coordinates so insertions cannot shift another PR's lines."""
    ranges = []
    old = None
    start = None
    removed = 0

    def flush():
        nonlocal start, removed
        if start is not None:
            ranges.append((max(1, start), max(1, start + max(removed, 1) - 1)))
        start, removed = None, 0

    for line in patch.splitlines():
        header = re.match(r"@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@", line)
        if header:
            flush()
            old = int(header.group(1))
        elif old is not None:
            if line.startswith('-'):
                if start is None:
                    start = old
                removed += 1
                old += 1
            elif line.startswith('+'):
                if start is None:
                    start = old
            elif line.startswith(' '):
                flush()
                old += 1
    flush()
    return sorted(set(ranges))


def analyze(data):
    prs = []
    for p in data['pulls']:
        files = [ChangedFile(f.get('previous_filename') or f.get('previousFilename') or f['filename'], 0, 0, 0,
                             old_ranges(f['patch'])) for f in p['files'] if f.get('patch')]
        prs.append(PullRequestData(p['number'], p['title'] or '', p['author'], p['url'], p['draft'],
                                  p['baseRef'] + '@' + p['mergeBase'], p['headSha'], files))
    conflicts = detect_conflicts(prs, verify=False)
    return {'version': 1, 'conflicts': [dataclasses.asdict(c) for c in conflicts],
            'clusters': [dataclasses.asdict(c) for c in cluster_conflicts(conflicts)]}


if __name__ == '__main__':
    data = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    Path(sys.argv[2]).write_text(json.dumps(analyze(data), ensure_ascii=False), encoding='utf-8')
