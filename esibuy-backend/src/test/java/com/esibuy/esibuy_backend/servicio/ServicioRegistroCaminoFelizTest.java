package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.dto.TipoCuenta;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Caminos felices del registro de cliente y de vendedor.
 */
class ServicioRegistroCaminoFelizTest extends ServicioRegistroBaseTest {

    @Test
    void registrarCliente_datosValidos_creaUsuarioConRolClienteYEstadoDesactivado() { // CP-REG-01
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        RespuestaRegistroDTO respuesta = servicio.registrarCliente(solicitud);

        // Then
        Usuario usuario = usuarioGuardado();
        assertThat(usuario.getRoles()).containsExactly(Rol.CLIENTE);
        assertThat(usuario.getEstado()).isEqualTo(EstadoUsuario.DESACTIVADO);
        assertThat(usuario.getEmail()).isEqualTo(ConstructorSolicitudCliente.EMAIL_POR_DEFECTO);
        assertThat(usuario.getPerfil().getNombre()).isEqualTo("Ana");
        assertThat(usuario.getPerfil().getApellidos()).isEqualTo("Garc\u00eda L\u00f3pez");
        assertThat(usuario.getPerfil().getDni()).isEqualTo("12345678Z");
        assertThat(usuario.getPerfil().getFechaNacimiento()).isEqualTo(LocalDate.of(2000, 5, 15));
        assertThat(usuario.getPerfil().getTelefono()).isEqualTo("612345678");
        assertThat(respuesta.mensaje()).isNotBlank();
    }

    @Test
    void registrarCliente_tipoPremium_creaUsuarioConUnUnicoRolPremium() { // CP-REG-02
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conTipoCuenta(TipoCuenta.PREMIUM).construir();

        // When
        servicio.registrarCliente(solicitud);

        // Then
        List<Rol> roles = usuarioGuardado().getRoles();
        assertThat(roles).hasSize(1).containsExactly(Rol.PREMIUM);
        assertThat(roles).doesNotContain(Rol.CLIENTE);
    }

    @Test
    void registrarVendedor_datosValidos_creaUsuarioConRolVendedorCategoriaYNombreComercial() { // CP-REG-03
        // Given
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida().construir();

        // When
        servicio.registrarVendedor(solicitud);

        // Then
        Usuario usuario = usuarioGuardado();
        assertThat(usuario.getRoles()).containsExactly(Rol.VENDEDOR);
        assertThat(usuario.getEstado()).isEqualTo(EstadoUsuario.DESACTIVADO);
        assertThat(usuario.getPerfil().getCategoriaPrincipalId())
                .isEqualTo(ConstructorSolicitudVendedor.CATEGORIA_VALIDA);
        assertThat(usuario.getPerfil().getNombreComercial())
                .isEqualTo(ConstructorSolicitudVendedor.NOMBRE_COMERCIAL_POR_DEFECTO);
    }

    @Test
    void registrarCliente_soloConCamposObligatorios_creaUsuarioSinTelefonoYConAvatarPorDefecto() { // CP-REG-04
        // Given: sin telefono ni avatar
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conTelefono(null).conAvatar(null).construir();

        // When
        servicio.registrarCliente(solicitud);

        // Then
        Usuario usuario = usuarioGuardado();
        assertThat(usuario.getPerfil().getTelefono()).isNull();
        assertThat(usuario.getPerfil().getAvatarUrl()).isEqualTo(AVATAR_CLIENTE_POR_DEFECTO);
    }

    @Test
    void registrarVendedor_sinAvatar_recibeElAvatarPorDefectoDeVendedorDistintoAlDeCliente() { // CP-REG-05
        // Given
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conAvatar(null).construir();

        // When
        servicio.registrarVendedor(solicitud);

        // Then
        String avatarAsignado = usuarioGuardado().getPerfil().getAvatarUrl();
        assertThat(avatarAsignado)
                .isEqualTo(AVATAR_VENDEDOR_POR_DEFECTO)
                .isNotEqualTo(AVATAR_CLIENTE_POR_DEFECTO);
    }

    @Test
    void registrarCliente_conAvatarValidoDelCatalogo_guardaEseAvatar() { // CP-REG-06
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conAvatar(OTRO_AVATAR_VALIDO).construir();

        // When
        servicio.registrarCliente(solicitud);

        // Then
        assertThat(usuarioGuardado().getPerfil().getAvatarUrl()).isEqualTo(OTRO_AVATAR_VALIDO);
    }

    @Test
    void registrarCliente_emailConMayusculasYEspacios_loNormalizaAntesDeComprobarYGuardar() { // CP-REG-07
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail("  Ana.GARCIA@Ejemplo.ES ").construir();

        // When
        servicio.registrarCliente(solicitud);

        // Then
        verify(repositorioUsuario).existePorEmail("ana.garcia@ejemplo.es");
        verify(validadorDominioEmail).tieneDominioValido("ana.garcia@ejemplo.es");
        assertThat(usuarioGuardado().getEmail()).isEqualTo("ana.garcia@ejemplo.es");
    }

    @Test
    void registrarCliente_registroCorrecto_respuestaConConfirmacionYSinContrasenaNiHash() { // CP-REG-08
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        RespuestaRegistroDTO respuesta = servicio.registrarCliente(solicitud);

        // Then
        assertThat(respuesta.email()).isEqualTo(ConstructorSolicitudCliente.EMAIL_POR_DEFECTO);
        assertThat(respuesta.nombre()).isEqualTo("Ana");
        assertThat(respuesta.mensaje()).isNotBlank();

        List<String> componentes = Arrays.stream(RespuestaRegistroDTO.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(componentes).doesNotContain("passwordHash", "contrasena", "password");
        assertThat(respuesta.toString())
                .doesNotContain(ConstructorSolicitudCliente.CONTRASENA_VALIDA)
                .doesNotContain(HASH_SIMULADO);
    }
}