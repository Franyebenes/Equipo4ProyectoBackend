package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.dto.SolicitudAltaAdministradorDTO;
import com.esibuy.esibuy_backend.dto.SolicitudModificacionUsuarioDTO;
import com.esibuy.esibuy_backend.dto.UsuarioDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.OperacionNoPermitidaException;
import com.esibuy.esibuy_backend.excepcion.RegistroNoCompletadoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.excepcion.UsuarioNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

@Service
public class ServicioGestionUsuarios {

    static final int TAMANO_MAXIMO_PAGINA = 100;
    static final String MENSAJE_ADMINISTRADOR_CREADO = "La cuenta de administrador se ha creado correctamente";

    private static final ZoneId ZONA_APLICACION = ZoneId.of("Europe/Madrid");

    // Nombres de campo de los errores: coinciden con los del JSON de la solicitud
    private static final String CAMPO_NOMBRE = "nombre";
    private static final String CAMPO_APELLIDOS = "apellidos";
    private static final String CAMPO_DNI = "dni";
    private static final String CAMPO_EMAIL = "email";
    private static final String CAMPO_TELEFONO = "telefono";
    private static final String CAMPO_SEDE = "sede";
    private static final String CAMPO_AVATAR = "avatar";
    private static final String CAMPO_CONTRASENA = "contrasena";
    private static final String CAMPO_REPETIR_CONTRASENA = "repetirContrasena";

    // Estados de usuario que pueden pasar a otro estado mediante la API de administración
    private static final Set<EstadoUsuario> PUEDEN_BLOQUEARSE =
            EnumSet.of(EstadoUsuario.ACTIVO, EstadoUsuario.DESACTIVADO);
    private static final Set<EstadoUsuario> PUEDEN_DESBLOQUEARSE = EnumSet.of(EstadoUsuario.BLOQUEADO, EstadoUsuario.DESACTIVADO);
    private static final Set<EstadoUsuario> PUEDEN_ELIMINARSE =
            EnumSet.of(EstadoUsuario.ACTIVO, EstadoUsuario.DESACTIVADO, EstadoUsuario.BLOQUEADO);

    private static final Logger log = LoggerFactory.getLogger(ServicioGestionUsuarios.class);

    private final RepositorioUsuario repositorioUsuario;
    private final ValidadorDominioEmail validadorDominioEmail;
    private final ValidadorContrasena validadorContrasena;
    private final PasswordEncoder codificadorContrasena;
    private final CatalogoAvatares catalogoAvatares;
    // para la fecha de incorporacion
    private final Clock reloj;

    public ServicioGestionUsuarios(RepositorioUsuario repositorioUsuario,
                                  ValidadorDominioEmail validadorDominioEmail,
                                  ValidadorContrasena validadorContrasena,
                                  PasswordEncoder codificadorContrasena,
                                  CatalogoAvatares catalogoAvatares,
                                  Clock reloj) {
        this.repositorioUsuario = repositorioUsuario;
        this.validadorDominioEmail = validadorDominioEmail;
        this.validadorContrasena = validadorContrasena;
        this.codificadorContrasena = codificadorContrasena;
        this.catalogoAvatares = catalogoAvatares;
        this.reloj = reloj;
    }

    public RespuestaRegistroDTO crearAdministrador(SolicitudAltaAdministradorDTO solicitud) {
        // Normalizar los datos de la solicitud (eliminando espacios, etc.)
        DatosAdministrador datos = DatosAdministrador.normalizar(solicitud.nombre(), solicitud.apellidos(),
                solicitud.email(), solicitud.sede(), solicitud.avatar(), solicitud.contrasena(),
                solicitud.repetirContrasena());

        // Validar los datos personales y la contraseña, acumulando errores
        ErroresValidacion errores = validarAltaAdministrador(datos);
        errores.lanzarSiHay();

        ErroresValidacion erroresExternos = new ErroresValidacion();
        comprobarDominioEmail(datos.email(), erroresExternos);
        erroresExternos.lanzarSiHay();

        PerfilUsuario perfil = PerfilUsuario.builder()
                .nombre(datos.nombre())
                .apellidos(datos.apellidos())
                .sede(datos.sede())
                .avatarUrl(datos.avatar())
                .fechaIncorporacion(LocalDate.now(reloj.withZone(ZONA_APLICACION)))
                .build();
        return crearCuenta(datos, Rol.ADMIN, perfil);
    }

