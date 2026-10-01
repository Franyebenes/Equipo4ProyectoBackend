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
import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.dto.TipoCliente;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.RegistroNoCompletadoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/**
 * Registro de clientes y vendedores.
 *
 * Flujo comun:
 * 1. Normalizar las entradas.</li>
 * 2. Validar el formato de todos los campos (sin tocar BBDD ni servicios externos) y devolver todos los
 *       errores a la vez en {@link com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException}.</li>
 * 3. Solo con los datos bien formados: comprobar dominio del email y, en vendedores, nombre comercial y
 *       categoria. Asi no se lanzan consultas con datos basura ni se hacen esperas DNS innecesarias.</li>
 * 4. Comprobar que el email no esta registrado ({@link RegistroNoCompletadoException}, mensaje generico).</li>
 * 5. Codificar la contrasena, cuando todo es valido y guardar con un unico rol y desactivado.</li>
 * 6. creación de la cuenta
 */
@Service
public class ServicioRegistro {
//Registro de clientes y vendedores.
    public static final int LONGITUD_MAXIMA_CAMPO_TEXTO = 100;
    static final String MENSAJE_REGISTRO_CORRECTO = "Tu cuenta se ha creado correctamente. Podras acceder cuando un administrador la active";

    private static final ZoneId ZONA_APLICACION = ZoneId.of("Europe/Madrid"); // La edad se calcula en la zona de la aplicacion

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
        DatosComunes datos = DatosComunes.normalizar(solicitud.nombre(), solicitud.apellidos(), solicitud.dni(),
                solicitud.email(), solicitud.telefono(), solicitud.avatar(), solicitud.contrasena(),
                solicitud.repetirContrasena());

        ErroresRegistro errores = validarFormatoComun(datos, null);
        ReglasCamposRegistro.validarFechaNacimiento(CAMPO_FECHA_NACIMIENTO, solicitud.fechaNacimiento(), hoy(),
                errores);
        errores.lanzarSiHay();

        ErroresRegistro erroresExternos = new ErroresRegistro();
        comprobarDominioEmail(datos.email(), erroresExternos);
        erroresExternos.lanzarSiHay();

        PerfilUsuario perfil = perfilComun(datos, catalogoAvatares::avatarPorDefectoCliente)
                .fechaNacimiento(solicitud.fechaNacimiento())
                .build();
        return crearCuenta(datos, rolDeCliente(solicitud.tipoCliente()), perfil);
    }

    public RespuestaRegistroDTO registrarVendedor(SolicitudRegistroVendedorDTO solicitud) {
        DatosComunes datos = DatosComunes.normalizar(solicitud.nombre(), solicitud.apellidos(), solicitud.dni(),
                solicitud.email(), solicitud.telefono(), solicitud.avatar(), solicitud.contrasena(),
                solicitud.repetirContrasena());
        String nombreComercial = NormalizadorRegistro.nombreComercial(solicitud.nombreComercial());
        String categoriaId = NormalizadorRegistro.texto(solicitud.categoriaPrincipalId());

        ErroresRegistro errores = validarFormatoComun(datos, nombreComercial);
        ReglasCamposRegistro.validarTextoObligatorio(CAMPO_NOMBRE_COMERCIAL, nombreComercial,
                LONGITUD_MAXIMA_CAMPO_TEXTO, errores);
        ReglasCamposRegistro.validarCategoriaPrincipal(CAMPO_CATEGORIA_PRINCIPAL, categoriaId, errores);
        errores.lanzarSiHay();

        ErroresRegistro erroresExternos = new ErroresRegistro();
        comprobarDominioEmail(datos.email(), erroresExternos);
        if (repositorioUsuario.existePorNombreComercial(NormalizadorRegistro.claveNombreComercial(nombreComercial))) {
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

    /* validacion */

    //Reglas locales comunes a clientes y vendedores. Se decide mantener el campo nombreComercial para clientes, pero como nulo
    private ErroresRegistro validarFormatoComun(DatosComunes datos, String nombreComercial) {
        ErroresRegistro errores = new ErroresRegistro();
        ReglasCamposRegistro.validarTextoObligatorio(CAMPO_NOMBRE, datos.nombre(), LONGITUD_MAXIMA_CAMPO_TEXTO, errores);
        ReglasCamposRegistro.validarTextoObligatorio(CAMPO_APELLIDOS, datos.apellidos(), LONGITUD_MAXIMA_CAMPO_TEXTO,
                errores);
        ReglasCamposRegistro.validarTextoObligatorio(CAMPO_DNI, datos.dni(), LONGITUD_MAXIMA_CAMPO_TEXTO, errores);
        ReglasCamposRegistro.validarEmail(CAMPO_EMAIL, datos.email(), errores);
        ReglasCamposRegistro.validarTelefono(CAMPO_TELEFONO, datos.telefono(), errores);
        if (datos.avatar() != null && !catalogoAvatares.esAvatarValido(datos.avatar())) {
            errores.anadir(CAMPO_AVATAR, CodigoError.AVATAR_NO_PERMITIDO);
        }
        validarContrasena(datos, nombreComercial, errores);
        return errores;
    }

    private void validarContrasena(DatosComunes datos, String nombreComercial, ErroresRegistro errores) {
        DatosPersonalesContrasena datosPersonales =
                new DatosPersonalesContrasena(datos.nombre(), datos.apellidos(), datos.email(), nombreComercial);
        errores.anadirTodos(CAMPO_CONTRASENA, validadorContrasena.validar(datos.contrasena(), datosPersonales));
        // Comparacion exacta (tras NFC): una diferencia solo de mayusculas tambien cuenta (CP-PWD-14)
        if (datos.contrasena() != null && !datos.contrasena().equals(datos.repetirContrasena())) {
            errores.anadir(CAMPO_REPETIR_CONTRASENA, CodigoError.CONTRASENAS_NO_COINCIDEN);
        }
    }

    // Si el servicio de dominios falla, se aborta el registro con un error controlado (CP-REG-25). 
    private void comprobarDominioEmail(String email, ErroresRegistro errores) {
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

/* creacion de la cuenta*/

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

    private Usuario guardar(Usuario usuario) {
        try {
            return repositorioUsuario.save(usuario);
        } catch (DuplicateKeyException e) { // Puede ocurrir si llegan dos solicitudes simultaneas con el mismo email. No remitir el error a cliente.
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

    private static Rol rolDeCliente(TipoCliente tipoCliente) {
        return tipoCliente == TipoCliente.PREMIUM ? Rol.PREMIUM : Rol.CLIENTE;
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj.withZone(ZONA_APLICACION));
    }

    // Campos comunes a clientes y vendedores, ya normalizados.
    private record DatosComunes(String nombre, String apellidos, String dni, String email, String telefono,
                                String avatar, String contrasena, String repetirContrasena) {

        static DatosComunes normalizar(String nombre, String apellidos, String dni, String email, String telefono,
                                       String avatar, String contrasena, String repetirContrasena) {
            return new DatosComunes(
                    NormalizadorRegistro.texto(nombre),
                    NormalizadorRegistro.texto(apellidos),
                    NormalizadorRegistro.texto(dni),
                    NormalizadorRegistro.email(email),
                    NormalizadorRegistro.texto(telefono),
                    NormalizadorRegistro.texto(avatar),
                    NormalizadorRegistro.contrasena(contrasena),
                    NormalizadorRegistro.contrasena(repetirContrasena));
        }

        // Contiene la contrasena: nunca debe acabar en un log.
        @Override
        public String toString() {
            return "DatosComunes[OCULTO]";
        }
    }
}
