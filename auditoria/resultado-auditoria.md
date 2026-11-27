# Auditoría de requisitos de seguridad — ESIBuy (SSS · P1)

- **Fecha:** 16/11/2026
- **Alcance:** backend `esibuy-backend` (Spring Boot 4, Java 21, MongoDB), frontend `Equipo4ProyectoFrontend` (Angular 21) y configuración (`application*.properties`, `pom.xml`, `azure-pipelines.yml`, `sonar-project.properties`).
- **Método:** lectura y búsqueda en el código. No se ha ejecutado la aplicación ni se ha modificado ningún fichero de código. No se ha abierto ningún `.env`.
- **Abreviaturas de rutas:**
  - `B/` = `esibuy-backend/src/main/java/com/esibuy/esibuy_backend/`
  - `R/` = `esibuy-backend/src/main/resources/`
  - `F/` = repositorio `Equipo4ProyectoFrontend/`
- **Estados:** Sí · Parcial · No · Verificación manual (depende de algo externo al código).
- Los valores de claves y tokens que aparecen en el repositorio se muestran como `<REDACTADO>`.

## 1. Tabla resumen

| RQ | Requisito | Estado | Evidencia principal |
|---|---|---|---|
| 01 | Identificación con usuario y contraseña (RBAC) | **Sí** | `B/seguridad/ConfiguracionSeguridad.java:51-69` |
| 02 | Registro, listas, valoraciones, perfil y suscripción | **Sí** | `B/controlador/ControladorListas.java:29-30`, `ControladorPedido.java:73-79` |
| 03 | Gestión de contenidos, estadísticas y valoraciones | **Parcial** | `B/controlador/ControladorProducto.java:33`, `ControladorProductoAdmin.java:18` |
| 04 | Gestión de usuarios solo para administrador | **Sí** | `B/controlador/ControladorGestionUsuarios.java:19-65` |
| 05 | Política de contraseñas segura | **Parcial** | `B/servicio/ValidadorContrasena.java:57-64, 108-115` |
| 06 | Registro seguro y recuperación de contraseñas | **Sí** | `B/servicio/ServicioRegistro.java:160-172`, `ServicioTokensRecuperacion.java:87-100` |
| 07 | Control y seguridad de sesión | **Sí** | `R/application.properties:26-30`, `B/seguridad/PoliticaSesion.java:22-39` |
| 08 | Accesible desde cualquier navegador | **Verificación manual** | `F/tsconfig.json:15` |
| 09 | Conexión segura HTTPS | **Parcial** | `B/seguridad/ConfiguracionSeguridad.java:46-47` |
| 10 | Protección de información sensible en BBDD | **Parcial** | `B/servicio/CifradorSecretos.java:30-34`, `B/modelo/PerfilUsuario.java:17-22` |
| 11 | 0 bloqueantes y críticos | **Verificación manual** | panel de Sonar |
| 12 | AGR (ISO 27001:2022) | **Verificación manual** | documento externo |
| 13 | Cobertura > 60 % y sin código muerto | **Parcial** | `esibuy-backend/pom.xml:178-191` |
| 14 | Uso de Sonar y SonarLint | **Sí** | `esibuy-backend/azure-pipelines.yml:110-133`, `.sonarlint/connectedMode.json` |
| 15 | Principios CCN-CERT BP/28 | **Parcial** | varias (ver sección) |
| 16 | 2FA y 3FA | **Parcial** | `B/servicio/GeneradorTotp.java:26-36, 97-107` |
| 17 | Medidas de seguridad a nivel de BBDD | **Parcial** | `R/application.properties:4` |
| 18 | Recuperación segura de contraseña | **Sí** | `B/servicio/GeneradorTokens.java:36-52` |
| 19 | Análisis de código asistido por IA | **No** | no se ha encontrado nada |

**Totales:** Sí 7 · Parcial 8 · No 1 · Verificación manual 3.

---

## 2. Detalle por requisito

### RQ-01 — Identificación con usuario y contraseña mediante RBAC

**Estado: Sí**

**Cómo se implementa.** El login recibe email y contraseña en `POST /api/auth/login`. La contraseña se comprueba con Argon2id (con pepper) y, si el correo no existe, contra un hash ficticio para que el coste sea el mismo. Spring Security autoriza por rol (`CLIENTE`, `PREMIUM`, `VENDEDOR`, `ADMIN`), con jerarquía `PREMIUM ⇒ CLIENTE` y denegación por defecto. Además está activa la seguridad a nivel de método (`@PreAuthorize`). Solo entra una cuenta en estado `ACTIVO` y con un único rol válido.

**Evidencia.** `B/seguridad/ConfiguracionSeguridad.java:51-69`

```java
.authorizeHttpRequests(autorizacion -> autorizacion
        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
        // Operaciones del vendedor (su catalogo propio): solo rol VENDEDOR, el resto recibe 403
        .requestMatchers("/api/vendedor/**").hasRole(Rol.VENDEDOR.name())
        // ...
        .requestMatchers("/api/cliente/**").hasAnyRole(Rol.CLIENTE.name(), Rol.PREMIUM.name())
        // ...
        .requestMatchers("/api/admin/**").hasRole(Rol.ADMIN.name())
        .anyRequest().authenticated());
```

`B/modelo/Rol.java:4-5`

```java
public enum Rol {
    CLIENTE, PREMIUM, VENDEDOR, ADMIN;
```

`B/servicio/ServicioAutenticacion.java:167-169`

```java
Usuario usuario = repositorioUsuario.buscarPorEmail(email).orElse(null);
String hash = usuario != null ? usuario.getPasswordHash() : hashFicticio();
return new Comprobacion(usuario, codificadorContrasena.matches(contrasena, hash));
```

---

### RQ-02 — Registro, gestión de listas, valoraciones, perfil y suscripción

