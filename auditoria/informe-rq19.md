# Informe de análisis de código asistido por IA (RQ-19) — ESIBuy

> **Importante:** la columna «Decisión del equipo», su justificación y el estado final las debe escribir el equipo tras revisar cada caso. Las propuestas de corrección son recomendaciones de la herramienta y del análisis, no se aplican automáticamente. No se ha modificado ningún fichero de código.

## 1. Herramienta y análisis realizados

| Dato | Backend | Frontend |
|---|---|---|
| Herramienta | Semgrep OSS 1.184.0 | Semgrep OSS 1.184.0 |
| Reglas cargadas | 1074 (`--config auto`, registro de Semgrep) | 1074 (`--config auto`) |
| Fichero de resultados | `semgrep.sarif` (raíz del repositorio) | `Equipo4ProyectoFrontend/semgrep.sarif` |
| Alcance | `esibuy-backend/src` | `src` (Angular) |
| Fecha del análisis | 16/11/2026, 20:42 | 16/11/2026, 21:06 |
| Resultados | 1 | 1 |
| Integración en el flujo | Ejecución manual desde la línea de comandos (se ejecutó con Docker). **No está integrada** en el pipeline de Azure ni en ningún workflow | Igual |

**Aviso sobre las fechas:** el SARIF no trae `startTimeUtc`, así que la fecha es la de modificación del fichero.

**Limitación del análisis del frontend:** Semgrep no pudo analizar `src/app/app.html` (línea 26, `Syntax error ... > 0) {`), porque no entiende la sintaxis de control de flujo de Angular (`@if`). Esa plantilla queda sin revisar por la herramienta.

**Pendiente para cumplir el RQ-19 «integrada en el flujo de desarrollo»:** añadir Semgrep al pipeline (o a un hook de pre-commit) y guardar el SARIF como artefacto.

## 2. Tabla de vulnerabilidades

| ID | Fecha | Herramienta | Vulnerabilidad (CWE) | Ubicación | Severidad | Propuesta de corrección | Decisión del equipo | Justificación | Estado |
|---|---|---|---|---|---|---|---|---|---|
| IA-01 | 16/11/2026 | Semgrep 1.184.0 | Uso de hash débil SHA-1 (CWE-328) · `java.lang.security.audit.crypto.use-of-sha1` | `esibuy-backend/src/main/java/com/esibuy/esibuy_backend/servicio/ClientePwnedPasswords.java:68` | Media (nivel `warning`; precisión de la regla muy alta) | Probable falso positivo: SHA-1 lo impone el protocolo de Pwned Passwords. Mantener, con la justificación documentada, y valorar usar `// nosemgrep: java.lang.security.audit.crypto.use-of-sha1` además del `NOSONAR`. No sustituirlo por SHA-256: la API dejaría de funcionar | [PENDIENTE – decidir: Corregir / Falso positivo / Riesgo aceptado] | [PENDIENTE] | Abierta |
| IA-02 | 16/11/2026 | Semgrep 1.184.0 | Credencial codificada (CWE-798) · `generic.secrets.security.detected-generic-secret` | `Equipo4ProyectoFrontend/src/app/registro/registro.spec.ts:15` | Alta (nivel `error`; confianza **baja**) | Probable falso positivo: es un dato de prueba de un test. Si se quiere eliminar el aviso, no incluir el secreto TOTP en la URI de ejemplo o marcar la línea con `// nosemgrep` | [PENDIENTE – decidir: Corregir / Falso positivo / Riesgo aceptado] | [PENDIENTE] | Abierta |

La severidad se ha deducido del nivel por defecto de cada regla, porque el SARIF no incluye `security-severity`: `warning` → Media y `error` → Alta.

## 3. Detalle de cada hallazgo

### IA-01 — SHA-1 en `ClientePwnedPasswords` (CWE-328)

**Qué dice la herramienta:** «Detected SHA1 hash algorithm which is considered insecure. SHA1 is not collision resistant and is therefore not suitable as a cryptographic signature.»

**Código afectado** (`ClientePwnedPasswords.java:65-73`):

