import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

import { classifySmtpError, smtpMailer } from '../src/mail.ts';

it('classifies SMTP responses and uncertain sends without retrying permanent failures', () => {
  assert.equal(classifySmtpError({ responseCode: 421 }), 'RETRYABLE_FAILURE');
  assert.equal(classifySmtpError({ responseCode: 550 }), 'PERMANENT_FAILURE');
  assert.equal(classifySmtpError({ command: 'DATA' }), 'UNKNOWN');
  assert.equal(classifySmtpError({ code: 'ETIMEDOUT' }), 'UNKNOWN');
  assert.equal(classifySmtpError({ code: 'ECONNREFUSED', command: 'CONN' }), 'RETRYABLE_FAILURE');
});

const port = process.env.NOTIFICATIONS_TEST_SMTP_PORT;
const caFile = process.env.NOTIFICATIONS_TEST_SMTP_CA_FILE;
const mailpit = process.env.NOTIFICATIONS_TEST_MAILPIT_URL;
describe('SMTP STARTTLS against an isolated Mailpit', { skip: !port || !caFile || !mailpit }, () => {
  const options = { host: 'localhost', port: Number(port), requireTls: true, from: 'fixture@example.test' };
  it('delivers with the trusted CA and refuses an untrusted certificate', async () => {
    const subject = `fixture-${randomUUID()}`;
    const message = { to: 'fan@example.test', subject, text: 'Fictitious delivery for STARTTLS verification.' };
    assert.equal(await smtpMailer({ ...options, ca: null }).send(message), 'RETRYABLE_FAILURE');
    assert.equal(await smtpMailer({ ...options, ca: readFileSync(caFile!, 'utf8') }).send(message), 'DELIVERED');
    const response = await fetch(`${mailpit}/api/v1/messages?limit=50`);
    assert.equal(response.status, 200);
    const body = await response.json() as { messages: { Subject: string }[] };
    assert.equal(body.messages.filter((m) => m.Subject === subject).length, 1);
  });
});