**Estado: Sí**

**Cómo se implementa.** El registro está en `POST /api/auth/registro` y `POST /api/auth/registro/2fa`. Las listas de productos (y favoritos) cuelgan de `/api/cliente/listas`, restringidas a `CLIENTE` y `PREMIUM`. El cliente valora un producto de un pedido finalizado, una sola vez. El perfil se consulta, modifica y da de baja en `/api/perfil`, y la suscripción Premium se contrata y cancela en `/api/cliente/suscripcion/premium`.

**Evidencia.** `B/controlador/ControladorListas.java:29-30`

```java
@RequestMapping("/api/cliente/listas")
@PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
```

`B/controlador/ControladorPedido.java:72-79`

```java
// HU-30 Valorar un producto del pedido: {"puntos": 4}. Solo en pedidos finalizados y una sola vez
@PostMapping(RUTA_ID + "/productos/{idProducto}/valoracion")
@PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
public ResponseEntity<Void> valorar(@PathVariable String id, @PathVariable String idProducto,
                                    @RequestBody ValoracionDTO valoracion, Authentication autenticacion) {
    servicioValoracion.valorar(autenticacion.getName(), id, idProducto, valoracion.puntos());
    return ResponseEntity.noContent().build();
}
```

Otras rutas: `B/controlador/ControladorAuth.java:63` (registro), `ControladorRegistroDosFactores.java:45`, `ControladorPerfil.java:46-64`, `ControladorSuscripcion.java:28-56`, `ControladorFavoritos.java:26-62`.

---

### RQ-03 — Gestión de contenidos, estadísticas y valoraciones

**Estado: Parcial**

**Cómo se implementa.** El vendedor gestiona su catálogo en `/api/vendedor/productos` (detalle, estado, stock, modificación y baja). El administrador consulta productos en `/api/admin/productos` y gestiona categorías en `/api/admin/categorias`. Las valoraciones se consultan agregadas: media y número por producto (`ProductoDetalleDTO`), y el administrador las calcula con `IntSummaryStatistics` en `ServicioProductoAdmin`.

**Evidencia.** `B/controlador/ControladorProducto.java:33, 60-111` (`@RequestMapping("/api/vendedor/productos")` y sus `@GetMapping`, `@PatchMapping`, `@PutMapping`, `@DeleteMapping`); `B/controlador/ControladorProductoAdmin.java:18`; `B/controlador/ControladorCategoria.java:23-57`.

`B/servicio/ServicioProductoAdmin.java:104`

```java
IntSummaryStatistics puntuacion = repositorioValoracion.buscarPorProducto(new ObjectId(id)).stream()
```

**Qué falta.** No hay un módulo de **estadísticas de uso**. Se ha buscado `estadistic`, `statistic` y `metric` en backend y frontend: la única coincidencia es `IntSummaryStatistics` (media de valoraciones). Tampoco se listan las valoraciones individuales, solo la media y el recuento.

---

### RQ-04 — Módulo de gestión de usuarios solo para administrador

**Estado: Sí**

**Cómo se implementa.** Todo el controlador está bajo `/api/admin/usuarios`, protegido dos veces: por la ruta `/api/admin/**` en la cadena de filtros y por `@PreAuthorize("hasRole('ADMIN')")` en la clase. Permite crear solo administradores, listar con filtros, modificar, bloquear, desbloquear y eliminar.

**Evidencia.** `B/controlador/ControladorGestionUsuarios.java:19-65`

```java
@RequestMapping("/api/admin/usuarios")
@PreAuthorize("hasRole('ADMIN')") // to strict access to admin users only
public class ControladorGestionUsuarios {
    // ...
    @PostMapping(path = "/administradores", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaRegistroDTO> crearAdministrador(@RequestBody SolicitudAltaAdministradorDTO solicitud) {
    // ...
    @GetMapping
    public PaginaDTO<UsuarioDTO> listar(@RequestParam(defaultValue = "0") int pagina,
    // ...
    @PutMapping(path = RUTA_ID, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PatchMapping(RUTA_ID + "/bloquear")
    @PatchMapping(RUTA_ID + "/desbloquear")
    @DeleteMapping(RUTA_ID)
```

---

### RQ-05 — Política de contraseñas segura

**Estado: Parcial**

**Cómo se implementa.** `ValidadorContrasena` exige entre 12 y 128 caracteres, con mayúscula, minúscula, número y carácter especial. Rechaza las contraseñas comunes y las filtradas (diccionario local y servicio Pwned Passwords con k-anonimato) y las que contienen datos personales o el nombre de la aplicación. Se aplica en el registro, en el alta de administradores y en el restablecimiento.

**Evidencia.** `B/util/Constantes.java:7-8`

```java
public static final int LONGITUD_MINIMA = 12;
public static final int LONGITUD_MAXIMA = 128;
```

`B/servicio/ValidadorContrasena.java:108-115`

```java
private void validarDiccionarios(String contrasena, Set<CodigoError> errores) {
    if (diccionario.esComun(contrasena.toLowerCase(Locale.ROOT))) {
        errores.add(CodigoError.CONTRASENA_COMUN);
    }
    if (diccionario.estaFiltrada(contrasena)) {
        errores.add(CodigoError.CONTRASENA_FILTRADA);
    }
}
```

`B/servicio/ClientePwnedPasswords.java:52-55`

```java
String prefijo = hash.substring(0, LONGITUD_PREFIJO);
String sufijo = hash.substring(LONGITUD_PREFIJO);
// ...
String respuesta = cliente.get().uri("/range/{prefijo}", prefijo).retrieve().body(String.class);
```

**Qué falta.** No hay **caducidad a 30 días** ni **historial de las últimas 5 contraseñas**. Se ha buscado `passwordHistory`, `historialContrasen`, `contrasenasAnteriores`, `contrasenaCaducada` y `expir` sin resultados. Existe el campo `contrasenaCambiadaEn` (`B/modelo/Usuario.java:27-28`), pero solo se usa para invalidar sesiones.

