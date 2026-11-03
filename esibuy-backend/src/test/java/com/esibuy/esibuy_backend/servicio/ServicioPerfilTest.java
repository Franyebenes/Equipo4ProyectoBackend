package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.PerfilDTO;
import com.esibuy.esibuy_backend.dto.SolicitudModificacionPerfilDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.DatosInvalidosException;
import com.esibuy.esibuy_backend.excepcion.RecursoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

@ExtendWith(MockitoExtension.class)
class ServicioPerfilTest {

    private static final String ID = "665f1c2e9b1d4a0012345678";
    private static final LocalDate ADULTO = LocalDate.of(1999, 1, 1);

    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private CatalogoAvatares catalogoAvatares;
    private ServicioPerfil servicio;

    @BeforeEach
    void crearServicio() {
        Clock hoy = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        servicio = new ServicioPerfil(repositorioUsuario, catalogoAvatares, hoy);
    }

    private void existe(Rol rol, String nombreComercial, String avatar) {
        PerfilUsuario perfil = PerfilUsuario.builder()
                .nombre("Ana").apellidos("Lopez").dni("12345678Z").fechaNacimiento(LocalDate.of(2000, 5, 1))
                .telefono("600111222").avatarUrl(avatar).nombreComercial(nombreComercial).build();
        when(repositorioUsuario.findById(ID))
                .thenReturn(Optional.of(new Usuario("ana@gmail.com", "$argon2id$hash", rol, perfil)));
    }

    private void guardarDevuelveLoGuardado() {
        when(repositorioUsuario.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static SolicitudModificacionPerfilDTO solicitud(String telefono, LocalDate fecha, String avatar,
                                                            String nombreComercial) {
        return new SolicitudModificacionPerfilDTO("Ana Maria", "Lopez Ruiz", telefono, fecha, avatar, nombreComercial);
    }

    @Test
    void perfilDTO_noTieneCamposSecretos() {
        assertThat(Arrays.stream(PerfilDTO.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("password", "passwordHash", "estado", "contrasenaCambiadaEn");
    }

    @Test
    void obtenerPerfil_usuarioInexistente_lanzaNoEncontrado() {
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.obtenerPerfil(ID)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void modificarPerfil_cliente_aplicaCambiosYConservaEmailSinNombreComercial() {
        existe(Rol.CLIENTE, null, "avatar-01");
        when(catalogoAvatares.esAvatarValido("avatar-02")).thenReturn(true);
        guardarDevuelveLoGuardado();

        Usuario guardado = servicio.modificarPerfil(ID, solicitud("600999888", ADULTO, "avatar-02", "Intento"));

        assertThat(guardado.getEmail()).isEqualTo("ana@gmail.com");
        assertThat(guardado.getPerfil().getNombre()).isEqualTo("Ana Maria");
        assertThat(guardado.getPerfil().getFechaNacimiento()).isEqualTo(ADULTO);
        assertThat(guardado.getPerfil().getNombreComercial()).isNull();
        verify(repositorioUsuario, never()).existePorNombreComercial(anyString());
    }

    @Test
    void modificarPerfil_telefonoVacio_seBorra() {
        existe(Rol.CLIENTE, null, "avatar-01");
        guardarDevuelveLoGuardado();

        Usuario guardado = servicio.modificarPerfil(ID, solicitud("", ADULTO, "avatar-01", null));

        assertThat(guardado.getPerfil().getTelefono()).isNull();
    }

    @Test
    void modificarPerfil_conservaElAvatarPorDefecto_noLoRechaza() {
        existe(Rol.CLIENTE, null, "defecto-cliente");
        guardarDevuelveLoGuardado();

        Usuario guardado = servicio.modificarPerfil(ID, solicitud(null, ADULTO, "defecto-cliente", null));

        assertThat(guardado.getPerfil().getAvatarUrl()).isEqualTo("defecto-cliente");
        verify(catalogoAvatares, never()).esAvatarValido(anyString());
    }

    @Test
    void modificarPerfil_avatarFueraDelCatalogo_lanzaErrorEnAvatarYNoGuarda() {
        existe(Rol.CLIENTE, null, "avatar-01");
        when(catalogoAvatares.esAvatarValido("avatar-99")).thenReturn(false);

        assertThatThrownBy(() -> servicio.modificarPerfil(ID, solicitud(null, ADULTO, "avatar-99", null)))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.getErrores())
                        .containsEntry(ServicioPerfil.CAMPO_AVATAR, java.util.Set.of(CodigoError.FORMATO_INVALIDO)));
        verify(repositorioUsuario, never()).save(any());
    }

    @Test
    void modificarPerfil_clienteMenorDeEdad_lanzaErrorEnFechaYNoGuarda() {
        existe(Rol.CLIENTE, null, "avatar-01");

        assertThatThrownBy(() -> servicio.modificarPerfil(ID, solicitud(null, LocalDate.of(2015, 1, 1), "avatar-01", null)))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.getErrores())
                        .containsEntry(ServicioPerfil.CAMPO_FECHA_NACIMIENTO, java.util.Set.of(CodigoError.MENOR_DE_EDAD)));
        verify(repositorioUsuario, never()).save(any());
    }

    @Test
    void modificarPerfil_vendedorConNombreComercialDeOtro_lanzaConflictoYNoGuarda() {
        existe(Rol.VENDEDOR, "Tienda Ana", "avatar-01");
        when(repositorioUsuario.existePorNombreComercial("Tienda Pepe")).thenReturn(true);

        assertThatThrownBy(() -> servicio.modificarPerfil(ID, solicitud(null, null, "avatar-01", "  Tienda   Pepe ")))
                .isInstanceOf(ConflictoNegocioException.class);
        verify(repositorioUsuario, never()).save(any());
    }

    @Test
    void modificarPerfil_vendedorMismoNombreComercial_noCompruebaUnicidad() {
        existe(Rol.VENDEDOR, "Tienda Ana", "avatar-01");
        guardarDevuelveLoGuardado();

        servicio.modificarPerfil(ID, solicitud(null, null, "avatar-01", "tienda ana"));

        verify(repositorioUsuario, never()).existePorNombreComercial(anyString());
    }
}