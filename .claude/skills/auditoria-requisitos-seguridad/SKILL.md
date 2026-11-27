---
name: auditoria-requisitos-seguridad
description: Audita un repositorio frente a la checklist de requisitos de seguridad de SSS P1 y saca, por requisito, estado (Sí/Parcial/No), ruta y fragmento de código como evidencia.
---

# Auditoría de requisitos de seguridad (SSS – P1)

Objetivo: recorrer el código del proyecto y, para cada requisito de la checklist, encontrar la **evidencia real** de su implementación. El resultado alimenta la memoria técnica.

## Regla de oro

**Nunca inventes evidencia.** Cada "Sí" o "Parcial" debe ir acompañado de `ruta/fichero:líneas` y de un fragmento copiado literalmente del repositorio. Si no encuentras nada, el estado es "No" y explicas qué has buscado. Lo que no se puede verificar en el código (el AGR, el despliegue HTTPS en el servidor o el panel de Sonar) se marca como **"Verificación manual"** y se indica qué documento o captura hace falta.

## Paso 1 – Reconocimiento

1. Detecta el stack: `pom.xml` / `build.gradle` (Spring Boot), `package.json` (frontend), `application.properties|yml`, `docker-compose`, la configuración de MongoDB.
2. Haz un mapa de los paquetes: controllers, services, repositories, security/config, model/DTO.
3. Localiza los tests y la configuración de cobertura (JaCoCo) y de Sonar (`sonar-project.properties`, plugin en el pom).

## Paso 2 – Buscar evidencia por requisito

Usa grep o búsqueda en el repositorio con estas pistas (adáptalas al stack real):

| RQ | Qué comprobar | Pistas de búsqueda |
|---|---|---|
| 01 | Login con usuario/contraseña y RBAC | `SecurityFilterChain`, `hasRole`, `@PreAuthorize`, `@Secured`, `GrantedAuthority`, enum `Role` |
| 02 | Registro, listas/favoritos, valoraciones, perfil, suscripción | controllers `register`, `favorit`, `rating|valoracion`, `profile|perfil` |
| 03 | Gestión de contenidos, estadísticas, consulta de valoraciones | endpoints admin/creador de productos y estadísticas |
| 04 | Gestión de usuarios solo para ADMIN (crear solo admins, modificar, consultar, bloquear/desbloquear, eliminar) | `/admin/**`, `locked|blocked|enabled`, `deleteUser` |
| 05 | Política de contraseñas (≥8, blacklist, sin datos personales, caducidad 30 días, historial de 5) | `PasswordValidator`, `passay`, `@Size(min`, `passwordHistory`, `passwordChangedAt` |
| 06 / 18 | Registro seguro y recuperación de contraseña (token aleatorio, caducidad, de un solo uso, hash del token) | `reset`, `forgot`, `SecureRandom`, `token`, `expir` |
| 07 | Sesión: cookies HttpOnly/Secure/SameSite, rotación, idle 15–30 min, absoluto 8–12 h | `sessionFixation`, `changeSessionId`, `server.servlet.session`, `SameSite`, `maximumSessions` |
| 08 | Compatibilidad entre navegadores | estándares en el frontend, ausencia de APIs propietarias (normalmente verificación manual) |
| 09 | HTTPS | `server.ssl`, `requiresSecure`, HSTS, proxy/Render (en parte verificación manual) |
| 10 / 17 | Datos sensibles protegidos en la BD y seguridad a nivel de BD | cifrado de campos, `@Encrypted`, CSFLE, usuarios Mongo con roles mínimos, SCRAM-SHA-256, `tls=true` en la URI |
| 11 / 13 / 14 | 0 bloqueantes/críticos, cobertura > 60 %, sin código muerto, Sonar/SonarLint | informes de JaCoCo y Sonar, Quality Gate (verificación manual con captura) |
| 12 | AGR ISO 27001:2022 con MARISMA | documento externo (verificación manual) |
| 15 | CCN-CERT BP/28 | relaciona cada principio con las medidas encontradas |
| 16 | 2FA (TOTP/FIDO2) y 3FA (OTP por e-mail, 10 min, con hash); obligatorio para Admin/Creador | `totp`, `GoogleAuthenticator`, `webauthn`, `otp`, `backupCodes`, secreto cifrado |
| 19 | Análisis de código con IA integrado en el flujo | `.github/workflows/*codeql*|semgrep|snyk*`, ficheros SARIF |

Criterios detallados de las aclaraciones (compruébalos dentro del RQ que corresponda):
- **Contraseñas y anti-stuffing:** Argon2id (64–256 MB, 2–4 iteraciones) o bcrypt (cost 12–14) + **pepper** desde env/keystore; comparación en tiempo constante; verificación de contraseñas filtradas (diccionario o k-anonymity) en el alta y en el cambio; rate limit de 5 intentos / 5 min por IP+usuario; backoff de 30 s → 2 → 4 → 5 min; umbral adaptativo por IP; HTTP 429 + `Retry-After`; alertas de fallos.
- **Endurecimiento:** validación en servidor (`@Valid`, Bean Validation), codificación de salida, errores genéricos (`@ControllerAdvice`, sin stack traces), mapeo 400/401/403/404/409/429/5xx, logs JSON con correlationId, denegar por defecto, comprobar la propiedad del recurso.
- **MongoDB:** sin `$where`; claves que empiecen por `$` o contengan `.` rechazadas en las entradas de usuario; consultas tipadas.
- **Multimedia:** límite de tamaño, detección del tipo por magic numbers + lista blanca, rechazo de tipos ambiguos, sandbox (opcionales: antivirus, cabeceras de descarga, URLs firmadas).
- **Cumplimiento:** secretos fuera del repositorio (`.env`, variables de entorno, sin claves en `application.properties` versionado), logs de auditoría, RGPD, enmascaramiento de datos en los logs.

## Paso 3 – Clasificar

- **Sí:** todos los criterios del requisito tienen evidencia en el código o en un documento aportado.
- **Parcial:** hay implementación, pero falta algún criterio, solo se aplica a algunos roles o endpoints, o falta la prueba documental.
- **No:** no hay implementación.
- **Verificación manual:** depende de algo externo al código.

Si el código contradice el requisito (p. ej. un secreto en claro en el repositorio), señálalo como **hallazgo** aparte, con su severidad.

## Paso 4 – Salida

Crea `auditoria/resultado-auditoria.md` con:

1. Una tabla resumen: `RQ | Requisito | Estado | Evidencia principal (ruta)`.
2. Una sección por requisito:
   - **Estado**
   - **Cómo se implementa:** 3–6 frases técnicas basadas solo en lo que has visto.
   - **Evidencia:** `ruta:líneas` + bloque de código (máx. ~30 líneas, recortado con `// ...`).
   - **Qué falta / mejoras**, si es Parcial o No.
3. Una lista de hallazgos de seguridad detectados durante la revisión.
4. Una lista de comprobaciones manuales pendientes, con la captura o el documento que el equipo debe aportar.

Al terminar, resume en el chat cuántos requisitos quedan en Sí/Parcial/No y los 3 problemas más graves.
