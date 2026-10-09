package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistro;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.dto.TipoCuenta;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.RegistroNoCompletadoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/**
 * Registro de clientes y vendedores. Tests: ServicioRegistro*Test.
 *
 * <p>Flujo comun:
 * <ol>
 *   <li>Normalizar las entradas.</li>
 *   <li>Validar el formato de todos los campos, sin tocar la BBDD ni servicios externos, y devolver todos los
 *       errores a la vez en {@link com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException}.</li>
 *   <li>Solo con datos bien formados: comprobar el dominio del email y, en vendedores, el nombre comercial y la
 *       categoria. Asi no se lanzan consultas con datos basura ni esperas DNS innecesarias.</li>
 *   <li>Comprobar que el email no esta registrado ({@link RegistroNoCompletadoException}, mensaje generico).</li>
 *   <li>Codificar la contrasena (solo ahora, cuando todo es valido) y guardar la cuenta con un unico rol y
 *       desactivada.</li>
 * </ol>
 */
@Service
public class ServicioRegistro {

    public static final int LONGITUD_MAXIMA_CAMPO_TEXTO = 100;
    static final String MENSAJE_REGISTRO_CORRECTO =
            "Tu cuenta se ha creado correctamente. Podras acceder cuando un administrador la active";

    /** La edad se calcula en la zona de la aplicacion, no en la del reloj inyectado (CP-REG-37). */
    private static final ZoneId ZONA_APLICACION = ZoneId.of("Europe/Madrid");

    // Nombres de campo de los errores: coinciden con los del JSON de la solicitud
    private static final String CAMPO_NOMBRE = "nombre";
    private static final String CAMPO_APELLIDOS = "apellidos";
    private static final String CAMPO_DNI = "dni";
    private static final String CAMPO_EMAIL = "email";
    private static final String CAMPO_TELEFONO = "telefono";
    private static final String CAMPO_AVATAR = "avatar";
    private static final String CAMPO_CONTRASENA = "contrasena";
    private static final String CAMPO_REPETIR_CONTRASENA = "repetirContrasena";
    private static final String CAMPO_FECHA_NACIMIENTO = "fechaNacimiento";
    private static final String CAMPO_NOMBRE_COMERCIAL = "nombreComercial";
    private static final String CAMPO_CATEGORIA_PRINCIPAL = "categoriaPrincipalId";

    private static final Logger log = LoggerFactory.getLogger(ServicioRegistro.class);

    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioCategoria repositorioCategoria;
    private final ValidadorDominioEmail validadorDominioEmail;
    private final ValidadorContrasena validadorContrasena;
    private final PasswordEncoder codificadorContrasena;
    private final CatalogoAvatares catalogoAvatares;
    private final Clock reloj;

    public ServicioRegistro(RepositorioUsuario repositorioUsuario,
                            RepositorioCategoria repositorioCategoria,
                            ValidadorDominioEmail validadorDominioEmail,
                            ValidadorContrasena validadorContrasena,
                            PasswordEncoder codificadorContrasena,
                            CatalogoAvatares catalogoAvatares,
                            Clock reloj) {
        this.repositorioUsuario = repositorioUsuario;
        this.repositorioCategoria = repositorioCategoria;
        this.validadorDominioEmail = validadorDominioEmail;
        this.validadorContrasena = validadorContrasena;
        this.codificadorContrasena = codificadorContrasena;
        this.catalogoAvatares = catalogoAvatares;
        this.reloj = reloj;
    }

    public RespuestaRegistroDTO registrarCliente(SolicitudRegistroClienteDTO solicitud) {
        DatosComunes datos = DatosComunes.de(solicitud);

        ErroresValidacion errores = validarFormatoComun(datos, null);
        ReglasCampos.validarFechaNacimiento(CAMPO_FECHA_NACIMIENTO, solicitud.fechaNacimiento(), hoy(),
                errores);
        errores.lanzarSiHay();

        ErroresValidacion erroresExternos = new ErroresValidacion();
        comprobarDominioEmail(datos.email(), erroresExternos);
        erroresExternos.lanzarSiHay();

        PerfilUsuario perfil = perfilComun(datos, catalogoAvatares::avatarPorDefectoCliente)
                .fechaNacimiento(solicitud.fechaNacimiento())
                .build();
        return crearCuenta(datos, rolDeCliente(solicitud.tipoCuenta()), perfil);
    }

