package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Registro de clientes y vendedores. Tests: ServicioRegistro*Test.
 *
 * TODO al implementar: normalizar entradas (email, nombres, nombre comercial), validar campos y
 * contrasena (acumulando TODOS los errores en DatosRegistroInvalidosException), calcular la edad con la
 * zona Europe/Madrid, comprobar dominio del email, unicidad (RegistroNoCompletadoException), traducir
 * DuplicateKeyException, codificar la contrasena SOLO si todo es valido y asignar un unico rol.
 */
@Service
public class ServicioRegistro {

    public static final int LONGITUD_MAXIMA_CAMPO_TEXTO = 100;

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
        throw new UnsupportedOperationException("Pendiente de implementar");
    }

    public RespuestaRegistroDTO registrarVendedor(SolicitudRegistroVendedorDTO solicitud) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }
}
