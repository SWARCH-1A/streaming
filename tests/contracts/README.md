# Pruebas de contratos

Verifican que productores y consumidores respeten contratos compartidos. Organiza cada caso por límite
de contrato y nombra los módulos implicados; la fuente semántica está en docs/contratos_modelo_datos.md.
# Contrato Core–Streaming

Desde la raíz, con PowerShell 7, Git y Docker Desktop:

```powershell
git fetch origin feat/streaming-live-session
.\tests\contracts\test-runner-helpers.ps1
.\infra\local\test-core.ps1
.\tests\contracts\test-streaming-core.ps1
```

El runner fija el commit del PR #7 `f9dc6d164242b24bdc20e29ceefdc3978b215390` (se puede cambiar con
`-StreamingRef` después de revisar compatibilidad). Extrae su crate a `services/core/target`, copia
el harness y compila en Rust 1.98 importando `CoreHttpGateway` real, sin reimplementar el cliente.
Core se construye desde el checkout actual. El harness valida formato/tipos, contexto/operación,
campos omitidos, owner ajeno, logout, errores 401/403/404/422, tombstones y puerto público cerrado.
También usa una instancia del mismo cliente con la credencial limitada: resuelve catálogo pero
rechaza todas las operaciones owner incluso con sesión válida, sin modificar el crate consumidor.
No requiere instalar Rust, Java o PostgreSQL en Windows.

Antes de cada extracción el helper verifica el commit y genera el archivo; luego reemplaza solo
`target/streaming-contract/source`, verificando ruta y ausencia de enlaces/reparse points. Así,
archivos eliminados/renombrados por otro `-StreamingRef` no quedan en la compilación. Las cachés
Cargo/target y la configuración/volúmenes persistentes permanecen fuera de esa limpieza.
`test-runner-helpers.ps1` comprueba dos commits reales de un repositorio temporal, referencias
inválidas, protección de rutas/enlaces y seis escenarios LF/CRLF de inicialización de secretos;
no necesita Docker ni instala dependencias. Sus fixtures quedan bajo `target` ignorado.

La suite Java prueba las rutas sobre HTTPS real con certificado efímero, además de fallo SQL/503,
correlación, límites y CSRF público. El harness Rust usa HTTP explícito en la red Docker aislada;
no acredita TLS de un despliegue productivo ni carga/latencia del perfil SPEC-13.

El proyecto `taxonomy-contracts` usa volúmenes propios y puertos loopback 18081/15440; su conector
privado no se publica. Crea cuentas ficticias, agrega un tombstone y verifica mismos IDs, labels,
versión y canal después de reiniciar PostgreSQL y Core conservando volúmenes. Detiene contenedores
al terminar, conserva los datos y borra las credenciales de sesión del fixture temporal. Las
cachés `streaming-contract-cargo` y `streaming-contract-target` permiten repetir sin recompilar todo.
Los secretos de ese entorno y logs quedan exclusivamente bajo `target` ignorado. No incluirlos
en commits. Repetir muchas veces puede activar las cuotas normales de registro; no desactivarlas.

SPEC-06 permanece abierta hasta probar asociaciones/edición LIVE y consultas Discovery; SPEC-08
espera Web. La compatibilidad del proveedor no reemplaza la aceptación E2E.