    public RespuestaRegistroDTO registrarVendedor(SolicitudRegistroVendedorDTO solicitud) {
        DatosComunes datos = DatosComunes.de(solicitud);
        String nombreComercial = Normalizador.nombreComercial(solicitud.nombreComercial());
        String categoriaId = Normalizador.texto(solicitud.categoriaPrincipalId());

        ErroresValidacion errores = validarFormatoComun(datos, nombreComercial);
        ReglasCampos.validarTextoObligatorio(CAMPO_NOMBRE_COMERCIAL, nombreComercial,
                LONGITUD_MAXIMA_CAMPO_TEXTO, errores);
        ReglasCampos.validarCategoriaPrincipal(CAMPO_CATEGORIA_PRINCIPAL, categoriaId, errores);
        errores.lanzarSiHay();

        ErroresValidacion erroresExternos = new ErroresValidacion();
        comprobarDominioEmail(datos.email(), erroresExternos);
        if (repositorioUsuario.existePorNombreComercial(Normalizador.claveNombreComercial(nombreComercial))) {
            erroresExternos.anadir(CAMPO_NOMBRE_COMERCIAL, CodigoError.NOMBRE_COMERCIAL_DUPLICADO);
        }
        if (!repositorioCategoria.existsById(categoriaId)) {
            erroresExternos.anadir(CAMPO_CATEGORIA_PRINCIPAL, CodigoError.CATEGORIA_INEXISTENTE);
        }
        erroresExternos.lanzarSiHay();

        PerfilUsuario perfil = perfilComun(datos, catalogoAvatares::avatarPorDefectoVendedor)
                .nombreComercial(nombreComercial)
                .categoriaPrincipalId(categoriaId)
                .build();
        return crearCuenta(datos, Rol.VENDEDOR, perfil);
    }

    // ------------------------------------------------------------------ validacion

    /**
     * Reglas locales comunes a clientes y vendedores. El nombre comercial solo se usa para comprobar que no
     * aparece en la contrasena; en clientes es null.
     */
    private ErroresValidacion validarFormatoComun(DatosComunes datos, String nombreComercial) {
        ErroresValidacion errores = new ErroresValidacion();
        ReglasCampos.validarTextoObligatorio(CAMPO_NOMBRE, datos.nombre(), LONGITUD_MAXIMA_CAMPO_TEXTO, errores);
        ReglasCampos.validarTextoObligatorio(CAMPO_APELLIDOS, datos.apellidos(), LONGITUD_MAXIMA_CAMPO_TEXTO,
                errores);
        ReglasCampos.validarTextoObligatorio(CAMPO_DNI, datos.dni(), LONGITUD_MAXIMA_CAMPO_TEXTO, errores);
        ReglasCampos.validarEmail(CAMPO_EMAIL, datos.email(), errores);
        ReglasCampos.validarTelefono(CAMPO_TELEFONO, datos.telefono(), errores);
        if (datos.avatar() != null && !catalogoAvatares.esAvatarValido(datos.avatar())) {
            errores.anadir(CAMPO_AVATAR, CodigoError.AVATAR_NO_PERMITIDO);
        }
        validarContrasena(datos, nombreComercial, errores);
        return errores;
    }

    private void validarContrasena(DatosComunes datos, String nombreComercial, ErroresValidacion errores) {
        DatosPersonalesContrasena datosPersonales =
                new DatosPersonalesContrasena(datos.nombre(), datos.apellidos(), datos.email(), nombreComercial);
        errores.anadirTodos(CAMPO_CONTRASENA, validadorContrasena.validar(datos.contrasena(), datosPersonales));
        // Comparacion exacta (tras NFC): una diferencia solo de mayusculas tambien cuenta (CP-PWD-14)
        if (datos.contrasena() != null && !datos.contrasena().equals(datos.repetirContrasena())) {
            errores.anadir(CAMPO_REPETIR_CONTRASENA, CodigoError.CONTRASENAS_NO_COINCIDEN);
        }
    }

