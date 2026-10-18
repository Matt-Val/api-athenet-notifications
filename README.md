# Athenet Notifications

Microservicio Spring Boot para suscripciones al boletín y envío de correos de resultados y eventos deportivos.

- Registro con consentimiento y Turnstile; confirmación por correo y cancelación de suscripción.
- Consumo de resultados desde RabbitMQ, con control de versiones y duplicados.
- Avisos de publicación, modificación y cancelación de eventos MATCH/TRACKDAY.
- Entregas persistidas en PostgreSQL y envío por SMTP mediante una cola de tareas.

## Desarrollo

Requiere Java 25. Copiar `.env.example` a `.env` y completar los valores privados. `compose.yaml` contiene PostgreSQL, RabbitMQ y Mailpit para desarrollo. Para arrancar desde Bash:

```bash
set -a
source .env
set +a
./mvnw spring-boot:run
```

## Ramas y automatización

`feature/*` → PR a `develop` → PR a `main`.

El workflow `.github/workflows/ci.yml` ejecuta las pruebas en los PR y pushes a main/develop. Solo un push a main publica la imagen en GHCR y despliega en EC2. Las pruebas de PostgreSQL usan una base temporal del runner.

Configurar estos secretos en GitHub Actions:

- `EC2_APP_HOST`: IP o hostname de Notificaciones.
- `EC2_SSH_USER`: `ec2-user`.
- `EC2_SSH_KEY`: contenido de la clave privada SSH de esa instancia.

El despliegue necesita Docker, Compose y acceso SSH desde el runner. Usa el directorio `~/api-athenet-notifications` y conserva `deploy/.env`, configurado previamente en la EC2. Las credenciales de RDS, RabbitMQ, Gmail y Turnstile permanecen en ese archivo. El token de GHCR lo proporciona GitHub Actions automáticamente.

Los archivos de `deploy/` mantienen el despliegue Compose existente y esquema `validate`; las migraciones se aplican manualmente antes de desplegar cambios que las requieran. El despliegue comprueba HTTP local, sin acreditar por sí solo entrega de correos. No se configura reversión automática ante un fallo.

`docs/`, `db/`, `scripts/`, `.env`, claves privadas y artefactos generados quedan fuera de Git. Las migraciones y herramientas manuales se conservan localmente.
