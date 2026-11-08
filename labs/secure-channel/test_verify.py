"""Parser regressions: ensure exposure evidence stays tied to an authenticated flow."""
import json
import unittest
from verify import check_http, directions, messages


def http(start, body=None, **headers):
    data = json.dumps(body).encode() if body is not None else b''
    headers['Content-Length'] = str(len(data))
    return (start + '\r\n' + ''.join(f'{k}: {v}\r\n' for k, v in headers.items()) + '\r\n').encode() + data


class CaptureEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.credentials = {'email': 'student@example.test', 'password': 'LAB5_CANAL_INSEGURO_demo'}
        self.identity = {'userId': 'fictitious-user'}
        self.streams = [
            [http('POST /api/identity/sessions HTTP/1.1',
                  {'login': self.credentials['email'], 'password': self.credentials['password']},
                  **{'Cookie': 'XSRF-TOKEN=fictitious', 'X-XSRF-TOKEN': 'fictitious'}),
             http('HTTP/1.1 200 OK', self.identity, **{'Set-Cookie': 'stream_session=fake; HttpOnly; SameSite=Lax'})],
            [http('GET /api/profile/me HTTP/1.1', **{'Cookie': 'XSRF-TOKEN=fictitious; stream_session=fake'}),
             http('HTTP/1.1 200 OK', self.identity)]]

    def test_complete_flow(self):
        self.assertTrue(check_http(self.streams, self.credentials, self.identity)['csrfPreserved'])

    def test_marker_alone_not_enough(self):
        with self.assertRaises(ValueError):
            check_http(self.streams[:1], self.credentials, self.identity)

    def test_unrelated_session_rejected(self):
        self.streams[1][0] = self.streams[1][0].replace(b'stream_session=fake', b'stream_session=other')
        with self.assertRaises(ValueError):
            check_http(self.streams, self.credentials, self.identity)

    def test_incorrect_identity_rejected(self):
        with self.assertRaises(ValueError):
            check_http(self.streams, self.credentials, {'userId': 'someone-else'})

    def test_fragmented_tcp_reassembled(self):
        self.assertEqual(directions('header\n504f\n5354\n\t4854\n\t5450\nseparator'), [b'POST', b'HTTP'])

    def test_truncated_http_rejected(self):
        with self.assertRaises(ValueError):
            messages(b'POST / HTTP/1.1\r\nContent-Length: 99\r\n\r\n{}')

    def test_chunked_json_response(self):
        data = b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n'
        self.assertEqual(messages(data)[0][2], b'{}')

    def test_truncated_chunk_rejected(self):
        with self.assertRaises(ValueError):
            messages(b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n9\r\n{}')


if __name__ == '__main__':
    unittest.main()
