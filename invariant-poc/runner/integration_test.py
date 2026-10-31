"""Optional direct Spring/JPA regression test. Requires Python, Hypothesis and JDK 21; no container engine."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import sys
import os

sys.stdout.reconfigure(encoding='utf-8', errors='replace')


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sandbox', required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='prguard-real-') as directory:
        repo = Path(directory) / 'fixture'
        subprocess.run(['git', 'clone', '--no-hardlinks', str(Path(args.sandbox).resolve()), str(repo)], check=True)
        git(repo, 'config', 'user.name', 'Invariant integration fixture')
        git(repo, 'config', 'user.email', 'fixture@example.test')
        base = git(repo, 'rev-parse', 'HEAD')
        domain = repo / 'src/main/java/com/example/board/domain/Post.java'
        domain.write_text(domain.read_text(encoding='utf-8').replace('public void update(',
            'public void setAuthorForRegression(User user) { this.author = user; }\n\n    public void update('), encoding='utf-8')
        service = repo / 'src/main/java/com/example/board/service/PostService.java'
        source = service.read_text(encoding='utf-8').replace('public class PostService {',
            'public class PostService {\n    @org.springframework.beans.factory.annotation.Autowired\n'
            '    private com.example.board.repository.UserRepository regressionUsers;')
        source = source.replace('post.update(title, content);',
            'post.update(title, content);\n'
            '        if (title.contains("0")) post.setAuthorForRegression(regressionUsers.findAll().stream()\n'
            '            .filter(other -> !other.getId().equals(user.getId())).findFirst().orElseThrow());')
        service.write_text(source, encoding='utf-8')
        git(repo, 'add', '.')
        git(repo, 'commit', '-m', 'test fixture: author changes only for title containing zero')
        head = git(repo, 'rev-parse', 'HEAD')
        plan = {'ruleId': 'post-author-preserved-on-update', 'version': 1, 'headSha': head, 'baseSha': base}
        env = {**os.environ, 'INVARIANT_FIXTURE_REPO': str(repo), 'INVARIANT_WORKDIR': str(Path(directory) / 'work')}
        run = subprocess.run([sys.executable, str(Path(__file__).with_name('run.py'))],
                             env=env, input=json.dumps(plan), text=True,
                             capture_output=True, encoding='utf-8', errors='replace', timeout=720)
        print(run.stderr[-12000:])
        print(run.stdout)
        result = json.loads(run.stdout)
        assert run.returncode == 0, result
        assert result['status'] == 'new_violation', result
        assert '0' in result['input']['title'], result
        assert all(x['author_before'] == x['author_after'] for x in result['base']), result
        assert all(x['author_before'] != x['author_after'] for x in result['head']), result
        print('Real Spring + JPA + Hypothesis regression test passed without Docker')


if __name__ == '__main__':
    main()