---

### RQ-06 — Registro de usuario seguro y recuperación de contraseñas

**Estado: Sí**

**Cómo se implementa.** El registro valida los datos en servidor, comprueba el dominio del correo y exige un código TOTP válido de un secreto pendiente en la sesión, de modo que la cuenta nace con el 2FA configurado. La respuesta al registro indica que un administrador debe activar la cuenta. El registro tiene límite de 5 peticiones por minuto e IP. La recuperación se detalla en RQ-18.

**Evidencia.** `B/servicio/ServicioRegistro.java:160-172`

```java
private static void exigirCodigo(String codigo, ErroresValidacion errores) {
    if (codigo == null || codigo.isBlank()) {
        errores.anadir(CAMPO_CODIGO_DOS_FACTORES, CodigoError.OBLIGATORIO);
    }
}

private void exigirPendienteVigente(String email, PendienteRegistroDosFactores pendiente, ContextoPeticion contexto) {
    if (pendiente == null || !pendiente.esDe(email)) {
        auditoria.registrarRegistroDosFactoresFallido(contexto, email, MotivoFalloDosFactores.ACTIVACION_NO_VIGENTE);
        throw new DatosRegistroInvalidosException(
                Map.of(CAMPO_CODIGO_DOS_FACTORES, Set.of(CodigoError.ACTIVACION_NO_VIGENTE)));
    }
}
```

`R/application.properties:9-10`

```properties
esibuy.limite-registro.max-peticiones=5
esibuy.limite-registro.ventana=PT1M
```

---

### RQ-07 — Control y seguridad de sesión

**Estado: Sí**

**Cómo se implementa.** La cookie de sesión es `HttpOnly`, `Secure`, `SameSite=Lax`, limitada a `/api`, y la sesión solo se sigue por cookie. Al iniciar sesión se invalida la sesión anterior y se crea una nueva (anti fijación de sesión), y al cambiar de rol o perfil se cambia el identificador con `changeSessionId()`. Hay caducidad por inactividad y absoluta según el rol, comprobada en cada petición. Un filtro invalida las sesiones anteriores a un cambio de contraseña. CSRF está activo.

**Evidencia.** `R/application.properties:26-30`

```properties
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.same-site=lax
server.servlet.session.cookie.path=/api
server.servlet.session.tracking-modes=cookie
```

`B/seguridad/PoliticaSesion.java:22-25`

```java
private static final Duration INACTIVIDAD_CLIENTES = Duration.ofMinutes(20);
private static final Duration INACTIVIDAD_PERSONAL = Duration.ofMinutes(15);
private static final Duration ABSOLUTA_CLIENTES = Duration.ofHours(8);
private static final Duration ABSOLUTA_PERSONAL = Duration.ofHours(6);
```

`B/seguridad/EstablecedorSesion.java:41-46, 56, 68`

```java
public void establecer(HttpServletRequest peticion, ResultadoAutenticacion resultado) {
    HttpSession sesionAnterior = peticion.getSession(false);
    if (sesionAnterior != null) {
        sesionAnterior.invalidate();
    }
    HttpSession sesion = peticion.getSession(true);
    // ...
    sesion.setMaxInactiveInterval((int) politica.inactividadMaxima(rol).toSeconds());
// ...
    peticion.changeSessionId(); // Nuevo id, mismos atributos (token CSRF e inicio de sesion incluidos)
```

`B/configuracion/FiltroCaducidadSesion.java:39-41`

```java
HttpSession sesion = request.getSession(false);
if (sesion != null && estaAutenticada(sesion) && politica.haCaducado(sesion, reloj.instant())) {
    sesion.invalidate();
```

---

### RQ-08 — Accesible desde cualquier navegador

**Estado: Verificación manual**

**Cómo se implementa.** El frontend es una aplicación Angular que compila a HTML, CSS y JavaScript estándar (`"target": "ES2022"`, `F/tsconfig.json:15`). No se han visto plugins ni APIs propietarias de un navegador.

**Qué falta.** El código no demuestra la compatibilidad. Hace falta una prueba manual documentada (capturas en Chrome, Firefox, Edge y Safari). No hay fichero `.browserslistrc` ni clave `browserslist` en `package.json`.

---

### RQ-09 — Conexión segura HTTPS

**Estado: Parcial** (el despliegue requiere verificación manual)

**Cómo se implementa.** El backend envía HSTS de un año con subdominios, marca la cookie de sesión como `Secure` y, en el perfil `prod`, respeta `X-Forwarded-Proto` cuando la petición llega de un proxy de confianza. El backend no termina TLS: no hay `server.ssl.*` en ningún `application*.properties`.

**Evidencia.** `B/seguridad/ConfiguracionSeguridad.java:35-36, 46-47`

```java
/** Un ano: el HSTS que recomienda CCN-CERT BP/28 para que el navegador solo use HTTPS. */
private static final long SEGUNDOS_HSTS = 31_536_000L;
// ...
.headers(cabeceras -> cabeceras.httpStrictTransportSecurity(
        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(SEGUNDOS_HSTS)))
```

`R/application-prod.properties:20, 22`

```properties
server.forward-headers-strategy=native
server.tomcat.remoteip.protocol-header=x-forwarded-proto
```

**Qué falta.** El certificado y la terminación TLS dependen del proxy o de la plataforma de despliegue. Aportar captura del candado y del certificado en la URL desplegada. El frontend tiene la URL del backend fija a `http://localhost:8080` (`F/src/app/core/api.ts:8`), que habrá que cambiar a HTTPS al desplegar.

---

### RQ-10 — Protección de información sensible en BBDD

**Estado: Parcial**

