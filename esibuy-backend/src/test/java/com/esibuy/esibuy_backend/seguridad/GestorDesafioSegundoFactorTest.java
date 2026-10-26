package com.esibuy.esibuy_backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;
import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * Desafio pendiente entre el primer paso del login (contrasena correcta) y el segundo (codigo) (HdU 2FA, fase 3).
 * Casos: CP-2FA-292 a CP-2FA-311.
 *
 * El desafio vive en la sesion HTTP, en memoria como el resto de la sesion: sin tablas ni campos nuevos en la base de datos.
 * Lo esencial es que NO concede nada: la sesion con un desafio pendiente no tiene contexto de seguridad ni los atributos
 * de una sesion autenticada, asi que para el resto de la aplicacion sigue siendo anonima (401 en /me y en todo lo
 * protegido). Caduca a los 5 minutos exactos y se descarta al quinto codigo fallido. Si el primer paso se repite, el desafio
 * se sustituye y los intentos vuelven a cero: lo acota el limite por cuenta del segundo factor (10 fallos en 15 minutos),
 * que no depende de la sesion.
 *
 * Una sesion anonima ya existente (la que guarda el token CSRF) se reutiliza, para que el token siga valiendo en el segundo
 * paso; una autenticada (de otro usuario) se invalida y se crea otra. La sesion definitiva la crea EstablecedorSesion al
 * acertar el codigo, que ya invalida la anterior y evita la fijacion de sesion.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class GestorDesafioSegundoFactorTest {

    private static final ResultadoAutenticacion USUARIO = new ResultadoAutenticacion(
            "665f1c2e9b1e8a3d4c5b6a79", "ana.garcia@ejemplo.es", "Ana", Rol.CLIENTE, null, true);
    private static final Instant AHORA = Instant.parse("2026-10-20T10:00:00Z");

    private RelojMutable reloj;
    private GestorDesafioSegundoFactor gestor;
    private MockHttpServletRequest peticion;

    @BeforeEach
    void prepararGestor() {
        reloj = new RelojMutable(AHORA, ZoneId.of("Europe/Madrid"));
        gestor = new GestorDesafioSegundoFactor(Duration.ofMinutes(5), 5, reloj);
        peticion = new MockHttpServletRequest();
    }

    // ------------------------------------------------------------------ iniciar y consultar

    @Test
    void iniciar_guardaElDesafioConElUsuarioLaFechaYSinIntentosFallidos() { // CP-2FA-292
        gestor.iniciar(peticion, USUARIO);

        Optional<DesafioSegundoFactor> desafio = gestor.vigente(peticion);

        assertThat(desafio).isPresent();
        assertThat(desafio.get().usuario()).isEqualTo(USUARIO);
        assertThat(desafio.get().creadoEn()).isEqualTo(AHORA);
        assertThat(desafio.get().intentosFallidos()).isZero();
    }

    @Test
    void iniciar_noConcedeNada_laSesionSigueSiendoAnonima() { // CP-2FA-293
        gestor.iniciar(peticion, USUARIO);

        MockHttpSession sesion = (MockHttpSession) peticion.getSession(false);
        // Sin contexto de seguridad ni los atributos de una sesion autenticada: /me da 401 y lo protegido tambien
        assertThat(sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(sesion.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isNull();
        assertThat(sesion.getAttribute(PoliticaSesion.ATRIBUTO_INICIO)).isNull();
        assertThat(sesion.getAttribute(PoliticaSesion.ATRIBUTO_ROL)).isNull();
        assertThat(Collections.list(sesion.getAttributeNames())).containsExactly(GestorDesafioSegundoFactor.ATRIBUTO_DESAFIO);
    }

    @Test
    void iniciar_laInactividadMaximaDeLaSesionEsLaCaducidadDelDesafio() { // CP-2FA-294
        gestor.iniciar(peticion, USUARIO);

        assertThat(peticion.getSession(false).getMaxInactiveInterval()).isEqualTo(300);
    }

    @Test
    void iniciar_reutilizaUnaSesionAnonimaExistenteYConservaSusAtributos() { // CP-2FA-295
        // La sesion anonima guarda el token CSRF, que debe seguir valiendo en el segundo paso
        MockHttpSession anonima = new MockHttpSession();
        anonima.setAttribute("token-csrf", "valor");
        peticion.setSession(anonima);

        gestor.iniciar(peticion, USUARIO);

        assertThat(peticion.getSession(false)).isSameAs(anonima);
        assertThat(anonima.isInvalid()).isFalse();
        assertThat(anonima.getAttribute("token-csrf")).isEqualTo("valor");
        assertThat(gestor.vigente(peticion)).isPresent();
    }

    @Test
    void iniciar_conUnaSesionAutenticadaLaInvalidaYCreaOtraLimpia() { // CP-2FA-296
        // Alguien con la sesion de OTRO usuario abierta inicia sesion con una cuenta con 2FA
        MockHttpSession autenticada = new MockHttpSession();
        autenticada.setAttribute(EstablecedorSesion.ATRIBUTO_USUARIO, new ResultadoAutenticacion("otro", "otro@ejemplo.es", "Otro", Rol.ADMIN));
        autenticada.setAttribute("residuo", "no debe pasar");
        peticion.setSession(autenticada);

        gestor.iniciar(peticion, USUARIO);

        assertThat(autenticada.isInvalid()).isTrue();
        MockHttpSession nueva = (MockHttpSession) peticion.getSession(false);
        assertThat(nueva).isNotSameAs(autenticada);
        assertThat(nueva.getAttribute("residuo")).isNull();
        assertThat(nueva.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isNull();
        assertThat(gestor.vigente(peticion)).isPresent();
    }

    @Test
    void iniciar_sinSesionPrevia_creaUna() { // CP-2FA-297
        assertThat(peticion.getSession(false)).isNull();

        gestor.iniciar(peticion, USUARIO);

        assertThat(peticion.getSession(false)).isNotNull();
    }

    @Test
    void iniciar_deNuevo_sustituyeElDesafioYReiniciaLosIntentos() { // CP-2FA-298
        gestor.iniciar(peticion, USUARIO);
        gestor.registrarFallo(peticion);
        gestor.registrarFallo(peticion);
        reloj.avanzar(Duration.ofMinutes(2));
        ResultadoAutenticacion otro = new ResultadoAutenticacion("665f1c2e9b1e8a3d4c5b6a80", "luis@ejemplo.es", "Luis", Rol.VENDEDOR, null, true);

        gestor.iniciar(peticion, otro);

        DesafioSegundoFactor desafio = gestor.vigente(peticion).orElseThrow();
        assertThat(desafio.usuario()).isEqualTo(otro);
        assertThat(desafio.intentosFallidos()).isZero();
        assertThat(desafio.creadoEn()).isEqualTo(AHORA.plus(Duration.ofMinutes(2)));
    }

    // ------------------------------------------------------------------ vigente

    @Test
    void vigente_sinSesionOSinDesafio_estaVacio() { // CP-2FA-299
        assertThat(gestor.vigente(peticion)).isEmpty(); // sin sesion

        peticion.getSession(true);
        assertThat(gestor.vigente(peticion)).isEmpty(); // sesion sin desafio
        assertThat(peticion.getSession(false)).isNotNull(); // y no la crea ni la toca
    }

    @Test
    void vigente_unAtributoQueNoEsUnDesafio_estaVacio() { // CP-2FA-300
        peticion.getSession(true).setAttribute(GestorDesafioSegundoFactor.ATRIBUTO_DESAFIO, "no soy un desafio");

        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    @Test
    void vigente_caducaALosCincoMinutosExactos() { // CP-2FA-301
        gestor.iniciar(peticion, USUARIO);

        reloj.avanzar(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(gestor.vigente(peticion)).isPresent();

        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    @Test
    void vigente_alCaducarSeEliminaElDesafioDeLaSesion() { // CP-2FA-302
        gestor.iniciar(peticion, USUARIO);
        reloj.avanzar(Duration.ofMinutes(6));

        gestor.vigente(peticion);

        assertThat(peticion.getSession(false).getAttribute(GestorDesafioSegundoFactor.ATRIBUTO_DESAFIO)).isNull();
    }

    // ------------------------------------------------------------------ fallos

    @Test
    void registrarFallo_sumaUnIntentoCadaVezYNoAgotaHastaElQuinto() { // CP-2FA-303
        gestor.iniciar(peticion, USUARIO);

        for (int i = 1; i <= 4; i++) {
            assertThat(gestor.registrarFallo(peticion)).as("fallo %d", i).isFalse();
            assertThat(gestor.vigente(peticion).orElseThrow().intentosFallidos()).isEqualTo(i);
        }
    }

    @Test
    void registrarFallo_alQuintoAgotaElDesafioYLoDescarta() { // CP-2FA-304
        gestor.iniciar(peticion, USUARIO);
        for (int i = 1; i <= 4; i++) {
            gestor.registrarFallo(peticion);
        }

        boolean agotado = gestor.registrarFallo(peticion);

        assertThat(agotado).isTrue();
        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    @Test
    void registrarFallo_sinDesafio_noHaceNadaNiLanzaExcepcion() { // CP-2FA-305
        assertThat(gestor.registrarFallo(peticion)).isFalse();

        peticion.getSession(true);
        assertThat(gestor.registrarFallo(peticion)).isFalse();
    }

    @Test
    void registrarFallo_noReiniciaLaCaducidadDelDesafio() { // CP-2FA-306
        gestor.iniciar(peticion, USUARIO);
        reloj.avanzar(Duration.ofMinutes(4));
        gestor.registrarFallo(peticion);

        // Seguir fallando no lo alarga: cuenta desde que se creo
        assertThat(gestor.vigente(peticion).orElseThrow().creadoEn()).isEqualTo(AHORA);
        reloj.avanzar(Duration.ofMinutes(1));
        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    // ------------------------------------------------------------------ descartar

    @Test
    void descartar_eliminaElDesafioYEsIdempotente() { // CP-2FA-307
        gestor.iniciar(peticion, USUARIO);

        gestor.descartar(peticion);
        gestor.descartar(peticion);

        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    @Test
    void descartar_sinSesion_noLanzaExcepcionNiLaCrea() { // CP-2FA-308
        gestor.descartar(peticion);

        assertThat(peticion.getSession(false)).isNull();
    }

    // ------------------------------------------------------------------ el desafio y el gestor

    @Test
    void desafio_esInmutableYSerializable() throws Exception { // CP-2FA-309
        DesafioSegundoFactor original = new DesafioSegundoFactor(USUARIO, AHORA, 0);

        DesafioSegundoFactor conFallo = original.conUnFallo();

        assertThat(original.intentosFallidos()).isZero();
        assertThat(conFallo.intentosFallidos()).isEqualTo(1);
        assertThat(conFallo.usuario()).isEqualTo(USUARIO);
        assertThat(conFallo.creadoEn()).isEqualTo(AHORA);
        // La sesion puede persistirse al reiniciar el contenedor
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream salida = new ObjectOutputStream(bytes)) {
            salida.writeObject(conFallo);
        }
        try (ObjectInputStream entrada = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(entrada.readObject()).isEqualTo(conFallo);
        }
    }

    @Test
    void crear_conValoresAbsurdos_lanzaExcepcion() { // CP-2FA-310
        assertThrows(IllegalArgumentException.class, () -> new GestorDesafioSegundoFactor(null, 5, reloj));
        assertThrows(IllegalArgumentException.class, () -> new GestorDesafioSegundoFactor(Duration.ZERO, 5, reloj));
        assertThrows(IllegalArgumentException.class, () -> new GestorDesafioSegundoFactor(Duration.ofSeconds(-1), 5, reloj));
        assertThrows(IllegalArgumentException.class, () -> new GestorDesafioSegundoFactor(Duration.ofMinutes(5), 0, reloj));
        assertThrows(IllegalArgumentException.class, () -> new GestorDesafioSegundoFactor(Duration.ofMinutes(5), 5, null));
    }

    @ParameterizedTest(name = "maximo de {0} intentos")
    @ValueSource(ints = { 1, 3, 5, 10 })
    void registrarFallo_agotaAlLlegarAlMaximoConfigurado(int maximo) { // CP-2FA-311
        GestorDesafioSegundoFactor conMaximo = new GestorDesafioSegundoFactor(Duration.ofMinutes(5), maximo, reloj);
        conMaximo.iniciar(peticion, USUARIO);

        for (int i = 1; i < maximo; i++) {
            assertThat(conMaximo.registrarFallo(peticion)).isFalse();
        }
        assertThat(conMaximo.registrarFallo(peticion)).isTrue();
        assertThat(conMaximo.vigente(peticion)).isEmpty();
    }
}
