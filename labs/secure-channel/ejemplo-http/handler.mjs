// El mismo manejador puede conectarse después a un servidor HTTPS.
export async function handleRequest(request, response) {
  const reply = (status, body) => {
    const payload = JSON.stringify(body);
    response.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(payload),
      'Cache-Control': 'no-store',
    });
    response.end(payload);
  };

  if (request.url !== '/mensaje') {
    reply(404, { error: 'La ruta del ejemplo es /mensaje.' });
    return;
  }
  if (request.method !== 'POST') {
    reply(405, { error: 'Envía una solicitud POST.' });
    return;
  }
  if (request.headers['content-type']?.split(';')[0].trim() !== 'application/json') {
    reply(415, { error: 'Usa Content-Type: application/json.' });
    return;
  }

  try {
    const chunks = [];
    let size = 0;
    for await (const chunk of request) {
      size += chunk.length;
      if (size > 16384) {
        reply(413, { error: 'El ejemplo admite hasta 16 KiB.' });
        return;
      }
      chunks.push(chunk);
    }
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!body || typeof body.usuario !== 'string' || !body.usuario.trim()
      || typeof body.mensaje !== 'string' || !body.mensaje.trim()) {
      reply(400, { error: 'Incluye usuario y mensaje como textos no vacíos.' });
      return;
    }
    reply(200, { recibido: true, usuario: body.usuario, mensaje: body.mensaje });
  } catch {
    if (!response.headersSent && !response.destroyed) {
      reply(400, { error: 'Revisa que el cuerpo sea un JSON válido.' });
    }
  }
}
