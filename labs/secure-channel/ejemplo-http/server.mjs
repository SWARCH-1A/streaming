import { createServer } from 'node:http';
import { handleRequest } from './handler.mjs';

const server = createServer(handleRequest);
server.on('error', (error) => {
  console.error(`No se pudo iniciar el servidor: ${error.message}`);
  process.exitCode = 1;
});
server.listen(18080, '127.0.0.1', () => {
  console.log('Servidor del laboratorio: http://127.0.0.1:18080/mensaje');
  console.log('Déjalo abierto. Para terminar, pulsa Ctrl+C en esta terminal.');
});