    public PaginaDTO<UsuarioDTO> listar(int pagina, int tamano, Rol rol, EstadoUsuario estado) {
        // Math.clamp mantiene el tamano dentro del rango permitido; Sort.by usa un campo constante,
        // nunca un valor que venga del cliente.
        Pageable pageable = PageRequest.of(Math.max(0, pagina), Math.clamp(tamano, 1, TAMANO_MAXIMO_PAGINA),
                Sort.by(CAMPO_EMAIL));
        return PaginaDTO.desde(buscarPagina(rol, estado, pageable).map(UsuarioDTO::desde));
    }

    public UsuarioDTO modificar(String id, SolicitudModificacionUsuarioDTO solicitud) {
        String nombre = Normalizador.texto(solicitud.nombre());
        String apellidos = Normalizador.texto(solicitud.apellidos());
        String dni = Normalizador.texto(solicitud.dni());
        String telefono = Normalizador.texto(solicitud.telefono());
        String sede = Normalizador.texto(solicitud.sede());

        int max = ServicioRegistro.LONGITUD_MAXIMA_CAMPO_TEXTO;
        ErroresValidacion errores = new ErroresValidacion();
        ReglasCampos.validarTextoObligatorio(CAMPO_NOMBRE, nombre, max, errores);
        ReglasCampos.validarTextoObligatorio(CAMPO_APELLIDOS, apellidos, max, errores);
        ReglasCampos.validarTextoOpcional(CAMPO_DNI, dni, max, errores);
        ReglasCampos.validarTelefono(CAMPO_TELEFONO, telefono, errores);
        ReglasCampos.validarTextoOpcional(CAMPO_SEDE, sede, max, errores);
        errores.lanzarSiHay();

        Usuario usuario = buscarNoEliminado(id);
        usuario.actualizarDatosPersonales(nombre, apellidos, dni, telefono, sede);
        Usuario guardado = repositorioUsuario.save(usuario);
        log.info("Usuario modificado: id={}", guardado.getId());
        return UsuarioDTO.desde(guardado);
    }

     public UsuarioDTO bloquear(String id, String idSolicitante) {
        return cambiarEstado(id, idSolicitante, PUEDEN_BLOQUEARSE, EstadoUsuario.BLOQUEADO);
    }

    public UsuarioDTO desbloquear(String id, String idSolicitante) {
        return cambiarEstado(id, idSolicitante, PUEDEN_DESBLOQUEARSE, EstadoUsuario.ACTIVO);
    }

    public void eliminar(String id, String idSolicitante) {
        cambiarEstado(id, idSolicitante, PUEDEN_ELIMINARSE, EstadoUsuario.ELIMINADO);
    }

    /* validacion */

    private ErroresValidacion validarAltaAdministrador(DatosAdministrador datos) {
        ErroresValidacion errores = new ErroresValidacion();
        int max = ServicioRegistro.LONGITUD_MAXIMA_CAMPO_TEXTO;
        ReglasCampos.validarTextoObligatorio(CAMPO_NOMBRE, datos.nombre(), max, errores);
        ReglasCampos.validarTextoObligatorio(CAMPO_APELLIDOS, datos.apellidos(), max, errores);
        ReglasCampos.validarEmail(CAMPO_EMAIL, datos.email(), errores);
        ReglasCampos.validarTextoOpcional(CAMPO_SEDE, datos.sede(), max, errores);
        if (datos.avatar() != null && !catalogoAvatares.esAvatarValido(datos.avatar())) {
            errores.anadir(CAMPO_AVATAR, CodigoError.AVATAR_NO_PERMITIDO);
        }
        validarContrasena(datos, errores);
        return errores;
    }

