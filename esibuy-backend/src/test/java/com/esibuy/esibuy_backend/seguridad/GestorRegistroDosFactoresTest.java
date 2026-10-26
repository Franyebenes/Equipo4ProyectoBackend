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
 * Secreto del 2FA pendiente durante el registro (HdU 2FA en el registro, fase A). Casos: CP-2FA-377 a CP-2FA-396.
 *
 * Al registrarse, la persona escanea un QR ANTES de que exista su cuenta, asi que el secreto no puede ir a la base de datos:
 * se guarda cifrado en la sesion HTTP (en memoria, sin tablas ni campos nuevos) junto con el correo para el que se genero.
 * El registro final (fase B) lo recupera, comprueba el codigo y crea la cuenta ya con el 2FA activo.
 *
 * Reglas que fijan:
 *  - Solo hay un secreto pendiente por sesion: pedir otro QR sustituye al anterior y reinicia los intentos.
 *  - Caduca a los 10 minutos exactos desde que se genero (los codigos fallidos no lo alargan) y se descarta al llegar al
 *    maximo de codigos fallidos (5).
 *  - Esta ligado al correo: si la persona cambia el correo entre los dos pasos, no vale (hay que pedir otro QR).
 *  - Lo que se guarda es el texto cifrado, nunca el secreto en claro, y no aparece en el toString.
 *  - No concede nada: no toca el contexto de seguridad ni los atributos de una sesion autenticada. A diferencia del login,
 *    se puede usar tambien en una sesion que ya esta autenticada (alguien con sesion puede registrar otra cuenta) sin
 *    invalidarla. Una sesion anonima existente (la del token CSRF) se reutiliza.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class GestorRegistroDosFactoresTest {

    private static final String EMAIL = "lucia.torres@ejemplo.es";
    private static final String CIFRADO = "1:" + "A".repeat(64);
    private static final Instant AHORA = Instant.parse("2026-10-21T10:00:00Z");

    private RelojMutable reloj;
    private GestorRegistroDosFactores gestor;
    private MockHttpServletRequest peticion;

    @BeforeEach
    void prepararGestor() {
        reloj = new RelojMutable(AHORA, ZoneId.of("Europe/Madrid"));
        gestor = new GestorRegistroDosFactores(Duration.ofMinutes(10), 5, reloj);
        peticion = new MockHttpServletRequest();
    }

    // ------------------------------------------------------------------ iniciar y consultar

    @Test
    void iniciar_guardaElCorreoElSecretoCifradoLaFechaYSinIntentosFallidos() { // CP-2FA-377
        gestor.iniciar(peticion, EMAIL, CIFRADO);

        Optional<PendienteRegistroDosFactores> pendiente = gestor.vigente(peticion);

        assertThat(pendiente).isPresent();
        assertThat(pendiente.get().email()).isEqualTo(EMAIL);
        assertThat(pendiente.get().secretoCifrado()).isEqualTo(CIFRADO);
        assertThat(pendiente.get().creadoEn()).isEqualTo(AHORA);
        assertThat(pendiente.get().intentosFallidos()).isZero();
    }

    @Test
    void iniciar_sinSesionPrevia_creaUna() { // CP-2FA-378
        assertThat(peticion.getSession(false)).isNull();

        gestor.iniciar(peticion, EMAIL, CIFRADO);

        assertThat(peticion.getSession(false)).isNotNull();
    }

    @Test
    void iniciar_reutilizaUnaSesionAnonimaExistenteYConservaSusAtributos() { // CP-2FA-379
        // La sesion anonima guarda el token CSRF, que debe seguir valiendo en el registro final
        MockHttpSession anonima = new MockHttpSession();
        anonima.setAttribute("token-csrf", "valor");
        peticion.setSession(anonima);

        gestor.iniciar(peticion, EMAIL, CIFRADO);

        assertThat(peticion.getSession(false)).isSameAs(anonima);
        assertThat(anonima.isInvalid()).isFalse();
        assertThat(anonima.getAttribute("token-csrf")).isEqualTo("valor");
        assertThat(gestor.vigente(peticion)).isPresent();
    }

    @Test
    void iniciar_conUnaSesionAutenticada_noLaInvalidaNiLaToca() { // CP-2FA-380
        MockHttpSession autenticada = new MockHttpSession();
        ResultadoAutenticacion usuario = new ResultadoAutenticacion("1", "otro@ejemplo.es", "Otro", Rol.ADMIN);
        autenticada.setAttribute(EstablecedorSesion.ATRIBUTO_USUARIO, usuario);
        peticion.setSession(autenticada);
        int inactividadAntes = autenticada.getMaxInactiveInterval();

        gestor.iniciar(peticion, EMAIL, CIFRADO);

        // Quien tiene su sesion abierta puede registrar otra cuenta: su sesion sigue intacta
        assertThat(autenticada.isInvalid()).isFalse();
        assertThat(autenticada.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isEqualTo(usuario);
        assertThat(autenticada.getMaxInactiveInterval()).isEqualTo(inactividadAntes);
        assertThat(gestor.vigente(peticion)).isPresent();
    }

    @Test
    void iniciar_noConcedeNada_soloAnadeElAtributoDelPendiente() { // CP-2FA-381
        gestor.iniciar(peticion, EMAIL, CIFRADO);

        MockHttpSession sesion = (MockHttpSession) peticion.getSession(false);
        assertThat(sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(sesion.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isNull();
        assertThat(Collections.list(sesion.getAttributeNames())).containsExactly(GestorRegistroDosFactores.ATRIBUTO_PENDIENTE);
    }

    @Test
    void iniciar_denuevo_sustituyeElSecretoYReiniciaLosIntentos() { // CP-2FA-382
        gestor.iniciar(peticion, EMAIL, CIFRADO);
        gestor.registrarFallo(peticion);
        gestor.registrarFallo(peticion);
        reloj.avanzar(Duration.ofMinutes(3));
        String otroCifrado = "1:" + "B".repeat(64);

        // La persona pide otro QR (por ejemplo, porque cambio el correo)
        gestor.iniciar(peticion, "otro@ejemplo.es", otroCifrado);

        PendienteRegistroDosFactores pendiente = gestor.vigente(peticion).orElseThrow();
        assertThat(pendiente.email()).isEqualTo("otro@ejemplo.es");
        assertThat(pendiente.secretoCifrado()).isEqualTo(otroCifrado);
        assertThat(pendiente.intentosFallidos()).isZero();
        assertThat(pendiente.creadoEn()).isEqualTo(AHORA.plus(Duration.ofMinutes(3)));
    }

    // ------------------------------------------------------------------ vigente

    @Test
    void vigente_sinSesionOSinPendiente_estaVacioYNoCreaSesion() { // CP-2FA-383
        assertThat(gestor.vigente(peticion)).isEmpty();
        assertThat(peticion.getSession(false)).isNull();

        peticion.getSession(true);
        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    @Test
    void vigente_unAtributoQueNoEsUnPendiente_estaVacio() { // CP-2FA-384
        peticion.getSession(true).setAttribute(GestorRegistroDosFactores.ATRIBUTO_PENDIENTE, "no soy un pendiente");

        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    @Test
    void vigente_caducaALosDiezMinutosExactos() { // CP-2FA-385
        gestor.iniciar(peticion, EMAIL, CIFRADO);

        reloj.avanzar(Duration.ofMinutes(9).plusSeconds(59));
        assertThat(gestor.vigente(peticion)).isPresent();

        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    @Test
    void vigente_alCaducarSeEliminaElPendienteDeLaSesion() { // CP-2FA-386
        gestor.iniciar(peticion, EMAIL, CIFRADO);
        reloj.avanzar(Duration.ofMinutes(11));

        gestor.vigente(peticion);

        assertThat(peticion.getSession(false).getAttribute(GestorRegistroDosFactores.ATRIBUTO_PENDIENTE)).isNull();
    }

    // ------------------------------------------------------------------ fallos

    @Test
    void registrarFallo_sumaUnIntentoCadaVezYNoAgotaHastaElQuinto() { // CP-2FA-387
        gestor.iniciar(peticion, EMAIL, CIFRADO);

        for (int i = 1; i <= 4; i++) {
            assertThat(gestor.registrarFallo(peticion)).as("fallo %d", i).isFalse();
            assertThat(gestor.vigente(peticion).orElseThrow().intentosFallidos()).isEqualTo(i);
        }
    }

    @Test
    void registrarFallo_alQuintoAgotaElPendienteYLoDescarta() { // CP-2FA-388
        gestor.iniciar(peticion, EMAIL, CIFRADO);
        for (int i = 1; i <= 4; i++) {
            gestor.registrarFallo(peticion);
        }

        boolean agotado = gestor.registrarFallo(peticion);

        assertThat(agotado).isTrue();
        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    @Test
    void registrarFallo_sinPendiente_noHaceNadaNiLanzaExcepcion() { // CP-2FA-389
        assertThat(gestor.registrarFallo(peticion)).isFalse();

        peticion.getSession(true);
        assertThat(gestor.registrarFallo(peticion)).isFalse();
    }

    @Test
    void registrarFallo_noReiniciaLaCaducidad() { // CP-2FA-390
        gestor.iniciar(peticion, EMAIL, CIFRADO);
        reloj.avanzar(Duration.ofMinutes(9));
        gestor.registrarFallo(peticion);

        assertThat(gestor.vigente(peticion).orElseThrow().creadoEn()).isEqualTo(AHORA);
        reloj.avanzar(Duration.ofMinutes(1));
        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    // ------------------------------------------------------------------ descartar

    @Test
    void descartar_eliminaElPendienteYEsIdempotente() { // CP-2FA-391
        gestor.iniciar(peticion, EMAIL, CIFRADO);

        gestor.descartar(peticion);
        gestor.descartar(peticion);

        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    @Test
    void descartar_sinSesion_noLanzaExcepcionNiLaCrea() { // CP-2FA-392
        gestor.descartar(peticion);

        assertThat(peticion.getSession(false)).isNull();
    }

    // ------------------------------------------------------------------ el pendiente

    @Test
    void esDe_soloCoincideConElCorreoParaElQueSeGenero() { // CP-2FA-393
        PendienteRegistroDosFactores pendiente = new PendienteRegistroDosFactores(EMAIL, CIFRADO, AHORA, 0);

        assertThat(pendiente.esDe(EMAIL)).isTrue();
        assertThat(pendiente.esDe("otro@ejemplo.es")).isFalse();
        assertThat(pendiente.esDe("LUCIA.TORRES@ejemplo.es")).isFalse(); // se compara el correo ya normalizado
        assertThat(pendiente.esDe(null)).isFalse();
        assertThat(pendiente.esDe("")).isFalse();
    }

    @Test
    void pendiente_esInmutableYSerializable() throws Exception { // CP-2FA-394
        PendienteRegistroDosFactores original = new PendienteRegistroDosFactores(EMAIL, CIFRADO, AHORA, 0);

        PendienteRegistroDosFactores conFallo = original.conUnFallo();

        assertThat(original.intentosFallidos()).isZero();
        assertThat(conFallo.intentosFallidos()).isEqualTo(1);
        assertThat(conFallo.email()).isEqualTo(EMAIL);
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
    void pendiente_elTextoNoMuestraElSecretoCifrado() { // CP-2FA-395
        String texto = new PendienteRegistroDosFactores(EMAIL, CIFRADO, AHORA, 2).toString();

        assertThat(texto).doesNotContain(CIFRADO).doesNotContain("AAAAAAAA");
    }

    @ParameterizedTest(name = "maximo de {0} intentos")
    @ValueSource(ints = { 1, 3, 5, 10 })
    void registrarFallo_agotaAlLlegarAlMaximoConfigurado(int maximo) { // CP-2FA-396
        GestorRegistroDosFactores conMaximo = new GestorRegistroDosFactores(Duration.ofMinutes(10), maximo, reloj);
        conMaximo.iniciar(peticion, EMAIL, CIFRADO);

        for (int i = 1; i < maximo; i++) {
            assertThat(conMaximo.registrarFallo(peticion)).isFalse();
        }
        assertThat(conMaximo.registrarFallo(peticion)).isTrue();
        assertThat(conMaximo.vigente(peticion)).isEmpty();
    }

    @Test
    void crear_conValoresAbsurdos_lanzaExcepcion() { // CP-2FA-396
        assertThrows(IllegalArgumentException.class, () -> new GestorRegistroDosFactores(null, 5, reloj));
        assertThrows(IllegalArgumentException.class, () -> new GestorRegistroDosFactores(Duration.ZERO, 5, reloj));
        assertThrows(IllegalArgumentException.class, () -> new GestorRegistroDosFactores(Duration.ofSeconds(-1), 5, reloj));
        assertThrows(IllegalArgumentException.class, () -> new GestorRegistroDosFactores(Duration.ofMinutes(10), 0, reloj));
        assertThrows(IllegalArgumentException.class, () -> new GestorRegistroDosFactores(Duration.ofMinutes(10), 5, null));
    }
}
