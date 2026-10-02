# Chat

Servicio propio de salas, mensajes, cuota global por cuenta, deduplicación, secuencia, historial y
WebSocket. Se define en [SPEC-05](../../docs/spec-p1/spec_05_chat.md) y
[contratos](../../docs/contratos_modelo_datos.md). Obtiene un contexto Core por mensaje nuevo;
no consulta servicios separados de identidad/perfil/emisión. Go/MongoDB son candidatos sujetos a ADR.
