"""Guardrails for project isolation; no Docker daemon required."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import lab


class IsolationTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.state = Path(self.folder.name)
        self.config = {'project': 'vault-lab5-123456ab', 'mode': 'http'}
        (self.state / 'config.json').write_text(json.dumps(self.config))
        self.state_patch = patch.object(lab, 'STATE', self.state)
        self.state_patch.start()

    def tearDown(self):
        self.state_patch.stop()
        self.folder.cleanup()

    def test_invalid_project_cannot_target_personal_deployment(self):
        self.config['project'] = 'personal-vault'
        (self.state / 'config.json').write_text(json.dumps(self.config))
        with self.assertRaises(RuntimeError):
            lab.configuration()

    def test_ambient_lab_variables_do_not_override_private_state(self):
        with patch.dict(lab.os.environ, LAB_PROJECT='personal', LAB_DB_PASSWORD='ambient'), patch.object(lab, 'execute') as run:
            lab.compose(['ps'])
        self.assertNotIn('LAB_PROJECT', run.call_args.kwargs['env'])
        self.assertNotIn('LAB_DB_PASSWORD', run.call_args.kwargs['env'])
        self.assertIn('vault-lab5-123456ab', run.call_args.args[0])
        self.assertIn(str(self.state / 'environment.env'), run.call_args.args[0])

    def test_cleanup_needs_no_tls_files(self):
        self.config['mode'] = 'https'
        (self.state / 'config.json').write_text(json.dumps(self.config))
        with patch.object(lab.sys, 'argv', ['lab.py', 'down']), patch.object(lab, 'execute') as run:
            lab.main()
        self.assertIn('down', run.call_args.args[0])
        self.assertNotIn('-v', run.call_args.args[0])

    def test_https_requires_explicit_material(self):
        with self.assertRaises(RuntimeError), patch.object(lab, 'execute') as run:
            lab.compose(['up'], mode='https')
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
