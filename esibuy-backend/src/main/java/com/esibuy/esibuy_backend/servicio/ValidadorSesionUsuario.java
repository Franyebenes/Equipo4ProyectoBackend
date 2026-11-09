package com.esibuy.esibuy_backend.servicio;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/*
 * Decide si una sesion sigue valiendo para su usuario: deja de valer si el usuario cambio su contrasena (por
 * recuperacion) en el instante en que se inicio la sesion o despues, o si el usuario ya no existe. Es lo que hace
 * efectivo "se invalidan todas las sesiones abiertas": las sesiones viven en memoria y no se pueden listar ni cerrar
 * desde fuera, asi que cada peticion autenticada comprueba la marca contrasenaCambiadaEn del usuario.
 *
 * Mongo guarda las fechas en milisegundos y la sesion guarda su instante con mas precision: se comparan truncados a
 * milisegundos para que una sesion iniciada en el mismo milisegundo del cambio no sobreviva por un redondeo. Sin id o
 * sin instante de inicio no vale (y no se consulta nada). Un fallo de la base de datos se propaga: lo decide el filtro.
 * Tests: ValidadorSesionUsuarioTest (CP-RST-30 a CP-RST-37).
 */
@Component
public class ValidadorSesionUsuario {

    private final RepositorioUsuario repositorioUsuario;

    public ValidadorSesionUsuario(RepositorioUsuario repositorioUsuario) {
        this.repositorioUsuario = repositorioUsuario;
    }

    public boolean sigueVigente(String idUsuario, Instant inicioSesion) {
        if (idUsuario == null || idUsuario.isBlank() || inicioSesion == null) {
            return false;
        }
        Optional<Usuario> usuario = repositorioUsuario.buscarMarcaDeCredenciales(idUsuario);
        if (usuario.isEmpty()) {
            return false;
        }
        Instant cambio = usuario.get().getContrasenaCambiadaEn();
        return cambio == null
                || inicioSesion.truncatedTo(ChronoUnit.MILLIS).isAfter(cambio.truncatedTo(ChronoUnit.MILLIS));
    }
}
