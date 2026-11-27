---
name: redaccion-memoria-seguridad
description: Redacta en Word la memoria extendida de SSS P1 a partir de la auditoría, el informe RQ-19 y los datos de Sonar/cobertura, con plantilla fija por requisito.
---

# Redacción de la memoria de seguridad (SSS – P1)

## Entradas

- `auditoria/resultado-auditoria.md` (skill auditoria-requisitos-seguridad). Si no existe, recomienda ejecutarla primero.
- `auditoria/informe-rq19.md` (skill informe-analisis-ia), con las decisiones ya rellenadas por el equipo.
- Datos de Sonar (Quality Gate, bugs, vulnerabilidades, hotspots), cobertura de JaCoCo y capturas que aporte el equipo.
- Datos del grupo (asignatura, integrantes, fecha). Si faltan, deja `[COMPLETAR]`.

## Estructura de la memoria

1. Portada (asignatura, práctica, título del proyecto, integrantes, fecha)
2. Índice
3. Introducción: descripción del sistema y alcance
4. Arquitectura y tecnologías (stack, capas, despliegue)
5. Metodología: proceso seguido, herramientas (Sonar, SonarLint, herramienta de IA del RQ-19) y **uso de IA y revisión humana** (qué skills se usaron y cómo se validó cada resultado)
6. Implementación de los requisitos: una sección por RQ-01 … RQ-19
7. Análisis de código asistido por IA (RQ-19): tabla completa y conclusiones
8. Calidad: Sonar y cobertura (métricas, Quality Gate, capturas)
9. Resumen de cumplimiento (tabla Sí/Parcial/No)
10. Conclusiones, limitaciones y trabajo futuro
11. Anexos (configuraciones, informes completos)

## Plantilla obligatoria de cada requisito

```
RQ-XX – <Título>
Enunciado: <texto del requisito>
Estado: Sí / Parcial / No
Solución adoptada: explicación detallada (qué, cómo, por qué esta opción y qué ataque mitiga)
Fragmento de código: <ruta:líneas> + código
Evidencia adicional: captura, test o configuración
Limitaciones / mejoras: solo si es Parcial o No
```

El enunciado exige, de cada requisito, una explicación detallada y un fragmento de código: no omitas ninguno de los dos.

## Estilo

- Español académico, en primera persona del plural o impersonal ("se ha implementado", "hemos optado"), con frases claras y sin relleno.
- Términos técnicos correctos (Argon2id, session fixation, CSRF, SCRAM-SHA-256…), explicados la primera vez que aparecen.
- Justifica las decisiones con referencia a las aclaraciones del enunciado, a CCN-CERT BP/28 o a OWASP cuando proceda.
- **No inventes** implementaciones, métricas ni resultados: todo sale de las entradas. Lo que falte se marca como `[COMPLETAR: …]`, de forma visible.
- No maquilles los requisitos Parciales o No: explícalos con honestidad y propón mejoras.

## Generación del Word

- Usa la skill de Word/docx para crear `memoria/Memoria_SSS_P1.docx`.
- Tamaño A4, fuente Arial 11, títulos numerados con estilos de Título 1/2 (para que el índice funcione), pie con número de página.
- Código en fuente monoespaciada (Consolas 9), en un cuadro con fondo gris claro y con la ruta del fichero como pie.
- Tablas con cabecera de color y bordes finos; capturas con pie de figura numerado.
- Construye por partes (primero la estructura, después las secciones) y, al terminar, convierte a PDF y revisa visualmente unas páginas.

Al final, informa al usuario de cuántos `[COMPLETAR]` quedan y dónde están.