    /** Si el servicio de dominios falla, se aborta el registro con un error controlado (CP-REG-25). */
    private void comprobarDominioEmail(String email, ErroresValidacion errores) {
        boolean dominioValido;
        try {
            dominioValido = validadorDominioEmail.tieneDominioValido(email);
        } catch (RuntimeException e) {
            throw new ServicioNoDisponibleException(e);
        }
        if (!dominioValido) {
            errores.anadir(CAMPO_EMAIL, CodigoError.DOMINIO_EMAIL_INEXISTENTE);
        }
    }

    // ------------------------------------------------------------------ creacion de la cuenta

    private RespuestaRegistroDTO crearCuenta(DatosComunes datos, Rol rol, PerfilUsuario perfil) {
        if (repositorioUsuario.existePorEmail(datos.email())) {
            log.info("Registro rechazado: el email ya pertenece a una cuenta");
            throw new RegistroNoCompletadoException();
        }
        String hash = codificadorContrasena.encode(datos.contrasena());
        Usuario guardado = guardar(new Usuario(datos.email(), hash, rol, perfil));
        log.info("Cuenta creada: id={}, rol={}", guardado.getId(), rol);
        return new RespuestaRegistroDTO(guardado.getId(), guardado.getEmail(), perfil.getNombre(),
                MENSAJE_REGISTRO_CORRECTO);
    }

    /** Un registro simultaneo con el mismo email puede ganar la carrera a la comprobacion previa (CP-REG-41). */
    private Usuario guardar(Usuario usuario) {
        try {
            return repositorioUsuario.save(usuario);
        } catch (DuplicateKeyException e) { // NOSONAR S1166: la causa se descarta a proposito
            // El mensaje de MongoDB incluye el valor duplicado (el email): no puede llegar al log ni al cliente,
            // asi que la causa no se encadena ni se registra.
            log.warn("Registro rechazado: clave duplicada al guardar (registro simultaneo)");
            throw new RegistroNoCompletadoException();
        }
    }

    private PerfilUsuario.Builder perfilComun(DatosComunes datos, Supplier<String> avatarPorDefecto) {
        return PerfilUsuario.builder()
                .nombre(datos.nombre())
                .apellidos(datos.apellidos())
                .dni(datos.dni())
                .telefono(datos.telefono())
                .avatarUrl(Objects.requireNonNullElseGet(datos.avatar(), avatarPorDefecto));
    }

    /** Una solicitud de cliente solo llega con CLIENTE o PREMIUM (lo garantiza {@link SolicitudRegistro}). */
    private static Rol rolDeCliente(TipoCuenta tipoCuenta) {
        return tipoCuenta == TipoCuenta.PREMIUM ? Rol.PREMIUM : Rol.CLIENTE;
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj.withZone(ZONA_APLICACION));
    }

    /** Campos comunes a clientes y vendedores, ya normalizados. */
    private record DatosComunes(String nombre, String apellidos, String dni, String email, String telefono,
                                String avatar, String contrasena, String repetirContrasena) {

        static DatosComunes de(SolicitudRegistro solicitud) {
            return new DatosComunes(
                    Normalizador.texto(solicitud.nombre()),
                    Normalizador.texto(solicitud.apellidos()),
                    Normalizador.texto(solicitud.dni()),
                    Normalizador.email(solicitud.email()),
                    Normalizador.texto(solicitud.telefono()),
                    Normalizador.texto(solicitud.avatar()),
                    Normalizador.contrasena(solicitud.contrasena()),
                    Normalizador.contrasena(solicitud.repetirContrasena()));
        }

        /** Contiene la contrasena: nunca debe acabar en un log. */
        @Override
        public String toString() {
            return "DatosComunes[OCULTO]";
        }
    }
}
