"""Streaming authorization test port, not a replacement for the Rust producer.

Returns the same private recording headers as the Rust Media adapter, including
a repeated ingest path at a new generation. No engine configuration is changed.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading

lock=threading.Lock()
attempts={}
generations={}


class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        self.send_response(204);self.end_headers()
    def do_POST(self):
        value=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
        if value['action']=='api':
            self.send_response(204);self.end_headers();return
        path=value.get('path','')
        if value['action']!='publish' or path not in ['live/sk_'+letter*64 for letter in 'abc']:
            self.send_response(403);self.end_headers();return
        with lock:
            if value['id'] not in attempts:
                generation=generations.get(path,0)+1;generations[path]=generation
                attempts[value['id']]=generation
            generation=attempts[value['id']]
        session='ses_00000000-0000-4000-8000-0000000000'+{'a':'14','b':'15','c':'16'}[path[-1]]
        self.send_response(204)
        self.send_header('X-Media-Recording-Session',session)
        self.send_header('X-Media-Recording-Source-Generation',str(generation))
        self.end_headers()


ThreadingHTTPServer(('0.0.0.0',8090),Handler).serve_forever()
