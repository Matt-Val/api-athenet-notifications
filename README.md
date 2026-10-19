<p align="center">
  <img src="assets/banner.png" alt="Athenet Banner" width="100%">
</p>

<h1 align="center">Athenet Notifications</h1>

<p align="center">
Microservicio de notificaciones de <strong>Athenet</strong>: administra las suscripciones al boletín y envía correos de resultados de competencias y avisos de eventos deportivos.
</p>

<h4 align="center">
<a href="https://github.com/fabetabilo/web-athenet">Athenet Público</a>
<br>
<a href="#ms-notifications">Funcionalidades y API</a> ·
<a href="#mensajería-rabbitmq">Mensajería</a> ·
<a href="#control-de-acceso">Control de Acceso</a> ·
<a href="#desarrollo-local">Desarrollo Local</a> ·
<a href="#despliegue">Despliegue</a>
</h4>

### Stack

<div align="center">

[![Java](https://img.shields.io/badge/Java_25-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot_4-%236DB33F.svg?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-%23336791.svg?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-%23FF6600.svg?style=for-the-badge&logo=rabbitmq&logoColor=white)](https://www.rabbitmq.com/)
[![Docker](https://img.shields.io/badge/Docker-%232496ED.svg?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)
[![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-%232088FF.svg?style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/features/actions)

</div>

* **Framework backend:** Spring Boot 4.1.1, Spring Web MVC, Spring Data JPA y Bean Validation.
* **Lenguaje:** Java 25.
* **Persistencia:** PostgreSQL mediante Hibernate/JPA.
* **Mensajería:** Spring AMQP con RabbitMQ; consumo de resultados y eventos, publicación de tareas de correo y confirmaciones de suscripción.
* **Correo:** Spring Mail y SMTP; Mailpit para desarrollo local.
* **Registro público:** consentimiento, validación de Cloudflare Turnstile y límites de solicitudes por correo e IP.
* **Build:** Maven mediante el wrapper `./mvnw`.
* **Contenedores y despliegue:** Docker multi-stage, Docker Compose y GitHub Actions con publicación en Docker Hub y despliegue por SSH a EC2.

### ms-notifications

El microservicio administra el ciclo de suscripción al boletín: registro, confirmación por correo y cancelación. Los resultados y avisos llegan desde RabbitMQ; el servicio persiste su procesamiento y las entregas antes de enviarlas por SMTP.

* **Suscripciones:** el registro requiere correo, consentimiento y token de Turnstile. La respuesta de aceptación no revela si el correo ya estaba registrado.
* **Resultados:** avisa del marcador final y sus correcciones, con control de versiones y duplicados.
* **Eventos:** avisa de publicación, modificación y cancelación de eventos deportivos `MATCH` y `TRACKDAY`.
* **Destinatarios:** selecciona suscriptores activos y confirmados al momento del evento de dominio; comprueba su elegibilidad antes del envío.
* **Entregas:** registra estado e intentos en PostgreSQL y utiliza una cola de tareas para el envío y sus reintentos.

| Método | Endpoint | Descripción |
| :--- | :--- | :--- |
| `POST` | `/subscriptions` | Solicitar suscripción; devuelve `202` si la solicitud se acepta |
| `POST` | `/subscriptions/confirm` | Confirmar suscripción con un token en JSON |
| `GET` | `/api/public/newsletter/confirm?token=...` | Mostrar formulario de confirmación |
| `POST` | `/api/public/newsletter/confirm` | Confirmar mediante JSON o formulario |
| `GET` | `/api/public/newsletter/unsubscribe?token=...` | Mostrar formulario de cancelación |
| `POST` | `/api/public/newsletter/unsubscribe` | Cancelar mediante JSON o formulario |

Ejemplo de registro:

```json
{
  "email": "persona@example.com",
  "consent": true,
  "turnstileToken": "TOKEN_DEL_WIDGET"
}
```

Las rutas de confirmación y cancelación reciben `token`. Abrir el enlace con `GET` muestra una página; el cambio de estado requiere `POST`, para evitar que los escáneres de enlaces del correo modifiquen la suscripción.

### Mensajería (RabbitMQ)

El exchange de dominio es `athenet.topic`, de tipo topic y durable. El servicio declara sus colas y bindings; los fallos de consumo utilizan el exchange `athenet.dlx` y las colas de mensajes fallidos correspondientes.

| Routing key | Cola | Función |
| :--- | :--- | :--- |
| `resultado.registrado` | `notificaciones.resultados` | Procesar resultados finales y correcciones de Competencia |
| `evento.publicado` | `notificaciones.eventos` | Avisar de la publicación de un evento deportivo |
| `evento.modificado` | `notificaciones.eventos` | Avisar de cambios relevantes |
| `evento.cancelado` | `notificaciones.eventos` | Avisar de su cancelación |
| `notificacion.email.solicitado` | `notificaciones.correos` | Ejecutar una tarea de envío SMTP |
| `subscription.confirmed` | `notificaciones.suscripciones.confirmadas` | Publicar la confirmación de suscripción como evento de dominio |

La cola `notificaciones.suscripciones.confirmadas` conserva eventos para un consumidor externo; este servicio no la consume ni la utiliza para enviar el correo de confirmación.

Los mensajes de resultados y eventos se procesan con control de revisión para evitar que duplicados o versiones antiguas creen nuevos avisos. Las entregas y los estados procesados quedan persistidos en PostgreSQL. El envío SMTP no ofrece una garantía de entrega exactamente una vez ante cualquier fallo.

Los avisos de eventos están desactivados por defecto. Para habilitarlos, configurar `NEWSLETTER_EVENTS_ENABLED=true` después de preparar el esquema y el productor de Eventos. También se admite la variable nativa `APP_NEWSLETTER_EVENTS_ENABLED=true`; el despliegue activo con Compose los habilita mediante argumentos de arranque.

Configurar el mismo vhost que los demás microservicios con `RABBITMQ_VHOST` o `SPRING_RABBITMQ_VIRTUAL_HOST` (por defecto `/`).

### Control de Acceso

Las rutas de suscripción son públicas y no requieren JWT de Microsoft Entra ID. Las operaciones de confirmación y cancelación se autorizan mediante el token enviado al correo. Las demás rutas quedan denegadas por Spring Security, salvo las rutas públicas y de error configuradas.

* **Registro:** requiere consentimiento, Turnstile válido y cumplimiento de los límites por correo e IP. Sin `TURNSTILE_SECRET_KEY`, el registro responde `503`.
* **CORS:** los orígenes permitidos se configuran mediante `CORS_ALLOWED_ORIGINS`.
* **Enlaces:** las respuestas de confirmación y cancelación evitan almacenamiento en caché; los enlaces `GET` no cambian el estado.
* **Credenciales:** las claves de PostgreSQL, RabbitMQ, SMTP y Turnstile se suministran mediante variables de entorno.

### Desarrollo Local

#### 1. Dependencias

* JDK 25 para ejecutar Java y compilar con `javac`.
* Docker y Docker Compose para PostgreSQL, RabbitMQ y Mailpit.
* Maven Wrapper incluido en el repositorio.

```bash
java -version
javac -version
./mvnw -version
```

#### 2. Configuración del entorno

Para una instalación nueva, copiar la plantilla sin sobrescribir un `.env` existente:

```bash
cp -n .env.example .env
```

Editar `.env` y completar los valores necesarios. Spring no carga este archivo automáticamente: exportarlo desde Bash antes de arrancar la aplicación.

| Variable | Función |
| :--- | :--- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Conexión PostgreSQL |
| `DB_DDL_AUTO` | Gestión del esquema; desarrollo `update`, despliegue `validate` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_SSL` | Conexión al broker |
| `RABBITMQ_VHOST` o `SPRING_RABBITMQ_VIRTUAL_HOST` | Vhost compartido con los productores |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | Servidor SMTP y remitente |
| `MAIL_SMTP_AUTH`, `MAIL_STARTTLS` | Autenticación y STARTTLS de SMTP |
| `NOTIFICATIONS_PUBLIC_API_URL` | URL pública del servicio usada en enlaces del correo |
| `FRONT_URL` | URL del sitio público enlazado desde el correo |
| `CORS_ALLOWED_ORIGINS` | Orígenes del frontend, separados por coma |
| `TURNSTILE_SECRET_KEY`, `TURNSTILE_ALLOWED_HOSTNAMES` | Validación del registro público |
| `NEWSLETTER_EVENTS_ENABLED` | Activación de avisos de eventos |

#### 3. Servicios de desarrollo

```bash
docker compose up -d
```

La plantilla `.env.example` corresponde a los puertos y credenciales locales de `compose.yaml`:

| Servicio | Dirección local |
| :--- | :--- |
| PostgreSQL | `127.0.0.1:5434`, base `notifications_db` |
| RabbitMQ AMQP | `127.0.0.1:5673` |
| RabbitMQ Management | `http://localhost:15673` |
| Mailpit SMTP | `127.0.0.1:1025` |
| Mailpit, buzón web | `http://localhost:8025` |

Para recibir mensajes de otros microservicios, todos deben utilizar el mismo broker, vhost y contratos. La plantilla usa Mailpit sin autenticación ni STARTTLS; producción requiere la configuración SMTP real.

#### 4. Arranque

```bash
set -a
source .env
set +a
./mvnw spring-boot:run
```

La API queda disponible en `http://localhost:8084`. Para probar el registro, configurar Turnstile y el hostname correspondiente al frontend.

#### 5. Compilación y pruebas

```bash
./mvnw clean package -DskipTests
./mvnw verify
```

El artefacto se genera en `target/notifications-0.0.1-SNAPSHOT.jar`. GitHub Actions prepara PostgreSQL temporal y las variables `NOTIFICATIONS_DB_TEST_URL`, `NOTIFICATIONS_DB_TEST_USER` y `NOTIFICATIONS_DB_TEST_PASSWORD` para las pruebas que lo requieren.

### Despliegue

El `Dockerfile` compila con JDK 25 y ejecuta con JRE 25 como usuario sin privilegios. El workflow `.github/workflows/ci.yml` implementa:

1. **Test:** ejecuta `./mvnw verify` en pull requests y pushes a `main` y `develop`, con PostgreSQL temporal en el runner.
2. **Build & Push:** después de pasar las pruebas, un push a `main` o una ejecución manual en `main` publica la imagen en Docker Hub con etiquetas `latest` y SHA del commit.
3. **Deploy:** copia los archivos Compose a EC2, conserva `deploy/.env` y descarga la imagen identificada por el SHA del commit. Ejecuta el servicio con el esquema en modo `validate` y los consumidores, publicadores y envío de correos activos.

Flujo de ramas: `feature/*` → PR a `develop` → PR a `main`. Un push a `develop` ejecuta pruebas; no despliega. Integrar en `main` activa el despliegue automático.

Configurar en **Settings → Secrets and variables → Actions** del repositorio:

| Tipo | Nombre | Valor |
| :--- | :--- | :--- |
| Variable | `DOCKERHUB_USERNAME` | Usuario de Docker Hub |
| Secret | `DOCKERHUB_TOKEN` | Token con permisos de lectura y escritura |
| Secret | `EC2_APP_HOST` | IP o hostname de la EC2 de Notificaciones |
| Secret | `EC2_SSH_USER` | Usuario SSH de la instancia, por ejemplo `ec2-user` |
| Secret | `EC2_SSH_KEY` | Contenido completo de la clave privada SSH |

Preparar Docker, Docker Compose y `curl` en la EC2. El usuario SSH debe poder ejecutar Docker y disponer de `~/api-athenet-notifications/deploy/.env`, configurado a partir de `deploy/ec2.env.example`. Allí permanecen las credenciales de RDS, RabbitMQ, SMTP y Turnstile; el pipeline no las reemplaza.

La imagen se publica como `DOCKERHUB_USERNAME/api-athenet-notifications`. El despliegue utiliza `deploy/compose.ec2.yaml` y `deploy/compose.active.yaml`, publica el puerto `8084` y comprueba que la API responda `400` a una solicitud de registro vacía. Esta comprobación no acredita entrega de correos ni procesamiento de mensajes; verificar ambos después del despliegue. No se configura reversión automática.

Preparar el esquema antes de desplegar cambios que lo requieran. En producción, usar la base `notifications_db`, su usuario de servicio y direcciones de red accesibles para las dependencias. El modo `validate` comprueba el esquema y no crea ni actualiza tablas.

### Estructura del Proyecto

```text
src/main/java/com/athenet/notifications/
├── client/       # Validación de Turnstile
├── config/       # Seguridad, CORS, RabbitMQ y propiedades del boletín
├── controller/   # Registro, confirmación y cancelación de suscripciones
├── exception/    # Errores HTTP, publicación y entrega
├── mail/         # Transporte de correo SMTP
├── messaging/    # Consumidores de resultados/eventos/correos y publicadores
├── model/        # Suscriptores, entregas y estados de procesamiento
├── repository/   # Repositorios Spring Data JPA
└── service/      # Suscripciones, avisos, planificación y entrega
```

Los archivos `.env`, claves privadas y artefactos generados quedan fuera de Git. Los directorios `docs/`, `db/` y `scripts/` se conservan localmente según el `.gitignore` del repositorio.