**Cómo se implementa.** Las contraseñas se guardan con Argon2id tras aplicar un pepper con HMAC-SHA256. El secreto TOTP se cifra con AES-256-GCM con IV aleatorio y versión de clave. Los códigos de recuperación del 2FA se guardan como HMAC-SHA256 y los tokens de recuperación como SHA-256. De la tarjeta solo se guardan los últimos dígitos y la caducidad.

**Evidencia.** `B/seguridad/ConfiguracionContrasena.java:21-30`

```java
static final int PARALELISMO = 1;
static final int MEMORIA_KIB = 64 * 1024;
static final int ITERACIONES = 3;

/** Sin pepper la aplicacion no arranca: el constructor del codificador lanza IllegalStateException. */
@Bean
public PasswordEncoder codificadorContrasena(@Value("${esibuy.seguridad.pepper:}") String pepper) {
    PasswordEncoder argon2id = new Argon2PasswordEncoder(
            LONGITUD_SALT_BYTES, LONGITUD_HASH_BYTES, PARALELISMO, MEMORIA_KIB, ITERACIONES);
    return new CodificadorContrasenaConPepper(pepper, argon2id);
```

`B/servicio/CifradorSecretos.java:30-34, 76`

```java
private static final String TRANSFORMACION = "AES/GCM/NoPadding";
private static final String ALGORITMO_CLAVE = "AES";
private static final int LONGITUD_CLAVE_BYTES = 32;
private static final int LONGITUD_IV_BYTES = 12;
private static final int LONGITUD_ETIQUETA_BITS = 128;
// ...
cipher.init(Cipher.ENCRYPT_MODE, claves.get(versionActiva), new GCMParameterSpec(LONGITUD_ETIQUETA_BITS, iv));
```

**Qué falta.** El DNI y el teléfono se guardan en claro (`B/modelo/PerfilUsuario.java:17-22`):

```java
@Field("dni")
private String dni;
// ...
@Field("telefono")
private String telefono;
```

No se ha encontrado cifrado de campos (`@Encrypted`, CSFLE) para estos datos.

---

### RQ-11 — 0 defectos, bugs y vulnerabilidades bloqueantes y críticos

**Estado: Verificación manual**

**Cómo se implementa.** Sonar se ejecuta en el pipeline (ver RQ-14). En el código hay una supresión justificada: `B/servicio/GeneradorTotp.java:35-36`

```java
// HMAC-SHA1 es el unico algoritmo que usa Google Authenticator; como HMAC no tiene los problemas de SHA-1 a secas
private static final String ALGORITMO_HMAC = "HmacSHA1"; // NOSONAR
```

**Qué falta.** El número de bloqueantes y críticos solo se ve en el panel de Sonar. Aportar captura de los proyectos `2026-g04-backend` y `2026-g04-frontend` tras el último análisis. El pipeline no espera al Quality Gate (`-Dsonar.qualitygate.wait=true` está solo como comentario).

---

### RQ-12 — Análisis y Gestión de Riesgos (ISO 27001:2022)

**Estado: Verificación manual**

No hay ningún documento de AGR en los repositorios. Aportar el informe o la exportación de MARISMA.

---

### RQ-13 — Cobertura de pruebas > 60 % y sin código muerto

**Estado: Parcial**

**Cómo se implementa.** El backend genera el informe de JaCoCo en `mvn verify` (objetivos `prepare-agent` y `report`). El informe local (`esibuy-backend/target/site/jacoco/jacoco.xml`, no versionado) da 21 233 instrucciones cubiertas de 22 666 (**93,7 %**) y 4 308 líneas de 4 579 (**94,1 %**). Hay 197 ficheros Java en `src/test`. El pipeline publica la cobertura con `PublishCodeCoverageResults@2`.

**Evidencia.** `esibuy-backend/pom.xml:178, 184, 191`

```xml
<artifactId>jacoco-maven-plugin</artifactId>
<!-- ... -->
<goal>prepare-agent</goal>
<!-- ... -->
<goal>report</goal>
```

**Qué falta.** El pom no fija un mínimo (no hay regla `check` con el 60 %). El frontend no mide cobertura en el pipeline (`F/azure-pipelines.yml` solo compila y lanza Sonar). El código muerto debe comprobarse en Sonar (verificación manual con captura).

---

### RQ-14 — Uso de Sonar y SonarLint

**Estado: Sí**

**Cómo se implementa.** Ambos repositorios tienen `sonar-project.properties` contra el servidor de la universidad y una etapa `Sonar` en su pipeline de Azure. SonarLint está en modo conectado en los dos.

**Evidencia.** `esibuy-backend/sonar-project.properties:2-3`

```properties
sonar.host.url=http://seralu4.esi.uclm.es:1521/
sonar.projectKey=2026-g04-backend
```

`esibuy-backend/azure-pipelines.yml:110, 132-133`

```yaml
  - stage: Sonar
# ...
          - script: sonar-scanner
            displayName: 'sonar-scanner'
```

`.sonarlint/connectedMode.json`

```json
{
    "sonarQubeUri": "http://seralu4.esi.uclm.es:1521",
    "projectKey": "2026-g04-backend"
}
```

Frontend: `F/sonar-project.properties:2-3` (`2026-g04-frontend`), `F/azure-pipelines.yml:59, 74` y `F/.sonarlint/`.

**Pendiente.** Captura del panel de Sonar y de SonarLint en el IDE para la memoria.

---

### RQ-15 — Principios CCN-CERT BP/28 y buenas prácticas

**Estado: Parcial**

**Cómo se implementa.** Medidas del código relacionadas con los principios de la guía:

