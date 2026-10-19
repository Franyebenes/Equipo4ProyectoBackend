package com.esibuy.esibuy_backend.configuracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.esibuy.esibuy_backend.seguridad.PoliticaSesion;
import com.esibuy.esibuy_backend.servicio.ValidadorSesionUsuario;

/**
 * Filtro que invalida las sesiones iniciadas antes de un cambio de contrasena (HdU recuperacion de contrasena, fase 4).
 * Casos: CP-RST-40 a CP-RST-46.
 *
 * Es el mecanismo de "se invalidan todas las sesiones abiertas": en cada peticion con una sesion autenticada pregunta
 * a ValidadorSesionUsuario (mockeado aqui) si sigue valiendo. Si no, invalida la sesion y responde 401 con el mismo
 * cuerpo que la caducidad de sesion. No toca las peticiones sin sesion ni las de sesiones anonimas (por ejemplo, la que
 * solo guarda el token CSRF), que son las del login, el registro y la recuperacion. Si la base de datos falla responde
 * 503 sin invalidar la sesion (un fallo temporal no debe cerrar la sesion de nadie).
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@ExtendWith(MockitoExtension.class)
class FiltroSesionTrasCambioContrasenaTest {

    private static final String ID_USUARIO = "665f1c2e9b1e8a3d4c5b6a79";
    private static final Instant INICIO = Instant.parse("2026-10-19T10:00:00Z");

    @Mock
    private ValidadorSesionUsuario validador;

    private FiltroSesionTrasCambioContrasena filtro;
    private MockHttpServletRequest peticion;
    private MockHttpServletResponse respuesta;
    private MockFilterChain cadena;

    @BeforeEach
    void prepararFiltro() {
        filtro = new FiltroSesionTrasCambioContrasena(validador);
        peticion = new MockHttpServletRequest("GET", "/api/auth/me");
        respuesta = new MockHttpServletResponse();
        cadena = new MockFilterChain();
    }

    private MockHttpSession sesionAutenticada(Instant inicio) {
        MockHttpSession sesion = new MockHttpSession();
        sesion.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(ID_USUARIO, null,
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENTE")))));
        if (inicio != null) {
            sesion.setAttribute(PoliticaSesion.ATRIBUTO_INICIO, inicio);
        }
        peticion.setSession(sesion);
        return sesion;
    }

    private boolean laPeticionLlegoAlResto() {
        return cadena.getRequest() != null;
    }

    @Test
    void filtrar_peticionSinSesion_pasaSinConsultarNada() throws Exception { // CP-RST-40
        filtro.doFilter(peticion, respuesta, cadena);

        assertThat(laPeticionLlegoAlResto()).isTrue();
        verifyNoInteractions(validador);
    }

    @Test
    void filtrar_sesionAnonima_pasaSinConsultarNada() throws Exception { // CP-RST-41
        // Como la que solo guarda el token CSRF durante el login o la recuperacion
        MockHttpSession anonima = new MockHttpSession();
        anonima.setAttribute("csrf", "valor");
        peticion.setSession(anonima);

        filtro.doFilter(peticion, respuesta, cadena);

        assertThat(laPeticionLlegoAlResto()).isTrue();
        assertThat(anonima.isInvalid()).isFalse();
        verifyNoInteractions(validador);
    }

    @Test
    void filtrar_sesionAutenticadaQueSigueValiendo_pasaYNoSeToca() throws Exception { // CP-RST-42
        MockHttpSession sesion = sesionAutenticada(INICIO);
        when(validador.sigueVigente(ID_USUARIO, INICIO)).thenReturn(true);

        filtro.doFilter(peticion, respuesta, cadena);

        assertThat(laPeticionLlegoAlResto()).isTrue();
        assertThat(sesion.isInvalid()).isFalse();
        assertThat(respuesta.getStatus()).isEqualTo(200);
    }

    @Test
    void filtrar_sesionIniciadaAntesDelCambioDeContrasena_seInvalidaYResponde401() throws Exception { // CP-RST-43
        MockHttpSession sesion = sesionAutenticada(INICIO);
        when(validador.sigueVigente(ID_USUARIO, INICIO)).thenReturn(false);

        filtro.doFilter(peticion, respuesta, cadena);

        assertThat(laPeticionLlegoAlResto()).isFalse();
        assertThat(sesion.isInvalid()).isTrue();
        assertThat(respuesta.getStatus()).isEqualTo(401);
        assertThat(respuesta.getContentType()).startsWith("application/json");
        // el mismo cuerpo que la caducidad de sesion: el frontend ya sabe tratarlo
        assertThat(respuesta.getContentAsString()).isEqualTo("{\"mensaje\":\"La sesion ha caducado\"}");
    }

    @Test
    void filtrar_fallaLaBaseDeDatos_responde503SinInvalidarLaSesion() throws Exception { // CP-RST-44
        MockHttpSession sesion = sesionAutenticada(INICIO);
        when(validador.sigueVigente(ID_USUARIO, INICIO)).thenThrow(new IllegalStateException("mongo caido"));

        filtro.doFilter(peticion, respuesta, cadena);

        assertThat(laPeticionLlegoAlResto()).isFalse();
        assertThat(sesion.isInvalid()).isFalse();
        assertThat(respuesta.getStatus()).isEqualTo(503);
        assertThat(respuesta.getContentType()).startsWith("application/json");
        assertThat(respuesta.getContentAsString()).contains("\"mensaje\"").doesNotContain("mongo");
    }

    @Test
    void filtrar_sesionAutenticadaSinInstanteDeInicio_seInvalidaYResponde401() throws Exception { // CP-RST-45
        // Falla cerrado, igual que la politica de sesion
        MockHttpSession sesion = sesionAutenticada(null);

        filtro.doFilter(peticion, respuesta, cadena);

        assertThat(laPeticionLlegoAlResto()).isFalse();
        assertThat(sesion.isInvalid()).isTrue();
        assertThat(respuesta.getStatus()).isEqualTo(401);
    }

    @Test
    void filtrar_consultaConElIdDelPrincipalYElInicioDeLaSesion() throws Exception { // CP-RST-46
        sesionAutenticada(INICIO);
        when(validador.sigueVigente(ID_USUARIO, INICIO)).thenReturn(true);

        filtro.doFilter(peticion, respuesta, cadena);

        verify(validador).sigueVigente(ID_USUARIO, INICIO);
    }
}
