package com.esibuy.esibuy_backend.configuracion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limita los registros por IP (decision D10) en {@code POST /api/auth/registro}, sea cual sea el tipo de cuenta;
 * al superar maxPeticiones dentro de la ventana responde 429 con la cabecera
 * Retry-After en segundos. Tests: CP-SEG-13.
 *
 * <p>Ventana fija por IP, en memoria: suficiente para una sola instancia. Con varias instancias habria que llevar
 * el contador a un almacen compartido.
 */
public class FiltroLimitePeticionesRegistro extends OncePerRequestFilter {

    static final String RUTA_REGISTRO = "/api/auth/registro";
    /** A partir de este numero de IPs registradas se purgan las ventanas caducadas para no crecer sin limite. */
    private static final int IPS_ANTES_DE_PURGAR = 10_000;
    private static final String CUERPO_429 =
            "{\"mensaje\":\"Demasiados intentos de registro. Vuelve a intentarlo mas tarde\"}";

    private final int maxPeticiones;
    private final Duration ventana;
    private final Clock reloj;
    private final Map<String, VentanaIp> ventanasPorIp = new ConcurrentHashMap<>();

    public FiltroLimitePeticionesRegistro(int maxPeticiones, Duration ventana) {
        this(maxPeticiones, ventana, Clock.systemUTC());
    }

    public FiltroLimitePeticionesRegistro(int maxPeticiones, Duration ventana, Clock reloj) {
        if (maxPeticiones < 1 || ventana.isNegative() || ventana.isZero()) {
            throw new IllegalArgumentException("El limite y la ventana deben ser positivos");
        }
        this.maxPeticiones = maxPeticiones;
        this.ventana = ventana;
        this.reloj = reloj;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String ruta = request.getRequestURI().substring(request.getContextPath().length());
        // Tambien cubre subrutas, por si en el futuro el registro tuviera mas de una
        boolean esRegistro = ruta.equals(RUTA_REGISTRO) || ruta.startsWith(RUTA_REGISTRO + "/");
        return !HttpMethod.POST.matches(request.getMethod()) || !esRegistro;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Instant ahora = reloj.instant();
        purgarSiHaceFalta(ahora);
        // getRemoteAddr y no X-Forwarded-For, que lo elige el cliente. Detras de un proxy hay que configurar
        // server.forward-headers-strategy para que Spring resuelva la IP real.
        VentanaIp ventanaIp = ventanasPorIp.compute(request.getRemoteAddr(),
                (ip, actual) -> actual == null || actual.haCaducado(ahora)
                        ? new VentanaIp(ahora.plus(ventana), 1)
                        : actual.conUnaPeticionMas());

        if (ventanaIp.peticiones() > maxPeticiones) {
            rechazar(response, ventanaIp.segundosHastaElFin(ahora));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void purgarSiHaceFalta(Instant ahora) {
        if (ventanasPorIp.size() >= IPS_ANTES_DE_PURGAR) {
            ventanasPorIp.values().removeIf(ventanaIp -> ventanaIp.haCaducado(ahora));
        }
    }

    private static void rechazar(HttpServletResponse response, long segundosDeEspera) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(segundosDeEspera));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(CUERPO_429);
    }

    /** Peticiones de una IP en la ventana actual, que termina en {@code fin}. Inmutable. */
    private record VentanaIp(Instant fin, int peticiones) {

        boolean haCaducado(Instant ahora) {
            return !ahora.isBefore(fin);
        }

        VentanaIp conUnaPeticionMas() {
            return new VentanaIp(fin, peticiones + 1);
        }

        /** Redondeado hacia arriba y como minimo 1, para que Retry-After nunca invite a reintentar ya. */
        long segundosHastaElFin(Instant ahora) {
            long milisegundos = Duration.between(ahora, fin).toMillis();
            return Math.max(1, (milisegundos + 999) / 1000);
        }
    }
}
