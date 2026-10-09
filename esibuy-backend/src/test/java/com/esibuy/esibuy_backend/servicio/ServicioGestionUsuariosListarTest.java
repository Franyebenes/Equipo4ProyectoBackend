package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.query.Query;

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.UsuarioDTO;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;

/** Pruebas de listar, filtrar y buscar usuarios. */
class ServicioGestionUsuariosListarTest extends ServicioGestionUsuariosBase {

    private String consultaEnviadaAMongo() {
        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(consulta.capture(), eq(Usuario.class));
        return consulta.getValue().getQueryObject().toJson();
    }

    @Test
    void listar_tamanoDemasiadoGrande_seLimitaAlMaximoYPaginaNegativaEmpiezaEnCero() {
        // Given
        Usuario usuario = usuario(ID_USUARIO, Rol.CLIENTE, EstadoUsuario.ACTIVO);
        when(mongoTemplate.find(any(Query.class), eq(Usuario.class))).thenReturn(List.of(usuario));

        // When
        PaginaDTO<UsuarioDTO> pagina = servicio.listar(-5, 1000, null, null, null);

        // Then
        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(consulta.capture(), eq(Usuario.class));
        assertThat(consulta.getValue().getLimit()).isEqualTo(ServicioGestionUsuarios.TAMANO_MAXIMO_PAGINA);
        assertThat(consulta.getValue().getSkip()).isZero();
        assertThat(pagina.contenido()).hasSize(1);
        assertThat(pagina.contenido().get(0).email()).isEqualTo(EMAIL_USUARIO);
    }

    @Test
    void listar_conFiltroDeRolYEstado_aplicaAmbosFiltros() {
        // Given
        when(mongoTemplate.find(any(Query.class), eq(Usuario.class))).thenReturn(List.of());

        // When
        servicio.listar(0, 20, Rol.VENDEDOR, EstadoUsuario.BLOQUEADO, null);

        // Then
        assertThat(consultaEnviadaAMongo()).contains("VENDEDOR").contains("BLOQUEADO");
    }

    @Test
    void listar_sinFiltroDeEstado_excluyeLosEliminados() {
        // Given
        when(mongoTemplate.find(any(Query.class), eq(Usuario.class))).thenReturn(List.of());

        // When
        servicio.listar(0, 20, null, null, null);

        // Then
        assertThat(consultaEnviadaAMongo()).contains("$ne").contains("ELIMINADO");
    }

    @Test
    void listar_conBusqueda_buscaEnNombreApellidosYEmail() {
        // Given
        when(mongoTemplate.find(any(Query.class), eq(Usuario.class))).thenReturn(List.of());

        // When
        servicio.listar(0, 20, null, null, "ana lopez");

        // Then
        assertThat(consultaEnviadaAMongo())
                .contains("perfil.nombre").contains("perfil.apellidos").contains("ana").contains("lopez");
    }
}