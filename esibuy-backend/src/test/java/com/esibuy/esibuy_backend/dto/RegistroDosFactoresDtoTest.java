package com.esibuy.esibuy_backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * DTO del registro con 2FA (HdU 2FA en el registro, fase B). Casos: CP-2FA-499 a CP-2FA-504.
 *
 *  - Las solicitudes de cliente y de vendedor llevan "codigoDosFactores" (el primer codigo de la aplicacion autenticadora); el
 *    constructor anterior, sin el, sigue existiendo y lo deja a null. El toString lo oculta, como las contrasenas.
 *  - La respuesta del registro con 2FA son los campos de siempre mas "codigosRecuperacion"; su toString no incluye los codigos.
 */
class RegistroDosFactoresDtoTest {

    private static final String CODIGO = "654321";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void laSolicitudDeClienteOcultaElCodigoEnElToString() { // CP-2FA-499
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().conCodigoDosFactores(CODIGO).construir();

        assertThat(solicitud.toString()).contains("codigoDosFactores=[OCULTO]").doesNotContain(CODIGO)
                .doesNotContain(ConstructorSolicitudCliente.CONTRASENA_VALIDA);
    }

    @Test
    void laSolicitudDeVendedorOcultaElCodigoEnElToString() { // CP-2FA-500
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida().conCodigoDosFactores(CODIGO).construir();

        assertThat(solicitud.toString()).contains("codigoDosFactores=[OCULTO]").doesNotContain(CODIGO);
    }

    @Test
    void elConstructorSinCodigoLoDejaANulo() { // CP-2FA-501
        SolicitudRegistroClienteDTO cliente = new SolicitudRegistroClienteDTO(TipoCuenta.CLIENTE, "Ana", "Garcia", null, "12345678Z",
                "ana@ejemplo.es", "612345678", null, "x", "x");
        SolicitudRegistroVendedorDTO vendedor = new SolicitudRegistroVendedorDTO(TipoCuenta.VENDEDOR, "Luis", "Perez", "cat-1",
                "Tienda", "12345678Z", "luis@ejemplo.es", "612345678", null, "x", "x");

        assertThat(cliente.codigoDosFactores()).isNull();
        assertThat(vendedor.codigoDosFactores()).isNull();
    }

    @Test
    void elJsonDelRegistroLeeElCodigoParaClientesYVendedores() { // CP-2FA-502
        String cliente = objectMapper.writeValueAsString(ConstructorSolicitudCliente.unaSolicitudValida().conCodigoDosFactores(CODIGO).construir());
        String vendedor = objectMapper.writeValueAsString(ConstructorSolicitudVendedor.unaSolicitudValida().conCodigoDosFactores(CODIGO).construir());

        assertThat(objectMapper.readValue(cliente, SolicitudRegistro.class).codigoDosFactores()).isEqualTo(CODIGO);
        assertThat(objectMapper.readValue(vendedor, SolicitudRegistro.class).codigoDosFactores()).isEqualTo(CODIGO);
    }

    @Test
    void laRespuestaLlevaLosCamposDeSiempreMasLosCodigosDeRecuperacion() { // CP-2FA-503
        RespuestaRegistroDosFactoresDTO respuesta = new RespuestaRegistroDosFactoresDTO("id-1", "ana@ejemplo.es", "Ana",
                "Cuenta creada", List.of("ABCD-EFGH-IJKL-MNOP"));

        Map<String, Object> json = objectMapper.convertValue(respuesta, new TypeReference<Map<String, Object>>() { });

        assertThat(json).containsOnlyKeys("id", "email", "nombre", "mensaje", "codigosRecuperacion");
        assertThat(json.get("codigosRecuperacion")).isEqualTo(List.of("ABCD-EFGH-IJKL-MNOP"));
    }

    @Test
    void laRespuestaOcultaLosCodigosEnElToString() { // CP-2FA-504
        RespuestaRegistroDosFactoresDTO respuesta = new RespuestaRegistroDosFactoresDTO("id-1", "ana@ejemplo.es", "Ana",
                "Cuenta creada", List.of("ABCD-EFGH-IJKL-MNOP"));

        assertThat(respuesta.toString()).contains("codigosRecuperacion=[OCULTO]").doesNotContain("ABCD");
    }
}
