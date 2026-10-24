package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.PerfilDTO;
import com.esibuy.esibuy_backend.dto.SolicitudModificacionPerfilDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.RecursoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/** Ver y modificar el perfil del usuario de la sesion (HU-32.1 y HU-32.2). */
@Service
public class ServicioPerfil {

    static final String CAMPO_AVATAR = "avatar";
    static final String CAMPO_FECHA_NACIMIENTO = "fechaNacimiento";
    static final String CAMPO_NOMBRE_COMERCIAL = "nombreComercial";
    static final String MENSAJE_USUARIO_NO_ENCONTRADO = "El usuario no existe";
    static final String CODIGO_NOMBRE_COMERCIAL_EN_USO = "NOMBRE_COMERCIAL_EN_USO";
    static final String MENSAJE_NOMBRE_COMERCIAL_EN_USO = "Ese nombre comercial ya esta en uso";

    private final RepositorioUsuario repositorioUsuario;
    private final CatalogoAvatares catalogoAvatares;
    private final Clock reloj;

    public ServicioPerfil(RepositorioUsuario repositorioUsuario, CatalogoAvatares catalogoAvatares, Clock reloj) {
        this.repositorioUsuario = repositorioUsuario;
        this.catalogoAvatares = catalogoAvatares;
        this.reloj = reloj;
    }

    public PerfilDTO obtenerPerfil(String idUsuario) {
        return PerfilDTO.desde(buscarUsuario(idUsuario));
    }

    /** Devuelve el usuario ya guardado para que el controlador pueda refrescar la sesion. */
    public Usuario modificarPerfil(String idUsuario, SolicitudModificacionPerfilDTO solicitud) {
        Usuario usuario = buscarUsuario(idUsuario);
        PerfilUsuario perfil = usuario.getPerfil();
        Rol rol = usuario.getRoles().get(0);
        boolean esCliente = rol == Rol.CLIENTE || rol == Rol.PREMIUM;
        boolean esVendedor = rol == Rol.VENDEDOR;
        String nombreComercial = esVendedor ? normalizar(solicitud.nombreComercial()) : perfil.getNombreComercial();

        // 1. Errores por campo, todos a la vez y con el mismo formato que el registro
        ErroresValidacion errores = new ErroresValidacion();
        validarAvatar(solicitud.avatar(), perfil.getAvatarUrl(), errores);
        if (esCliente && solicitud.fechaNacimiento() != null) {
            ReglasCampos.validarFechaNacimiento(CAMPO_FECHA_NACIMIENTO, solicitud.fechaNacimiento(),
                    LocalDate.now(reloj), errores);
        }
        if (esVendedor && nombreComercial == null) {
            errores.anadir(CAMPO_NOMBRE_COMERCIAL, CodigoError.OBLIGATORIO);
        }
        errores.lanzarSiHay();

        // 2. Reglas que consultan la base de datos
        if (esVendedor) {
            comprobarNombreComercialLibre(nombreComercial, perfil.getNombreComercial());
        }

        // 3. Cada rol edita sus campos; los que no le corresponden se conservan tal cual
        LocalDate fechaNacimiento = esCliente && solicitud.fechaNacimiento() != null
                ? solicitud.fechaNacimiento()
                : perfil.getFechaNacimiento();
        perfil.modificarDesdePerfil(
                solicitud.nombre().strip(),
                solicitud.apellidos().strip(),
                normalizar(solicitud.telefono()),
                fechaNacimiento,
                solicitud.avatar(),
                nombreComercial);
        try {
            return repositorioUsuario.save(usuario);
        } catch (DuplicateKeyException e) {
            // Otro vendedor ha cogido el mismo nombre entre la comprobacion y el guardado: lo frena el indice unico
            throw new ConflictoNegocioException(CODIGO_NOMBRE_COMERCIAL_EN_USO, MENSAJE_NOMBRE_COMERCIAL_EN_USO);
        }
    }

    /** Se puede conservar el avatar actual aunque sea el de por defecto, que no esta en el catalogo. */
    private void validarAvatar(String nuevo, String actual, ErroresValidacion errores) {
        if (!nuevo.equals(actual) && !catalogoAvatares.esAvatarValido(nuevo)) {
            errores.anadir(CAMPO_AVATAR, CodigoError.FORMATO_INVALIDO);
        }
    }

    private void comprobarNombreComercialLibre(String nuevo, String actual) {
        boolean cambia = !nuevo.equalsIgnoreCase(actual);
        if (cambia && repositorioUsuario.existePorNombreComercial(nuevo)) {
            throw new ConflictoNegocioException(CODIGO_NOMBRE_COMERCIAL_EN_USO, MENSAJE_NOMBRE_COMERCIAL_EN_USO);
        }
    }

    private Usuario buscarUsuario(String idUsuario) {
        return repositorioUsuario.findById(idUsuario)
                .orElseThrow(() -> new RecursoNoEncontradoException(MENSAJE_USUARIO_NO_ENCONTRADO));
    }

    /** Vacio pasa a null; recorta y colapsa espacios repetidos. */
    private static String normalizar(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.strip().replaceAll("\\s+", " ");
    }
}