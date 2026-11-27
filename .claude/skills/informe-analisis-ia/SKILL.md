---
name: informe-analisis-ia
description: Convierte los resultados de CodeQL, Semgrep o Snyk (SARIF/JSON) en la tabla de vulnerabilidades del RQ-19, dejando la decisión del equipo para revisión humana.
---

# Informe de análisis de código asistido por IA (RQ-19)

El RQ-19 exige documentar, de las vulnerabilidades principales: **fecha del análisis, vulnerabilidad, severidad, propuesta de corrección y decisión adoptada por el equipo**. Además, **las recomendaciones de la IA no pueden aceptarse automáticamente**. Esta skill prepara la tabla, pero **nunca rellena la decisión del equipo**.

## Entrada

- Ficheros SARIF (CodeQL, Semgrep `--sarif`, Snyk `--sarif`) o JSON de Snyk/Semgrep.
- Si no hay ficheros, pide al usuario que los exporte. Ejemplos: descarga del artefacto del workflow de CodeQL, `semgrep scan --config auto --sarif -o semgrep.sarif`, `snyk code test --sarif-file-output=snyk.sarif`.
- Opcional: las sugerencias de Copilot Autofix (texto del PR o de la alerta).

## Paso 1 – Extraer hallazgos

Para SARIF, en Python:
- `runs[].tool.driver.name` → herramienta
- `runs[].tool.driver.rules[]` → `id`, `shortDescription`, `properties.tags` (CWE), `properties["security-severity"]`
- `runs[].results[]` → `ruleId`, `level`, `message.text`, `locations[0].physicalLocation.artifactLocation.uri` y `region.startLine`
- Fecha: `runs[].invocations[].startTimeUtc` si existe; si no, la fecha de modificación del fichero, y avisa de ello.

Severidad: usa `security-severity` (≥9 Crítica, 7–8.9 Alta, 4–6.9 Media, <4 Baja); si no existe, mapea `level` (error→Alta, warning→Media, note→Baja).

## Paso 2 – Seleccionar las principales

Incluye todas las Críticas y Altas, y las Medias que afecten a autenticación, sesión, inyección, datos sensibles o secretos. Agrupa las repeticiones de la misma regla en una sola fila con el número de ocurrencias.

## Paso 3 – Proponer corrección

Para cada fila, abre el fichero y la línea, entiende el contexto y redacta una **propuesta de corrección** concreta (qué cambiar y por qué). Si hay sugerencia de Autofix, resúmela y di si te parece correcta o qué riesgos tiene. Comprueba si es un falso positivo probable y explica el motivo.

## Paso 4 – Salida

Crea `auditoria/informe-rq19.md` con:

1. Herramienta(s), versión, cómo está integrada en el flujo (workflow, pre-commit, IDE) y fecha de cada análisis.
2. Una tabla:

| ID | Fecha | Herramienta | Vulnerabilidad (CWE) | Ubicación | Severidad | Propuesta de corrección | Decisión del equipo | Justificación | Estado |
|---|---|---|---|---|---|---|---|---|---|

   - **Decisión del equipo** = `[PENDIENTE – decidir: Corregir / Falso positivo / Riesgo aceptado]`
   - **Justificación** = `[PENDIENTE]`
   - **Estado** = `Abierta`, salvo que el usuario indique otra cosa.
3. Para cada vulnerabilidad, un bloque con el código afectado y la propuesta.
4. Un apartado de resultados: totales por severidad, y comparación antes/después si hay varios análisis.

Si el usuario quiere la tabla en Excel, genérala también como `.xlsx`.

## Reglas

- No apliques cambios en el código salvo petición expresa; y si lo haces, uno por uno y explicando el diff, para que el equipo lo revise y lo entienda.
- Recuerda al usuario que la decisión y la justificación las debe escribir el equipo tras revisar cada caso.
