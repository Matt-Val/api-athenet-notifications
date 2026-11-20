# Cambios de integración — Notificaciones

Fecha: 18 de octubre de 2026. Documento de entrega para el responsable de este repositorio.

## Objetivo y alcance real del cambio

Conectar el servicio existente con los productores actualizados de Competencia y Eventos. La selección de suscriptores, confirmación, baja, procesamiento de resultados, entregas de correo, publicadores y worker SMTP ya existían: se reutilizaron y se validaron con mensajes reales.

El cambio de este repositorio está concentrado en configuración y pruebas de contrato. No se reescribieron esos servicios ni se añadieron tablas o endpoints productivos en esta entrega.

## Qué cambió y por qué

### Configuración de integración

En `src/main/resources/application.properties`:

```properties
app.newsletter.events-enabled=${NEWSLETTER_EVENTS_ENABLED:false}
spring.rabbitmq.virtual-host=${RABBITMQ_VHOST:/}
```

La primera propiedad permite activar los avisos de eventos por variable de entorno, sin editar el código. Se conserva false como valor por defecto para habilitarlos cuando el productor y la base estén preparados.

La segunda permite que todos los servicios utilicen un mismo vhost RabbitMQ. Mantiene `/` como valor por defecto para conservar compatibilidad con configuraciones anteriores.

### Contratos con los productores actuales

Se actualizaron estas pruebas:

- `CompetitionProducerContractTest`: compila una selección del código actual de Competencia y ejecuta su publicador junto con `OutboxEventWriter`, usando un repositorio simulado. Verifica el JSON realmente persistido por la outbox y lo pasa al consumidor de Notificaciones. Genera muestras en `target/contracts/competition`.
- `EventProducerContractTest`: hace lo equivalente con `EventPublishedPublisher` y la outbox de Eventos, usando sus clases actuales.
- `CompetitionResultContractTest`: deja de exigir el header Java `__TypeId__`. Las muestras históricas con ese header y el JSON actual sin él deben poder procesarse sin cargar clases del productor.

El propósito es evitar que pruebas basadas únicamente en JSON escrito a mano sigan pasando aunque el productor real cambie su contrato. Las pruebas que compilan otros repositorios requieren indicar `notifications.contract.competition-source` o `notifications.contract.events-source`, respectivamente. Consultar sus anotaciones/README para la ejecución conjunta.

## Responsabilidad dentro del recorrido

El servicio consume `resultado.registrado` en `notificaciones.resultados`. Consulta sus propios suscriptores y prepara entregas para activos confirmados a tiempo. Su lógica existente controla versiones y envía aviso de corrección cuando corresponde. El worker vuelve a verificar la elegibilidad antes de enviar.

Cuando `NEWSLETTER_EVENTS_ENABLED=true`, también consume publicación, modificación y cancelación de eventos desde `notificaciones.eventos`. Las tareas de envío utilizan `notificaciones.correos`. Ranking tiene otra cola: no compite con Notificaciones por el mismo mensaje.

La suscripción actual es al boletín general; no se añadieron filtros por equipo, deporte o institución.

## Validación realizada

Pasaron 54 pruebas seleccionadas, incluyendo contratos con ambos productores, procesamiento de resultados/eventos y worker. Esa ejecución automatizada no usó broker, SMTP ni base reales.

Después el usuario ejecutó el servicio con PostgreSQL, RabbitMQ y Mailpit locales y comprobó:

1. Recepción del aviso producido por Eventos tras una agenda sintética.
2. Resultado y corrección enviados únicamente al suscriptor activo; exclusión de pendiente y baja preparados por SQL.
3. Revisiones repetidas y antiguas sin nuevas entregas.
4. Conservación de un mensaje durante detención del servicio y envío al reiniciarlo.
5. Baja mediante el endpoint público; una corrección posterior no creó otra entrega para ese destinatario.
6. Resultado y corrección emitidos por Competencia mediante BFF y JWT reales: ambas entregas quedaron SENT al nuevo destinatario de prueba.

Los correos de las pruebas sintéticas se observaron en Mailpit. En el último tramo real se compartió evidencia de las dos entregas SENT en base de datos. Los suscriptores se prepararon por SQL: esto no acredita el registro HTTP, Turnstile ni la doble confirmación.

## Qué necesita quien publique o despliegue

Este cambio no agrega una migración propia de Notificaciones. El esquema debe estar actualizado conforme al código que ya traía el repositorio.

Configurar conexión a su esquema, RabbitMQ/vhost, SMTP, remitente y URLs públicas. Para probar localmente se usó Mailpit; esos valores y sus contraseñas de desarrollo no corresponden a producción. Mantener activos publicadores y worker. Habilitar `NEWSLETTER_EVENTS_ENABLED=true` cuando se quieran procesar avisos deportivos.

Los consumidores deben usar el mismo exchange/vhost que los productores, con colas independientes. No copiar tokens ni credenciales privadas al ZIP. No se desplegó SMTP de producción ni se acredita entrega exactamente una vez: una caída posterior a la aceptación SMTP y anterior a guardar SENT puede producir duplicación.

## Docker Hub — 2026-10-19

Se preparó la publicación de imágenes en Docker Hub y el despliegue por SSH usando la etiqueta SHA. Las credenciales del registro se configuran mediante `DOCKERHUB_USERNAME` (variable) y `DOCKERHUB_TOKEN` (secret). La preparación no incluye publicación ni despliegue ejecutados.
