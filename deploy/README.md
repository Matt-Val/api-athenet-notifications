# EC2 — comandos de preparación

Instancia propuesta: Amazon Linux 2023, x86_64, al menos 2 GiB de RAM, en la VPC con acceso a RDS y RabbitMQ. No se ha creado ninguna instancia. Disco de 20 GiB como punto de partida; revisar disponibilidad del laboratorio.

Red: SSH 22 desde tu IP; API 8084 solo desde el BFF/proxy y, durante la revisión, tu IP. Permitir acceso desde esta instancia a PostgreSQL 5432 y AMQP 5672 en los grupos correspondientes, además de salida SMTP 587 y HTTPS 443. Mantener TLS de RabbitMQ según la configuración real del broker. La plantilla supone su listener actual sin TLS en 5672.

Antes de activarlo: detener la otra instancia de Notificaciones que consuma el mismo vhost. Aplicar `db/event-notifications.sql` a la BD existente con copia de seguridad y la API detenida; no crea el esquema inicial de una BD vacía. El productor de Eventos requiere su propia migración y versión actualizada. Este despliegue no ejecuta migraciones automáticamente.

Construir en EC2 x86_64 con Docker y Compose v2 instalados, desde la raíz del repositorio:

```bash
docker build -t athenet-notifications:ec2 .
cp -n deploy/ec2.env.example deploy/.env
chmod 600 deploy/.env
nano deploy/.env
```

Completar todos los REEMPLAZAR y revisar destinos/URLs antes de arrancar. No copiar credenciales al chat. Este archivo es formato env de Compose: no usar comandos Bash, referencias a otras variables ni `export`. No ejecutar `docker compose config` sin `--quiet` con credenciales reales: puede mostrarlas.

Revisión inicial, solo HTTP local, procesamiento pausado y esquema validado:

```bash
docker compose -f deploy/compose.ec2.yaml config --quiet
docker compose -f deploy/compose.ec2.yaml up -d
docker compose -f deploy/compose.ec2.yaml ps
docker compose -f deploy/compose.ec2.yaml logs --tail 80 notifications
curl -i --max-time 10 http://127.0.0.1:8084/subscriptions \
  -H 'Content-Type: application/json' -d '{}'
```

Esperar arranque sin errores y HTTP 400 por payload vacío. Esto comprueba HTTP y arranque/JPA; no comprueba RabbitMQ ni SMTP, pausados en esta fase. Si falla el esquema, revisar la migración; mantener `validate`.

Después de comprobar conexiones y acordar la URL pública, activar:

```bash
docker compose -f deploy/compose.ec2.yaml -f deploy/compose.active.yaml config --quiet
docker compose -f deploy/compose.ec2.yaml -f deploy/compose.active.yaml up -d
docker compose -f deploy/compose.ec2.yaml logs --tail 80 notifications
```

Para acceso desde el BFF en otra instancia, ejecutar el arranque anterior con `NOTIFICATIONS_BIND_ADDRESS=0.0.0.0` y restringir 8084 mediante el grupo de seguridad. Si el proxy HTTPS está en la misma máquina, conservar 127.0.0.1. Las URLs públicas deben apuntar al acceso HTTPS definitivo, no a esta dirección interna. Repetir la variable en posteriores comandos `up` para conservar el mismo bind.

Para pausar otra vez, usar `up -d` con el archivo base únicamente: también restaura el bind local si no se exportó uno diferente. Para detener: `docker compose -f deploy/compose.ec2.yaml stop notifications`.

Solo se ejecuta la API: RDS, broker y correo son externos. La imagen usa `spring`; el contenedor tiene reinicio automático, límite de memoria y rotación de logs. No hay endpoint de salud añadido. Una actualización requiere nueva imagen y `up -d`; conservar la etiqueta de la versión anterior para volver atrás, sin revertir migraciones automáticamente.

Referencias: [Docker Compose](https://docs.docker.com/reference/compose-file/services/), [Docker en Amazon Linux](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-docker.html).
