package com.esibuy.esibuy_backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;

import jakarta.servlet.http.HttpSession;

/**
 * Creación y rotación de la sesión tras un login correcto.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 5.
 * Casos: CP-SES-01, CP-SES-02.
 * Colaboradores: MockHttpServletRequest y MockHttpSession; Clock fijo; PoliticaSesion real.
 *
 * Reglas que fijan estas pruebas: la sesión guarda, en el atributo estándar de Spring Security
 * (SPRING_SECURITY_CONTEXT), un contexto con una autenticación ya autenticada cuyo principal es el id del usuario,
 * sin credenciales y con una única autoridad ROLE_xxx; además guarda el instante de inicio y el rol
 * (PoliticaSesion.ATRIBUTO_INICIO y ATRIBUTO_ROL) y la inactividad máxima del rol. Si había sesión previa, se
 * invalida y se crea otra nueva sin ninguno de sus atributos.
 *
 * Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
class EstablecedorSesionTest {

    private static final Instant AHORA = Instant.parse("2026-10-06T10:00:00Z");
    private static final Clock RELOJ_FIJO = Clock.fixed(AHORA, ZoneId.of("Europe/Madrid"));
    private static final String ID_USUARIO = "665f1c2e9b1e8a3d4c5b6a79";
    private static final String EMAIL = "ana.garcia@ejemplo.es";

    private final EstablecedorSesion establecedor = new EstablecedorSesion(new PoliticaSesion(), RELOJ_FIJO);

    private static ResultadoAutenticacion resultadoDe(Rol rol) {
        return new ResultadoAutenticacion(ID_USUARIO, EMAIL, "Ana", rol);
    }

    private static SecurityContext contextoDe(HttpSession sesion) {
        return (SecurityContext) sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    }

    // ------------------------------------------------------------------ CP-SES-01

    static Stream<Arguments> inactividadPorRol() {
        return Stream.of(
                arguments(Rol.CLIENTE, 20 * 60),
                arguments(Rol.PREMIUM, 20 * 60),
                arguments(Rol.VENDEDOR, 15 * 60),
                arguments(Rol.ADMIN, 15 * 60));
    }

    @ParameterizedTest(name = "{0}: inactividad máxima de {1} s")
    @MethodSource("inactividadPorRol")
    void establecerSesion_loginCorrectoDeCadaRol_creaContextoAutenticadoConLaPoliticaDelRol( // CP-SES-01
            Rol rol, int segundosDeInactividad) {
        // Given: una petición sin sesión previa
        MockHttpServletRequest peticion = new MockHttpServletRequest();

        // When
        establecedor.establecer(peticion, resultadoDe(rol));

        // Then: hay sesión, con contexto autenticado, una única autoridad ROLE_xxx y sin credenciales
        HttpSession sesion = peticion.getSession(false);
        assertThat(sesion).isNotNull();
        Authentication autenticacion = contextoDe(sesion).getAuthentication();
        assertThat(autenticacion.isAuthenticated()).isTrue();
        assertThat(autenticacion.getName()).isEqualTo(ID_USUARIO);
        assertThat(autenticacion.getCredentials()).isNull();
        assertThat(autenticacion.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_" + rol.name());

        // Then: inactividad máxima del rol, instante de inicio con la hora del reloj y rol guardado
        assertThat(sesion.getMaxInactiveInterval()).isEqualTo(segundosDeInactividad);
        assertThat(sesion.getAttribute(PoliticaSesion.ATRIBUTO_INICIO)).isEqualTo(AHORA);
        assertThat(sesion.getAttribute(PoliticaSesion.ATRIBUTO_ROL)).isEqualTo(rol);
    }

    // ------------------------------------------------------------------ CP-SES-02

    private static MockHttpSession sesionAnonima() {
        MockHttpSession sesion = new MockHttpSession();
        sesion.setAttribute("carrito", "datos de la sesión anterior");
        return sesion;
    }

    private static MockHttpSession sesionDeOtroUsuario() {
        MockHttpSession sesion = sesionAnonima();
        Authentication otroUsuario = UsernamePasswordAuthenticationToken.authenticated(
                "id-de-otro-usuario", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        sesion.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(otroUsuario));
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_ROL, Rol.ADMIN);
        return sesion;
    }

    static Stream<Arguments> sesionesPrevias() {
        return Stream.of(
                arguments("sesión previa anónima", sesionAnonima()),
                arguments("sesión previa de otro usuario", sesionDeOtroUsuario()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sesionesPrevias")
    void establecerSesion_existiaSesionPreviaAnonimaODeOtroUsuario_rotaElIdentificador( // CP-SES-02
            String descripcion, MockHttpSession sesionPrevia) {
        // Given: la petición llega con una sesión que ya existía
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setSession(sesionPrevia);
        String idAnterior = sesionPrevia.getId();

        // When
        establecedor.establecer(peticion, resultadoDe(Rol.CLIENTE));

        // Then: identificador distinto, sesión anterior invalidada y ningún atributo heredado
        HttpSession sesionNueva = peticion.getSession(false);
        assertThat(sesionNueva).isNotNull();
        assertThat(sesionNueva.getId()).isNotEqualTo(idAnterior);
        assertThat(sesionPrevia.isInvalid()).isTrue();
        assertThat(sesionNueva.getAttribute("carrito")).isNull();

        // Then: la sesión nueva pertenece al usuario que acaba de entrar y con su rol, no con el de la anterior
        assertThat(contextoDe(sesionNueva).getAuthentication().getName()).isEqualTo(ID_USUARIO);
        assertThat(sesionNueva.getAttribute(PoliticaSesion.ATRIBUTO_ROL)).isEqualTo(Rol.CLIENTE);
    }

    @Test
    void establecerSesion_dosLoginsSeguidos_cadaUnoUsaUnaSesionDistinta() { // CP-SES-02
        // Given: un primer login en la misma petición
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        establecedor.establecer(peticion, resultadoDe(Rol.CLIENTE));
        HttpSession primera = peticion.getSession(false);

        // When: un segundo login con la sesión creada por el primero
        establecedor.establecer(peticion, resultadoDe(Rol.VENDEDOR));

        // Then: el identificador cambia otra vez y la primera sesión queda invalidada
        HttpSession segunda = peticion.getSession(false);
        assertThat(segunda.getId()).isNotEqualTo(primera.getId());
        assertThat(((MockHttpSession) primera).isInvalid()).isTrue();
    }
}