```java
private static String sha1EnMayusculas(String contrasena) {
    try {
        // SHA-1 lo impone el protocolo de Pwned Passwords; no se usa para guardar contrasenas (eso es Argon2id)
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1"); // NOSONAR
        return HexFormat.of().withUpperCase().formatHex(sha1.digest(contrasena.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("La JVM no ofrece SHA-1", e);
    }
}
```

**Análisis:**
- El SHA-1 se usa solo para consultar el servicio Pwned Passwords con k-anonimato: se envían los 5 primeros caracteres del hash y el resto se compara en el servidor (`estaFiltrada`, líneas 50-57). La API pública de HIBP solo admite SHA-1 (o NTLM), así que no se puede cambiar de algoritmo.
- No se usa para guardar contraseñas: eso lo hace Argon2id con pepper (`ConfiguracionContrasena.java`). Tampoco se usa para firmas ni integridad.
- Riesgo residual: el hash de 40 caracteres de la contraseña existe en memoria durante la comprobación, y el prefijo de 5 caracteres sale hacia un servicio externo.

**Propuesta:** tratarlo como falso positivo justificado. Dejar constancia en el código de por qué (ya está el comentario) y añadir la supresión específica de Semgrep para que el análisis siguiente no lo vuelva a marcar. **No aplicar** la sugerencia de la herramienta («usar SHA256 o SHA512»), porque rompería la comprobación de contraseñas filtradas.

### IA-02 — Secreto genérico en `registro.spec.ts` (CWE-798)

**Qué dice la herramienta:** «Generic Secret detected» (confianza baja).

**Código afectado** (`registro.spec.ts:14-17`):

```ts
const ACTIVACION = {
  uri: 'otpauth://totp/ESIBuy:ana.garcia%40gmail.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=ESIBuy',
  secreto: 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ',
};
```

**Análisis:**
- Es un fichero de pruebas (`.spec.ts`). El valor `GEZDGNBVGY3TQOJQ...` es el Base32 de `1234567890` repetido, un dato de ejemplo evidente y sin uso real. No es una credencial.
- La regla se activa por el parámetro `secret=` de la URI `otpauth://`.
- El contenido de las pruebas no se despliega: Sonar del frontend excluye los `*.spec.ts` (`sonar.exclusions`).

**Propuesta:** probable falso positivo. Si se quiere dejar el análisis sin avisos, marcar la línea con `// nosemgrep: generic.secrets.security.detected-generic-secret` indicando que es un valor de prueba. Alternativa: construir la URI en el test a partir de la constante `secreto`, para que el valor aparezca una sola vez.

## 4. Resultados

| Severidad | Backend | Frontend | Total |
|---|---|---|---|
| Crítica | 0 | 0 | 0 |
| Alta | 0 | 1 (IA-02) | 1 |
| Media | 1 (IA-01) | 0 | 1 |
| Baja | 0 | 0 | 0 |
| **Total** | **1** | **1** | **2** |

- Los dos hallazgos son, a primera vista, falsos positivos: uno es un algoritmo impuesto por un protocolo externo y el otro es un dato de prueba.
- No ha aparecido ninguna vulnerabilidad real de inyección, autenticación, sesión o gestión de datos sensibles con el conjunto de reglas `--config auto`.
- No hay un análisis anterior, así que **no hay comparación antes/después**.

## 5. Limitaciones y pasos siguientes

1. **Cobertura de las reglas:** `--config auto` aplica reglas genéricas de Semgrep OSS. Para Java y Spring conviene repetir el análisis con `p/java`, `p/spring` (si está disponible), `p/owasp-top-ten` y `p/secrets`, y con `p/typescript` para el frontend, y guardar los resultados.
2. **Plantillas de Angular:** `app.html` no se analizó por el error de sintaxis (ver sección 1).
3. **Integración en el flujo:** añadir una etapa de Semgrep al `azure-pipelines.yml` de cada repositorio y publicar el SARIF como artefacto.
4. **Decisiones del equipo:** completar «Decisión del equipo», «Justificación» y «Estado» de IA-01 e IA-02 tras revisar el código. El RQ-19 exige que ninguna recomendación se acepte automáticamente.
5. **Segunda herramienta (opcional):** CodeQL con Copilot Autofix o Snyk Code darían una segunda opinión, sobre todo para flujos de datos (inyección, XSS).