| Principio | Medida | Evidencia |
|---|---|---|
| Mínimo privilegio y denegar por defecto | Rutas por rol y `anyRequest().authenticated()` | `B/seguridad/ConfiguracionSeguridad.java:51-69` |
| Defensa en profundidad | Autorización por ruta y por método | `B/controlador/ControladorGestionUsuarios.java:19-20` |
| Comunicaciones seguras | HSTS y cookie `Secure` | `ConfiguracionSeguridad.java:46-47` |
| Protección de credenciales | Argon2id, pepper y AES-256-GCM | `B/seguridad/ConfiguracionContrasena.java:21-30` |
| Gestión de errores | Mensaje genérico en los 500 | `B/controlador/ManejadorExcepciones.java:276-280` |
| Registro y auditoría | Eventos con correlationId y datos enmascarados | `B/servicio/AuditoriaSeguridad.java:278-283` |
| Validación de entradas | Patrones y rechazo de propiedades desconocidas | `B/util/Constantes.java:15-19`, `R/application.properties:7` |

**Qué falta.** No hay un documento que recorra la guía punto por punto. La propia guía pide cosas que aquí están en Parcial o No (HTTPS desplegado, endurecimiento de la BBDD).

---

### RQ-16 — Segundo (2FA) y tercer factor (3FA) de autenticación

**Estado: Parcial**

**Cómo se implementa.** El 2FA es TOTP compatible con Google Authenticator: 6 dígitos, periodo de 30 s, tolerancia de ±1 paso, comparación en tiempo constante y rechazo de códigos ya usados. El secreto se guarda cifrado (RQ-10) y hay 10 códigos de recuperación. El QR lo genera el frontend. El registro de clientes y vendedores exige el 2FA. En el login, si la cuenta tiene 2FA, la contraseña no da sesión hasta el segundo paso.

**Evidencia.** `B/servicio/GeneradorTotp.java:26-28, 97-107`

```java
public static final int DIGITOS = 6;
public static final int PERIODO_SEGUNDOS = 30;
public static final int VENTANA = 1;
// ...
for (long paso = actual - VENTANA; paso <= actual + VENTANA; paso++) {
    if (paso < 0) {
        continue;
    }
    // Se calcula y compara siempre, aunque el paso ya no pueda aceptarse
    boolean igual = MessageDigest.isEqual(introducido, codigo(secreto, paso).getBytes(StandardCharsets.US_ASCII));
    if (igual && paso > ultimoPaso && !encontrado) {
        coincidente = paso;
        encontrado = true;
    }
}
```

`B/controlador/ControladorLogin.java:66-70`

```java
ResultadoAutenticacion resultado = servicioAutenticacion.autenticar(solicitud, contexto);
if (resultado.requiereSegundoFactor()) {
    gestorDesafio.iniciar(peticion, resultado);
    return ResponseEntity.ok(new RespuestaSegundoFactorRequeridoDTO(true, MENSAJE_FALTA_SEGUNDO_FACTOR));
}
```

`F/src/app/seguridad/qr-dos-factores.ts:29-30` (QR con la librería `qrcode`); `R/application.properties:76` (`esibuy.dosfactores.codigos-recuperacion=10`).

**Qué falta.**
- **No hay tercer factor.** Se ha buscado `otp`, `codigo` por correo y `tercer` en el backend: solo aparece TOTP. No existe un código de un solo uso por email en el login.
- **El administrador no tiene 2FA obligatorio.** `crearAdministrador` crea la cuenta sin secreto TOTP (`B/servicio/ServicioGestionUsuarios.java:108-130`, termina en `return crearCuenta(datos, Rol.ADMIN, perfil);`) y el login solo pide el segundo factor si `usuario.tieneDosFactoresActivo()` (`B/servicio/ServicioAutenticacion.java:115`).

---

### RQ-17 — Medidas de seguridad a nivel de BBDD

**Estado: Parcial**

**Cómo se implementa.** El acceso a MongoDB va por repositorios de Spring Data y por `MongoTemplate` con consultas tipadas (`query(where(...))`), sin concatenar texto del usuario. La URI se toma de la variable `MONGODB_URI`. Los datos críticos están cifrados o resumidos (RQ-10).

**Evidencia.** `R/application.properties:4`

```properties
spring.mongodb.uri=${MONGODB_URI:mongodb://localhost:27017/ESIBuy}
```

`B/servicio/ServicioTokensRecuperacion.java:93-97`

```java
TokenRecuperacion consumido = mongo.findAndModify(
        query(where(CAMPO_HASH).is(hash).and(CAMPO_ESTADO).is(EstadoToken.VIGENTE.name())
                .and("caducaEn").gt(ahora)),
        new Update().set(CAMPO_ESTADO, EstadoToken.USADO.name()).set("usadoEn", ahora),
        FindAndModifyOptions.options().returnNew(true), TokenRecuperacion.class);
```

**Qué falta.** En el repositorio no hay evidencia de autenticación SCRAM-SHA-256, de usuarios separados con roles mínimos ni de TLS con Mongo: la URI por defecto no lleva credenciales ni `tls=true`, y no hay `docker-compose` ni scripts de usuarios. Depende de cómo se configure el servidor (verificación manual: aportar la URI de producción sin la contraseña y los usuarios creados).

---

### RQ-18 — Recuperación segura de contraseña

**Estado: Sí**

**Cómo se implementa.** El token son 256 bits de `SecureRandom` en base64url y en la BBDD solo se guarda su SHA-256. Vale 15 minutos y se consume con una operación atómica, de modo que solo se puede usar una vez. Al generar uno nuevo o al cambiar la contraseña se invalidan los demás. Hay límites por cuenta (5 en 24 h, 60 s entre solicitudes) y por IP (10 por hora), y el restablecimiento aplica la política de contraseñas y marca `contrasenaCambiadaEn`, que cierra las sesiones abiertas.

**Evidencia.** `B/servicio/GeneradorTokens.java:36-49`

