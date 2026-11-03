"""Optional private reusable accounts for repeated local browser/media scenarios.

P2_TEST_ACCOUNTS points to a mode-0600 JSON file outside Git. Each scenario gets
separate owner/fan accounts: replay-owner, replay-fan, ssr-owner, ssr-fan. Each entry
has handle/password/account (the public registration response). Never print it.
"""
import json
import os
from pathlib import Path
import re


def fixture_account(role):
    filename = os.environ.get('P2_TEST_ACCOUNTS')
    if not filename:
        return None
    path = Path(filename)
    if os.name != 'nt' and path.stat().st_mode & 0o077:
        raise RuntimeError('The fixture account file must be private (chmod 600)')
    entry = json.loads(path.read_text())[role]
    if not re.fullmatch(r'[a-z0-9_]{3,32}', entry['handle']) or not isinstance(entry['password'], str):
        raise RuntimeError('Invalid fixture account entry')
    return entry
