"""API integration test with mocked LLM/Actions HTTP and real API/PostgreSQL.

Start a dedicated backend on :18080 with GitHub API URL http://localhost:18181/github,
OpenAI API URL http://localhost:18181/v1, model integration-model, any test key,
INVARIANT_EXEC_ENABLED=true and INVARIANT_POLL_INTERVAL=PT2S. No container CLI needed.
"""
import argparse
import io
import json
import sys
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
API = 'http://127.0.0.1:18080'
HEAD = '985c949093c6a51065a2cb98f922ce20464600dd'
BASE = 'f95b303b52c0e51cd3a63e8ef83e36f75ae29cea'
state = {'inputs': None, 'scenario': 'success', 'polls': 0}
pull = {'number': 24, 'title': 'Actions integration fixture', 'state': 'open', 'draft': False,
        'html_url': 'https://github.com/dongguk-creative-fusion-2026/pr-guard-sandbox/pull/24',
        'user': {'login': 'fixture'}, 'head': {'sha': HEAD, 'ref': 'fixture'}, 'base': {'sha': BASE, 'ref': 'main'}}


class Upstream(BaseHTTPRequestHandler):
    def reply(self, value=None, status=200):
        body = b'' if value is None else json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        if self.headers.get('Transfer-Encoding', '').lower() != 'chunked':
            return json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        chunks = []
        while True:
            size = int(self.rfile.readline().split(b';', 1)[0].strip(), 16)
            if size == 0:
                self.rfile.readline()
                break
            chunks.append(self.rfile.read(size))
            self.rfile.read(2)
        return json.loads(b''.join(chunks))

    def do_POST(self):
        request = self.body()
        if self.path == '/v1/responses':
            rules = {'rules': [{'property': '작성자 ID 보존', 'precondition': '작성자 수정 성공',
                                'clarification': '', 'template': 'POST_AUTHOR_PRESERVED'}]}
            self.reply({'status': 'completed', 'output': [{'type': 'message', 'content': [
                {'type': 'output_text', 'text': json.dumps(rules)}]}]})
        elif self.path.endswith('/invariant-check.yml/dispatches'):
            state['inputs'] = request['inputs']
            self.reply(None, 403 if state['scenario'] == 'dispatch-error' else 204)
        else:
            self.reply(None, 204)

    def do_GET(self):
        path = self.path.split('?')[0]
        if path.endswith('/actions/artifacts/99/zip'):
            result = {'status': 'no_counterexample', 'headSha': HEAD, 'baseSha': BASE,
                      'ruleId': 'post-author-preserved-on-update', 'baseExecuted': False, 'max_examples': 100}
            if state['scenario'] == 'wrong-commit':
                result['headSha'] = '0' * 40
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, 'w') as file:
                file.writestr('invariant-result.json', json.dumps(result))
            self.send_response(200)
            self.send_header('Content-Type', 'application/zip')
            self.end_headers()
            self.wfile.write(archive.getvalue())
        elif path.endswith('/actions/runs/42/artifacts'):
            self.reply({'artifacts': [{'id': 99, 'name': 'invariant-' + state['inputs']['run_id'] + '-1',
                                       'expired': False, 'size_in_bytes': 500}]})
        elif '/actions/workflows/invariant-check.yml/runs' in path or path.endswith('/actions/runs/42'):
            state['polls'] += 1
            run = {'id': 42, 'display_title': 'invariant ' + state['inputs']['run_id'],
                   'path': '.github/workflows/invariant-check.yml', 'event': 'workflow_dispatch',
                   'head_branch': 'main', 'run_attempt': 1, 'status': 'completed' if state['polls'] > 1 else 'in_progress',
                   'conclusion': 'success', 'html_url': 'https://github.com/example/actions/runs/42'}
            self.reply(run if path.endswith('/42') else {'workflow_runs': [run]})
        elif '/compare/' in path:
            self.reply({'merge_base_commit': {'sha': BASE}})
        elif path.endswith('/pulls/24'):
            self.reply(pull)
        elif path.endswith('/pulls'):
            self.reply([pull])
        elif path.endswith('/files') or path.endswith('/commits'):
            self.reply([])
        elif path.endswith('/pr-guard-sandbox'):
            self.reply({'name': 'pr-guard-sandbox', 'owner': {'login': 'dongguk-creative-fusion-2026'},
                        'private': False, 'default_branch': 'main', 'size': 1,
                        'html_url': 'https://github.com/dongguk-creative-fusion-2026/pr-guard-sandbox'})
        else:
            self.reply({}, 404)

    def log_message(self, *args):
        pass


def api(path, body=None):
    request = Request(API + path, None if body is None else json.dumps(body).encode(), {'Content-Type': 'application/json'})
    with urlopen(request, timeout=80) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--scenario', choices=['success', 'wrong-commit', 'dispatch-error'], default='success')
    state['scenario'] = parser.parse_args().scenario
    server = ThreadingHTTPServer(('127.0.0.1', 18181), Upstream)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        for _ in range(90):
            try:
                api('/actuator/health')
                break
            except URLError:
                time.sleep(1)
        projects = api('/api/projects')
        project = next((p for p in projects if p['owner'] == 'dongguk-creative-fusion-2026' and p['name'] == 'pr-guard-sandbox'), None)
        if project is None:
            project = api('/api/projects', {'url': 'https://github.com/dongguk-creative-fusion-2026/pr-guard-sandbox'})
        api('/api/projects/' + str(project['id']) + '/poll', {})
        route = '/api/projects/' + str(project['id']) + '/pulls/24'
        candidate = api(route + '/invariant-settings/compile', {'rules': '게시글 수정 전후 작성자 ID는 같아야 한다'})['candidates'][0]
        try:
            run = api(route + '/invariant-runs', candidate)
            assert state['scenario'] != 'dispatch-error', run
        except HTTPError as error:
            assert state['scenario'] == 'dispatch-error' and error.code == 502, error.read()
            run = {'id': state['inputs']['run_id']}
        for _ in range(45):
            run = api(route + '/invariant-runs/' + run['id'])
            if run['status'] not in ('QUEUED', 'RUNNING'):
                break
            time.sleep(2)
        assert run['status'] == ('DONE' if state['scenario'] == 'success' else 'FAILED'), run
        assert run['result']['status'] == ('no_counterexample' if state['scenario'] == 'success' else 'execution_error'), run
        latest = api(route + '/invariant-runs/latest')['run']
        assert latest['id'] == run['id'] and latest['status'] == run['status'], latest
        assert latest['result'] == run['result'] and latest['createdAt'], latest
        print(json.dumps(run, ensure_ascii=False, indent=2))
        print('Actions API integration passed:', state['scenario'])
    finally:
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