```java
public String nuevoToken() {
    byte[] bytes = new byte[BYTES_DEL_TOKEN];
    aleatorio.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
}

// SHA-256 del token en hexadecimal minuscula (64 caracteres).
public static String hash(String token) {
    // ...
        byte[] resumen = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(resumen);
```

`B/servicio/ServicioTokensRecuperacion.java:39`

```java
public static final Duration VALIDEZ = Duration.ofMinutes(15);
```

`R/application.properties:49-53`

```properties
esibuy.recuperacion.max-por-cuenta=5
esibuy.recuperacion.ventana-cuenta=PT24H
esibuy.recuperacion.cooldown=PT60S
esibuy.recuperacion.max-por-ip=10
esibuy.recuperacion.ventana-ip=PT1H
```

`B/servicio/ServicioRestablecimiento.java:115, 133`

```java
Set<CodigoError> errores = validadorContrasena.validar(contrasena, datos);
// ...
usuario.cambiarContrasena(codificador.encode(contrasena), reloj.instant());
```

---

### RQ-19 — Análisis de código asistido por IA

**Estado: No**

**Qué se ha buscado.** Workflows en `.github/workflows` de ambos repositorios (no existe esa carpeta; en el backend solo hay `.github/modernize/java-upgrade`, que no es análisis de seguridad), ficheros `*.sarif`, y los nombres `codeql`, `semgrep` y `snyk` en ficheros y pipelines. Los `azure-pipelines.yml` solo compilan, prueban y lanzan Sonar.

**Nota.** En la raíz del repositorio hay un fichero llamado `emgrep scan --config auto --sarif -o semgrep.sarif`. No es un resultado de Semgrep: contiene un listado de ramas de git, fruto de un comando mal escrito. No hay ningún `semgrep.sarif`.

**Qué falta.** Ejecutar una herramienta (CodeQL con Copilot Autofix, Semgrep o Snyk Code), integrarla en el flujo, y documentar por cada vulnerabilidad principal la fecha, la severidad, la propuesta de corrección y la decisión del equipo, más el informe final.

---

## 3. Criterios detallados (aclaraciones)

### 3.1 Autenticación multifactor

| Criterio | Estado | Evidencia o búsqueda |
|---|---|---|
| F1: longitud ≥ 8, lista negra, caducidad 30 días, no reutilizar 5 | Parcial | Longitud y lista negra: `B/servicio/ValidadorContrasena.java:57-64, 108-115`. Caducidad e historial: no encontrados (ver RQ-05) |
| F1: bloqueo progresivo y rate limiting | Sí | `B/servicio/LimitadorIntentosLogin.java:39-46` |
| F2: TOTP con secreto cifrado, QR, ±1 paso y códigos de respaldo | Sí | `B/servicio/GeneradorTotp.java:26-28`, `CifradorSecretos.java:30-34`, `F/src/app/seguridad/qr-dos-factores.ts:29-30` |
| F3: código de un solo uso por email | No | Buscado `otp`, `tercer` y códigos por correo: nada |
| Admin y Creador con 3FA obligatoria | No | Admin sin 2FA: `B/servicio/ServicioGestionUsuarios.java:108-130`. Vendedor con F1+F2, sin F3 |
| Usuario con 3FA opcional e incentivo a F2 | Sí | F2 obligatorio en el registro: `B/servicio/ServicioRegistro.java:160-164` |

`B/servicio/LimitadorIntentosLogin.java:39-46`

```java
private static final long[] SEGUNDOS_DE_BLOQUEO = {30, 120, 240, 300};
private static final Duration RETENCION_CUENTA = Duration.ofHours(1);

private static final Duration VENTANA_INTENTOS = Duration.ofMinutes(5);
private static final int INTENTOS_BASE = 5;
private static final int INTENTOS_ADAPTATIVO = 2;
private static final int USUARIOS_PARA_MODO_ADAPTATIVO = 20;
private static final Duration RETRASO_ADAPTATIVO = Duration.ofSeconds(2);
```

### 3.2 Defensa contra diccionario y credential stuffing

| Criterio | Estado | Evidencia |
|---|---|---|
| Contraseña ≥ 8 (recomendado 12+) | Sí | `B/util/Constantes.java:7` (`LONGITUD_MINIMA = 12`) |
| Bloqueo de contraseñas comunes | Sí | `B/servicio/ValidadorContrasena.java:109-110`, `R/seguridad/contrasenas-comunes.txt` |
| Sin datos personales | Sí | `B/servicio/ValidadorContrasena.java:117` (`contieneDatosPersonales`) |
| 5 intentos / 5 min por IP+usuario | Sí | `LimitadorIntentosLogin.java:42-43` |
| Backoff 30 s → 2 → 4 → 5 min | Sí | `LimitadorIntentosLogin.java:39` |
| Adaptativo por IP (20 usuarios → 2 intentos y retraso) | Sí | `LimitadorIntentosLogin.java:44-46` |
| HTTP 429 con `Retry-After` | Sí | `B/controlador/ManejadorExcepciones.java:120-125` |
| Contraseñas filtradas (diccionario o k-anonimato) | Sí | `B/servicio/ClientePwnedPasswords.java:52-55`, `DiccionarioContrasenasCompuesto.java:42-43` |
| Argon2id (64–256 MB, 2–4 iteraciones, paralelismo 1–2) | Sí | `B/seguridad/ConfiguracionContrasena.java:21-23` |
| Pepper secreto global | Sí | `B/seguridad/CodificadorContrasenaConPepper.java:58-61`, `R/application.properties:8` |
| Comparación en tiempo constante | Sí | `B/servicio/ServicioAutenticacion.java:167-169`, `GeneradorTotp.java:102` |
| Alertas de intentos fallidos | Parcial | Solo avisos `WARN` en el log: `B/servicio/AuditoriaSeguridad.java:155, 163` |