    private void validarContrasena(DatosAdministrador datos, ErroresValidacion errores) {
        DatosPersonalesContrasena datosPersonales =
                new DatosPersonalesContrasena(datos.nombre(), datos.apellidos(), datos.email(), null);
        errores.anadirTodos(CAMPO_CONTRASENA, validadorContrasena.validar(datos.contrasena(), datosPersonales));
        // Comparacion exacta (tras NFC): una diferencia solo de mayusculas tambien cuenta (CP-PWD-14)
        if (datos.contrasena() != null && !datos.contrasena().equals(datos.repetirContrasena())) {
            errores.anadir(CAMPO_REPETIR_CONTRASENA, CodigoError.CONTRASENAS_NO_COINCIDEN);
        }
    }

    // Si el servicio de dominios falla, se aborta el alta con un error controlado (CP-REG-25).
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

    /* helpers */

    private RespuestaRegistroDTO crearCuenta(DatosAdministrador datos, Rol rol, PerfilUsuario perfil) {
        if (repositorioUsuario.existePorEmail(datos.email())) {
            log.info("Alta rechazada: el email ya pertenece a una cuenta");
            throw new RegistroNoCompletadoException();
        }
        String hash = codificadorContrasena.encode(datos.contrasena());
        Usuario guardado = guardar(new Usuario(datos.email(), hash, rol, perfil));
        log.info("Cuenta creada: id={}, rol={}", guardado.getId(), rol);
        return new RespuestaRegistroDTO(guardado.getId(), guardado.getEmail(), perfil.getNombre(),
                MENSAJE_ADMINISTRADOR_CREADO);
    }

    private Usuario guardar(Usuario usuario) {
        try {
            return repositorioUsuario.save(usuario);
        } catch (DuplicateKeyException e) { // Puede ocurrir si llegan dos solicitudes simultaneas con el mismo email. No remitir el error a cliente.
            log.warn("Alta rechazada: clave duplicada al guardar (alta simultanea)");
            throw new RegistroNoCompletadoException();
        }
    }

    private UsuarioDTO cambiarEstado(String id, String idSolicitante, Set<EstadoUsuario> origenesValidos,
                                     EstadoUsuario destino) {
        Usuario usuario = buscarNoEliminado(id);
        // El principal de la sesion es el id del usuario (EstablecedorSesion): un admin no puede cambiarse a si mismo.
        if (usuario.getId().equals(idSolicitante) || !origenesValidos.contains(usuario.getEstado())) {
            throw new OperacionNoPermitidaException();
        }
        usuario.cambiarEstado(destino);
        Usuario guardado = repositorioUsuario.save(usuario);
        log.info("Estado de usuario cambiado: id={}, nuevoEstado={}", guardado.getId(), destino);
        return UsuarioDTO.desde(guardado);
    }

    private Page<Usuario> buscarPagina(Rol rol, EstadoUsuario estado, Pageable pageable) {
        if (rol == null) {
            return estado == null
                    ? repositorioUsuario.findByEstadoNot(EstadoUsuario.ELIMINADO, pageable)
                    : repositorioUsuario.findByEstado(estado, pageable);
        }
        return estado == null
                ? repositorioUsuario.findPorRolExcluyendoEstado(rol, EstadoUsuario.ELIMINADO, pageable)
                : repositorioUsuario.findPorRolYEstado(rol, estado, pageable);
    }

    private Usuario buscarNoEliminado(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new UsuarioNoEncontradoException();
        }
        return repositorioUsuario.findByIdAndEstadoNot(id, EstadoUsuario.ELIMINADO)
                .orElseThrow(UsuarioNoEncontradoException::new);
    }

    // Datos del alta de administrador, ya normalizados.
    private record DatosAdministrador(String nombre, String apellidos, String email, String sede, String avatar,
                                      String contrasena, String repetirContrasena) {

        static DatosAdministrador normalizar(String nombre, String apellidos, String email, String sede,
                                             String avatar, String contrasena, String repetirContrasena) {
            return new DatosAdministrador(
                 Normalizador.texto(nombre),
                 Normalizador.texto(apellidos),
                 Normalizador.email(email),
                 Normalizador.texto(sede),
                 Normalizador.texto(avatar),
                 Normalizador.contrasena(contrasena),
                 Normalizador.contrasena(repetirContrasena));
        }

        // Contiene la contrasena: nunca debe acabar en un log.
        @Override
        public String toString() {
            return "DatosAdministrador[OCULTO]";
        }
    }
}
