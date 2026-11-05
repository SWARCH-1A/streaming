"""Real Web recovery request -> broker -> Notifications SMTP -> Mailpit -> reset form.

Uses only the dedicated rel_ replay-fan fixture from P2_TEST_ACCOUNTS. Does not
submit a password change; confirmation is a manual acceptance step. Never logs
reset links/tokens, email bodies or credentials; no browser tracing/screenshots.
"""
from pathlib import Path
import json
import os
import re
import sys
import time
import urllib.request

os.environ['P1_PROFILE'] = 'local'
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'p1-delivery'))
import verify as v
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'p2-fixtures'))
from accounts import fixture_account
from playwright.sync_api import sync_playwright


def mail(path):
    with urllib.request.urlopen('http://127.0.0.1:8025'+path,timeout=5) as response:
        return json.load(response)


def main():
    account = fixture_account('replay-fan')
    if not account or not account['handle'].startswith('rel_'):
        raise RuntimeError('Dedicated fictitious local recovery fixture required')
    address = account['handle']+'@example.test'
    before = {message['ID'] for message in mail('/api/v1/messages?limit=100')['messages']}
    with sync_playwright() as p:
        browser=p.chromium.launch()
        page=browser.new_page(ignore_https_errors=True)
        page.goto(v.ORIGIN+'/forgot-password')
        page.get_by_label('Correo electrónico',exact=True).fill(address)
        page.get_by_role('button',name='Enviar correo de recuperación',exact=True).click()
        page.get_by_role('link',name='Ir a iniciar sesión',exact=True).wait_for()
        def delivered():
            return [m for m in mail('/api/v1/messages?limit=100')['messages'] if m['ID'] not in before and any(to.get('Address')==address for to in m.get('To',[]))]
        messages=v.eventually(delivered,bool,45)
        assert len(messages)==1
        body=mail('/api/v1/message/'+messages[0]['ID'])
        match=re.search(re.escape(v.ORIGIN)+r'/reset-password#token=([A-Za-z0-9_-]{43})',body['Text'])
        assert match,'Canonical recovery link missing from SMTP message'
        page.goto(match.group(0))
        page.get_by_label('Nueva contraseña',exact=True).wait_for()
        page.get_by_label('Repite la contraseña',exact=True).wait_for()
        assert '#' not in page.url,'Token was retained in browser URL'
        time.sleep(2)
        assert len(delivered())==1
        print('PASS Web recovery request, real asynchronous SMTP, canonical link, fragment cleanup and reset form',flush=True)
        print('Password confirmation is a manual UI acceptance step; this runner does not submit it.',flush=True)
        browser.close()


if __name__=='__main__':main()
