import { readFileSync } from 'node:fs';
import { createServer } from 'node:https';
import { handleRequest } from '../ejemplo-http/handler.mjs';

// Mismo manejador del Grupo 1; solo cambia el transporte: HTTP pasa a HTTPS.
const certs = new URL('./certs/', import.meta.url);
let options;
try {
  options = {
    key: readFileSync(new URL('server.key', certs)),
    cert: readFileSync(new URL('server.crt', certs)),
    minVersion: 'TLSv1.2',
  };
} catch (error) {
  console.error(`Faltan la llave o el certificado en ejemplo-https/certs: ${error.message}`);
  process.exit(1);
}

const server = createServer(options, handleRequest);
server.on('error', (error) => {
  console.error(`No se pudo iniciar el servidor: ${error.message}`);
  process.exitCode = 1;
});
server.listen(18443, '127.0.0.1', () => {
  console.log('Servidor del laboratorio: https://localhost:18443/mensaje');
  console.log('Déjalo abierto. Para terminar, pulsa Ctrl+C en esta terminal.');
});
