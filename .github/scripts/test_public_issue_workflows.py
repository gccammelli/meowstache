#!/usr/bin/env python3
"""Offline regression checks for public issue workflow routing."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
DRAIN = yaml.safe_load((ROOT / '.github/workflows/public-issue-drain.yml').read_text())
AGENT = yaml.safe_load((ROOT / '.github/workflows/public-issue.yml').read_text())


class WorkflowTest(unittest.TestCase):
    def test_queue_uses_current_labels(self):
        step = DRAIN['jobs']['queue']['steps'][0]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'gh').write_text('''#!/bin/bash
if [[ "$1 $2" == "issue view" ]]; then
  jq -c "${@: -1}" "$STATE"
elif [[ "$1 $2" == "issue edit" ]]; then
  echo '{"state": "OPEN", "labels": [{"name": "bug"}, {"name": "agent-backlog"}]}' > "$STATE"
  echo edit >> "$LOG"
else
  exit 1
fi
''')
            (path / 'gh').chmod(0o755)
            (path / 'state').write_text('{"state": "OPEN", "labels": [{"name": "bug"}]}')
            env = dict(os.environ, PATH=directory + ':' + os.environ['PATH'],
                       STATE=str(path / 'state'), LOG=str(path / 'log'),
                       ENABLED='true', BODY='x' * 40, ISSUE='19', REPOSITORY='demo/repo')
            for _ in range(2):
                subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', step['run']], env=env, check=True)
            self.assertEqual((path / 'log').read_text().splitlines(), ['edit'])
            (path / 'log').unlink()
            (path / 'state').write_text('{"state": "CLOSED", "labels": [{"name": "bug"}]}')
            subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', step['run']], env=env, check=True)
            self.assertFalse((path / 'log').exists())
        self.assertIn('github.event.issue.number', DRAIN['jobs']['queue']['concurrency']['group'])

    def test_gate_outputs_and_status_paths(self):
        gate = AGENT['jobs']['intake']['steps'][0]
        self.assertEqual(gate['env']['ENABLED'], '${{ vars.PUBLIC_ISSUE_AGENT_ENABLED }}')
        self.assertIn('run=stop', gate['run'])
        self.assertIn('run=go', gate['run'])
        self.assertIn('over-budget=go', gate['run'])
        self.assertIn("outputs.run == 'go'", AGENT['jobs']['repair']['if'])
        self.assertIn("outputs.over-budget != 'go'", AGENT['jobs']['intake-declined']['if'])
        for job in ('intake-declined', 'publish', 'declined', 'failed'):
            scripts = '\n'.join(step.get('run', '') for step in AGENT['jobs'][job]['steps'])
            self.assertIn('<!-- cammelli-bot-status -->', scripts)
            self.assertIn('github-actions[bot]', scripts)
            self.assertIn('--method PATCH', scripts)
            self.assertIn('--method POST', scripts)
        self.assertNotIn('comment', DRAIN['jobs']['queue']['steps'][0]['run'])

    def run_labels(self, event, labels):
        step = DRAIN['jobs']['labels']['steps'][0]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            # Fake gh: answers the --jq filter with real jq, logs edits.
            (path / 'gh').write_text('''#!/bin/bash
if [[ "$1 $2" == "issue view" ]]; then
  jq -r "${@: -1}" <<< "$LABELS"
elif [[ "$1 $2" == "issue edit" ]]; then
  echo "${@: -1}" >> "$LOG"
else
  exit 1
fi
''')
            (path / 'gh').chmod(0o755)
            env = dict(os.environ, PATH=directory + ':' + os.environ['PATH'], LOG=str(path / 'log'),
                       LABELS='{"labels": [%s]}' % ', '.join('{"name": "%s"}' % l for l in labels),
                       EVENT=event, ISSUE='26', REPOSITORY='demo/repo')
            subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', step['run']], env=env, check=True)
            log = path / 'log'
            return log.read_text().splitlines() if log.exists() else []

    def test_labels_follow_maintainer_reply_and_close(self):
        everything = ['enhancement', 'agent-attempted', 'agent-needs-review', 'agent-backlog', 'agent-working']
        self.assertEqual(self.run_labels('issue_comment', everything), ['agent-needs-review'])
        self.assertEqual(self.run_labels('issues', everything),
                         ['agent-needs-review,agent-backlog,agent-working'])
        self.assertEqual(self.run_labels('issue_comment', ['enhancement', 'agent-pr']), [])
        condition = DRAIN['jobs']['labels']['if']
        self.assertIn("'OWNER'".replace("'", '"'), condition)
        self.assertIn('!github.event.issue.pull_request', condition)
        self.assertIn("action != 'closed'", DRAIN['jobs']['queue']['if'])
        self.assertIn('agent-needs-review', DRAIN['jobs']['drain']['steps'][0]['run'])


if __name__ == '__main__':
    unittest.main()