`B/controlador/ManejadorExcepciones.java:120-125`

```java
@ExceptionHandler(LoginBloqueadoTemporalmenteException.class)
public ResponseEntity<Map<String, Object>> loginBloqueado(LoginBloqueadoTemporalmenteException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getSegundosRestantes()))
            .body(Map.of(CLAVE_MENSAJE, MENSAJE_DEMASIADOS_INTENTOS));
}
```

`B/servicio/AuditoriaSeguridad.java:163`

```java
log.warn("ALERTA_SEGURIDAD tipo=BLOQUEO_CUENTA email={} ip={} segundos={}",
```

### 3.3 Gestión y control de sesión

| Criterio | Estado | Evidencia |
|---|---|---|
| Cookies HttpOnly, Secure y SameSite | Sí | `R/application.properties:26-28` |
| Rotación del ID tras el login | Sí | `B/seguridad/EstablecedorSesion.java:42-46` |
| Rotación al cambiar credenciales o privilegios | Sí | `EstablecedorSesion.java:68`, `B/configuracion/FiltroSesionTrasCambioContrasena.java:62-67` |
| Idle timeout 15–30 min | Sí | `B/seguridad/PoliticaSesion.java:22-23`, `EstablecedorSesion.java:56` |
| Absolute timeout 8–12 h | Sí | `PoliticaSesion.java:24-25`, `B/configuracion/FiltroCaducidadSesion.java:39-41` |

### 3.4 Endurecimiento de frontend y backend

| Criterio | Estado | Evidencia o búsqueda |
|---|---|---|
| HTTPS con certificado válido | Verificación manual | HSTS en `ConfiguracionSeguridad.java:46-47`; el certificado es del despliegue |
| Validación de entrada en servidor | Sí | `B/util/Constantes.java:15-19`, `R/application.properties:7, 11`, `B/controlador/LimiteTamanoCuerpoPeticion.java:32` |
| Codificación de salida (anti XSS) | Sí | Angular escapa por defecto; buscado `innerHTML`, `outerHTML`, `bypassSecurityTrust` y `document.write` en `F/src`: sin resultados |
| Rate limiting (/login, /otp/send, API 60 req/min) | Parcial | Login, registro, recuperación, restablecimiento y 2FA sí. No hay `/otp/send` ni límite general por token |
| Errores genéricos, sin trazas | Sí | `B/controlador/ManejadorExcepciones.java:276-280` |
| Mapeo 400/401/403/404/409/429/5xx | Sí | `ManejadorExcepciones.java` (p. ej. 429 en `:122`, 409 en `:273`, 500 en `:279`); 401 en `ConfiguracionSeguridad.java:49-50` |
| Logs JSON con usuario, IP, endpoint, UA, correlationId y causa | Parcial | Texto `clave=valor`, sin endpoint: `B/servicio/AuditoriaSeguridad.java:278-283`. Buscado `logstash` y `json` en `pom.xml` y propiedades: nada |
| RBAC con mínimo privilegio y denegar por defecto | Sí | `ConfiguracionSeguridad.java:51-69` |
| Roles y permisos finos (authorities) | Parcial | Cuatro roles con jerarquía (`ConfiguracionSeguridad.java:94-97`); sin authorities por permiso |
| Comprobación de propiedad del recurso | Sí | `B/repositorio/RepositorioListaProductos.java:23`, `B/servicio/ServicioListas.java:187` |

`B/controlador/ManejadorExcepciones.java:276-280`

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<Map<String, Object>> errorInesperado(Exception ex) {
    log.error("Error inesperado al procesar la peticion", ex);
    return conCorrelationId(HttpStatus.INTERNAL_SERVER_ERROR, MENSAJE_ERROR_INESPERADO);
}
```

`B/servicio/AuditoriaSeguridad.java:278-283`

```java
private static String datosDeLaPeticion(ContextoPeticion contexto, String correo) {
    return "ip=" + EnmascaradorDatosLog.texto(contexto.ip())
            + " userAgent=" + EnmascaradorDatosLog.texto(contexto.userAgent())
            + " correlationId=" + EnmascaradorDatosLog.texto(MDC.get(FiltroCorrelacionId.CLAVE_MDC))
            + " email=" + EnmascaradorDatosLog.correo(correo);
}
```

`B/servicio/ServicioListas.java:187`

```java
return repositorio.buscarPorIdYUsuario(new ObjectId(idLista), new ObjectId(idUsuario))
```

### 3.5 Seguridad específica de MongoDB

| Criterio | Estado | Evidencia o búsqueda |
|---|---|---|
| Autenticación SCRAM-SHA-256 | Verificación manual | La URI por defecto no lleva credenciales (`R/application.properties:4`); depende de `MONGODB_URI` |
| Usuarios separados por tarea | No | Una sola URI; no hay scripts de usuarios ni `docker-compose` |
| TLS 1.2+ entre cliente y Mongo | Verificación manual | Buscado `tls` y `ssl` en propiedades: solo aparece para el correo |
| Sin `$where` ni claves con `$` o `.` | Sí | Buscado `$where`: nada. Consultas tipadas, `fail-on-unknown-properties=true` y `PATRON_TEXTO_SEGURO = "^[^<>{}$]*$"` (`B/util/Constantes.java:17`) |

### 3.6 Subida y entrega de contenidos multimedia

Las fotos de producto se suben como *data URL* en base64 dentro del JSON del producto.

| Criterio | Estado | Evidencia o búsqueda |
|---|---|---|
| Límite de tamaño en servidor y proxy | Parcial | 1 MB de imagen y 2 MB de cuerpo en servidor; no hay proxy en el repositorio |
| Tipo por magic numbers y lista blanca | Parcial | Solo se valida el prefijo que declara el cliente, no los bytes |
| Rechazo de tipos ambiguos | Parcial | Solo se aceptan tres prefijos de imagen, pero no se inspecciona el contenido |
| Transcodificación en sandbox | No | La imagen se guarda tal cual (`ServicioProductoImpl.java:164`) |
| Antivirus (opcional) | No | Sin resultados |
| Cabeceras seguras en descargas (opcional) | No | Las imágenes viajan dentro del JSON |
| URLs firmadas (opcional) | No | Sin resultados |

`B/servicio/ServicioProductoImpl.java:63-66, 272-283`

```java
private static final int TAMANO_MAXIMO_IMAGEN_BYTES = 1024 * 1024;
private static final List<String> PREFIJOS_IMAGEN =
        List.of("data:image/jpeg;base64,", "data:image/png;base64,", "data:image/webp;base64,");
