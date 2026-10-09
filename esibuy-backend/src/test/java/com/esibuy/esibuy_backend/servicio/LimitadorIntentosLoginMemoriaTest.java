package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.excepcion.LoginBloqueadoTemporalmenteException;
import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * El limitador no debe acumular memoria sin límite: las cuentas y las IPs que llevan tiempo sin actividad se olvidan.
 * Sin esto, quien probara correos al azar llenaría la memoria del servidor.
 *
 * Reglas que fijan estas pruebas:
 *  - Una cuenta se olvida cuando pasa una hora desde su último fallo o desde el final de su último bloqueo (lo que
 *    ocurra más tarde). Hasta entonces recuerda su nivel de escalado.
 *  - Una IP se olvida cuando ya no tiene intentos dentro de su ventana de 5 minutos ni modo adaptativo vigente.
 *  - La limpieza se hace como mucho una vez por minuto, aprovechando las propias peticiones (sin hilos aparte).
 *
 * Clase añadida porque LimitadorIntentosLoginTest pertenece a una fase cerrada.
 */
@ExtendWith(MockitoExtension.class)
class LimitadorIntentosLoginMemoriaTest {

    private static final String CORREO = "ana.garcia@ejemplo.es";
    private static final String IP = "203.0.113.7";
    private static final String OTRA_IP = "198.51.100.20";
    private static final String CORREO_DISPARADOR = "disparador@ejemplo.es";

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

    /** Una petición cualquiera, que es la que aprovecha para limpiar la memoria. */
    private void unaPeticionCualquiera() {
        limitador.comprobarIntento(CORREO_DISPARADOR, OTRA_IP);
    }

    @Test
    void registrarFallo_cuentasSinActividadDuranteUnaHora_seOlvidan() {
        // Given: cien correos distintos con un fallo cada uno
        for (int i = 0; i < 100; i++) {
            limitador.registrarFallo("usuario" + i + "@ejemplo.es", IP);
        }
        assertThat(limitador.cuentasEnMemoria()).isEqualTo(100);

        // When: pasa más de una hora y llega otra petición
        reloj.avanzar(Duration.ofMinutes(61));
        unaPeticionCualquiera();

        // Then
        assertThat(limitador.cuentasEnMemoria()).isZero();
    }

    @Test
    void registrarFallo_cuentaConActividadReciente_seConservaYRecuerdaSuNivelDeEscalado() {
        // Given: una cuenta bloqueada (nivel 1, 30 s)
        for (int i = 0; i < 5; i++) {
            limitador.registrarFallo(CORREO, IP);
        }

        // When: pasan 50 minutos (menos de una hora desde el final del bloqueo) y falla otras cinco veces
        reloj.avanzar(Duration.ofMinutes(50));
        unaPeticionCualquiera();
        assertThat(limitador.cuentasEnMemoria()).isEqualTo(1);
        for (int i = 0; i < 5; i++) {
            limitador.registrarFallo(CORREO, IP);
        }

        // Then: sigue en el escalado, así que el segundo bloqueo es de 2 minutos
        verify(alertas).cuentaBloqueada(CORREO, IP, 120);
    }

    @Test
    void registrarFallo_cuentaOlvidadaTrasUnaHora_empiezaDeNuevoEnElPrimerNivel() {
        // Given: una cuenta bloqueada (nivel 1, 30 s)
        for (int i = 0; i < 5; i++) {
            limitador.registrarFallo(CORREO, IP);
        }

        // When: pasa más de una hora, se limpia y falla otras cinco veces
        reloj.avanzar(Duration.ofMinutes(61));
        unaPeticionCualquiera();
        for (int i = 0; i < 5; i++) {
            limitador.registrarFallo(CORREO, IP);
        }

        // Then: vuelve a ser un primer bloqueo (30 s), no uno de segundo nivel
        verify(alertas, times(2)).cuentaBloqueada(CORREO, IP, 30);
    }

    @Test
    void comprobarIntento_ipsSinIntentosEnLaVentana_seOlvidan() {
        // Given: cincuenta IPs distintas con un intento cada una
        for (int i = 0; i < 50; i++) {
            limitador.comprobarIntento(CORREO, "10.2.0." + (i + 1));
        }
        assertThat(limitador.ipsEnMemoria()).isEqualTo(50);

        // When: pasan más de 5 minutos y llega otra petición de una IP nueva
        reloj.avanzar(Duration.ofMinutes(6));
        unaPeticionCualquiera();

        // Then: solo queda la de la última petición
        assertThat(limitador.ipsEnMemoria()).isEqualTo(1);
    }

    @Test
    void comprobarIntento_ipConIntentosDentroDeLaVentana_noSeOlvidaYSigueLimitada() {
        // Given: cinco intentos del mismo usuario desde una IP (el límite)
        for (int i = 0; i < 5; i++) {
            limitador.comprobarIntento(CORREO, IP);
        }

        // When: pasan 4 minutos, se limpia la memoria con otra petición y se intenta una sexta vez
        reloj.avanzar(Duration.ofMinutes(4));
        unaPeticionCualquiera();

        // Then: la limpieza no ha borrado la ventana activa: sigue rechazado, y faltan 60 s
        LoginBloqueadoTemporalmenteException bloqueo = assertThrows(LoginBloqueadoTemporalmenteException.class,
                () -> limitador.comprobarIntento(CORREO, IP));
        assertThat(bloqueo.getSegundosRestantes()).isEqualTo(60);
    }

    @Test
    void comprobarIntento_ipEnModoAdaptativo_noSeOlvidaMientrasDura() {
        // Given: una IP que entra en modo adaptativo con 20 usuarios distintos
        for (int i = 0; i < 20; i++) {
            limitador.comprobarIntento("usuario" + i + "@ejemplo.es", IP);
        }

        // When: pasan 4 minutos y se limpia
        reloj.avanzar(Duration.ofMinutes(4));
        unaPeticionCualquiera();

        // Then: sigue recordada (más la de la petición que limpió)
        assertThat(limitador.ipsEnMemoria()).isEqualTo(2);
    }
}
