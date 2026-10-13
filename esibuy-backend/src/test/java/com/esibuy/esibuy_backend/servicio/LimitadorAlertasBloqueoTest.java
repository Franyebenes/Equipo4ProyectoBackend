package com.esibuy.esibuy_backend.servicio;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * Aviso de seguridad cuando el limitador bloquea una cuenta.
 *
 * Orden TDD: paso 4 (se conecta sobre los pasos 2 y 3).
 * Casos: CP-AUD-03 (la parte del bloqueo de cuenta; el aviso de la IP en modo adaptativo ya lo comprueba
 * LimitadorIntentosLoginTest y cómo se escribe el aviso lo comprueba AuditoriaSeguridadTest).
 * Colaboradores: AlertasSeguridad y Pausador mockeados; reloj mutable.
 *
 * Clase añadida en la fase 4 porque LimitadorIntentosLoginTest está cerrado: AuditoriaSeguridad solo es útil si el
 * limitador la avisa al activar un bloqueo.
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
@ExtendWith(MockitoExtension.class)
class LimitadorAlertasBloqueoTest {

    private static final String CORREO = "ana.garcia@ejemplo.es";
    private static final String IP = "203.0.113.7";

    @Mock
    private Pausador pausador;
    @Mock
    private AlertasSeguridad alertas;

    private RelojMutable reloj;
    private LimitadorIntentosLogin limitador;

    @BeforeEach
    void prepararLimitador() {
        reloj = new RelojMutable(Instant.parse("2026-10-06T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        limitador = new LimitadorIntentosLogin(reloj, pausador, alertas);
    }

    @Test
    void registrarFallo_quintoFallo_avisaDelBloqueoConElCorreoNormalizado() { // CP-AUD-03
        // Given: cuatro fallos, que todavía no bloquean
        for (int i = 0; i < 4; i++) {
            limitador.registrarFallo(CORREO, IP);
        }
        verifyNoInteractions(alertas);

        // When: el quinto, con el correo sin normalizar
        limitador.registrarFallo("  ANA.Garcia@Ejemplo.ES ", IP);

        // Then: un aviso con la cuenta, la IP y la duración del bloqueo
        verify(alertas).cuentaBloqueada(CORREO, IP, 30);
    }

    @Test
    void registrarFallo_fallosConElBloqueoVigente_noRepitenElAviso() { // CP-AUD-03
        // When: cincuenta fallos seguidos
        for (int i = 0; i < 50; i++) {
            limitador.registrarFallo(CORREO, IP);
        }

        // Then: el bloqueo se activó una sola vez
        verify(alertas, times(1)).cuentaBloqueada(CORREO, IP, 30);
    }

    @Test
    void registrarFallo_bloqueoQueEscala_avisaDeCadaNuevoBloqueo() { // CP-AUD-03
        // Given: primer bloqueo y fin del mismo
        for (int i = 0; i < 5; i++) {
            limitador.registrarFallo(CORREO, IP);
        }
        reloj.avanzar(Duration.ofSeconds(30));

        // When: otro fallo, que activa el segundo nivel
        limitador.registrarFallo(CORREO, IP);

        // Then: nuevo aviso con la duración del segundo bloqueo
        verify(alertas).cuentaBloqueada(CORREO, IP, 120);
    }
}