private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]*={0,2}");
// ...
private static boolean imagenValida(String imagen) {
    String base64 = PREFIJOS_IMAGEN.stream()
            .filter(imagen::startsWith)
            .findFirst()
            .map(prefijo -> imagen.substring(prefijo.length()))
            .orElse(null);
    if (base64 == null || base64.isEmpty() || base64.length() % 4 != 0 || !BASE64.matcher(base64).matches()) {
        return false;
    }
    long relleno = base64.chars().filter(caracter -> caracter == '=').count();
    return base64.length() / 4L * 3 - relleno <= TAMANO_MAXIMO_IMAGEN_BYTES;
}
```

`B/controlador/LimiteTamanoCuerpoPeticion.java:32-34`

```java
public static final int TAMANO_MAXIMO_BYTES = 16 * 1024;
// HU15.1: imagen de hasta 1 MB, que en base64 ocupa unos 1,4 MB
public static final int TAMANO_MAXIMO_ALTA_PRODUCTO_BYTES = 2 * 1024 * 1024;
```

### 3.7 Cumplimiento y privacidad

| Criterio | Estado | Evidencia o búsqueda |
|---|---|---|
| Secretos fuera del código y del repositorio | Parcial | Pepper, SMTP y clave 2FA de producción por variables de entorno; `.env` ignorado (`esibuy-backend/.gitignore:35`). Pero hay dos secretos versionados (hallazgos H-01 y H-02) |
| Registros de auditoría securizados | Parcial | Eventos con correlationId y saneado (`AuditoriaSeguridad.java:278-283`); sin firma ni almacén aparte |
| AGR con MARISMA | Verificación manual | Documento externo |
| Cumplimiento RGPD | Parcial | Baja con borrado (`B/servicio/ServicioBajaCuenta.java:85-96`) y casilla de aceptación en el registro (`F/src/app/registro/registro.html:455`). El backend no guarda la aceptación; el botón «Privacidad» de `F/src/app/home/home.html:158` no enlaza a ningún texto |
| Data masking de logs | Sí | `B/servicio/EnmascaradorDatosLog.java:23-31` |

---

## 4. Hallazgos de seguridad

| Id | Severidad | Hallazgo | Dónde |
|---|---|---|---|
| H-01 | Alta | Clave AES-256 del 2FA de desarrollo versionada. Si la aplicación se despliega sin el perfil `prod`, los secretos TOTP se cifran con una clave pública en el repositorio | `R/application.properties:67` (`esibuy.dosfactores.claves.1=<REDACTADO>`) |
| H-02 | Media | Token de Sonar versionado en los dos repositorios (decisión acordada con el profesorado, pero es un secreto en claro) | `esibuy-backend/sonar-project.properties:8`, `F/sonar-project.properties` |
| H-03 | Alta | Sin tercer factor y administrador sin 2FA obligatorio: la cuenta con más privilegios entra solo con contraseña | `B/servicio/ServicioGestionUsuarios.java:108-130`, `B/servicio/ServicioAutenticacion.java:115` |
| H-04 | Media | DNI y teléfono sin cifrar en la BBDD | `B/modelo/PerfilUsuario.java:17-22` |
| H-05 | Media | La imagen de producto se valida por el prefijo declarado, no por su firma real: se puede guardar contenido que no es una imagen | `B/servicio/ServicioProductoImpl.java:272-283` |
| H-06 | Media | La conexión a MongoDB por defecto no lleva autenticación ni TLS, y no hay usuarios separados | `R/application.properties:4` |
| H-07 | Baja | Sin caducidad ni historial de contraseñas | ver RQ-05 |
| H-08 | Baja | Logs en texto, no en JSON, y sin el endpoint | `B/servicio/AuditoriaSeguridad.java:278-283` |
| H-09 | Baja | El frontend apunta a `http://localhost:8080` de forma fija | `F/src/app/core/api.ts:8`, `F/src/app/api.config.ts:2`, `F/src/app/home/home-api.service.ts:6` |
| H-10 | Informativo | Fichero residual en la raíz llamado `emgrep scan --config auto --sarif -o semgrep.sarif` (contiene un listado de ramas) | raíz del repositorio |

---

## 5. Comprobaciones manuales pendientes

| RQ | Qué aportar |
|---|---|
| 08 | Capturas de la aplicación en Chrome, Firefox, Edge y Safari |
| 09 | Captura del candado y del certificado en la URL desplegada (frontend y backend) |
| 11 | Captura del panel de Sonar con 0 bloqueantes y 0 críticos en backend y frontend, con la fecha del análisis |
| 12 | Informe del AGR o exportación de MARISMA |
| 13 | Captura del informe de JaCoCo (`target/site/jacoco/index.html`) o de la cobertura en Sonar; cobertura del frontend; captura de código sin uso en Sonar |
| 14 | Captura de SonarLint en modo conectado en el IDE |
| 17 | URI de MongoDB de producción sin la contraseña, usuarios y roles creados, y prueba de TLS |
| 19 | Resultado de la herramienta de análisis con IA (SARIF o JSON) y la tabla de decisiones del equipo |
