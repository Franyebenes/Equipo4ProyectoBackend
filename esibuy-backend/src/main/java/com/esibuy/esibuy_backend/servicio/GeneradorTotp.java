package com.esibuy.esibuy_backend.servicio;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/*
 * Codigos TOTP (RFC 6238) compatibles con Google Authenticator: HMAC-SHA1, 6 digitos y pasos de 30 segundos. La
 * aplicacion ignora cualquier otro valor, asi que no son configurables.
 *
 * La verificacion acepta el paso actual y los adyacentes (VENTANA, por el desfase del reloj del movil) y rechaza los
 * pasos ya aceptados (ultimoPaso) para que un codigo no valga dos veces. Devuelve el paso que ha coincidido para que el
 * servicio lo guarde de forma atomica. Los tres candidatos se comparan siempre y en tiempo constante, sin salir al primer
 * acierto, para que el tiempo de respuesta no dependa de cual coincide.
 * Tests: GeneradorTotpTest (CP-2FA-08 a CP-2FA-25).
 */
public class GeneradorTotp {

    public static final int DIGITOS = 6;
    public static final int PERIODO_SEGUNDOS = 30;
    public static final int VENTANA = 1;
    public static final int LONGITUD_SECRETO_BYTES = 20;
    // Valor de ultimoPaso para un secreto que aun no ha aceptado ningun codigo.
    public static final long NINGUN_PASO = -1;

    // El RFC 4226 pide al menos 128 bits de secreto
    private static final int LONGITUD_MINIMA_SECRETO_BYTES = 16;
    // HMAC-SHA1 es el unico algoritmo que usa Google Authenticator; como HMAC no tiene los problemas de SHA-1 a secas
    private static final String ALGORITMO_HMAC = "HmacSHA1"; // NOSONAR
    private static final int MODULO = 1_000_000;

    private final Clock reloj;
    private final SecureRandom aleatorio;

    public GeneradorTotp(Clock reloj) {
        this(reloj, new SecureRandom());
    }

    public GeneradorTotp(Clock reloj, SecureRandom aleatorio) {
        if (reloj == null || aleatorio == null) {
            throw new IllegalArgumentException("El reloj y la fuente aleatoria son obligatorios");
        }
        this.reloj = reloj;
        this.aleatorio = aleatorio;
    }

    // Un secreto nuevo de 160 bits.
    public byte[] nuevoSecreto() {
        byte[] secreto = new byte[LONGITUD_SECRETO_BYTES];
        aleatorio.nextBytes(secreto);
        return secreto;
    }

    // Paso de tiempo actual: segundos desde 1970 entre el periodo. 
    public long pasoActual() {
        return reloj.instant().getEpochSecond() / PERIODO_SEGUNDOS;
    }

    // Codigo de seis digitos (con ceros a la izquierda) de un paso.
    public String codigo(byte[] secreto, long paso) {
        comprobarSecreto(secreto);
        if (paso < 0) {
            throw new IllegalArgumentException("El paso no puede ser negativo");
        }
        byte[] resumen = hmac(secreto, paso);
        // Truncado dinamico del RFC 4226
        int desplazamiento = resumen[resumen.length - 1] & 0x0F;
        int entero = ((resumen[desplazamiento] & 0x7F) << 24)
                | ((resumen[desplazamiento + 1] & 0xFF) << 16)
                | ((resumen[desplazamiento + 2] & 0xFF) << 8)
                | (resumen[desplazamiento + 3] & 0xFF);
        return String.format("%0" + DIGITOS + "d", entero % MODULO);
    }

    /*
     * Comprueba un codigo escrito por el usuario. Devuelve el paso que ha coincidido, o vacio si el formato no son seis
     * digitos (se toleran espacios, como los muestra la aplicacion), si no coincide con ningun paso de la ventana o si
     * ese paso no es posterior a ultimoPaso. Nunca lanza excepcion por el codigo; si el secreto no es valido, si.
     */
    public OptionalLong verificar(byte[] secreto, String codigo, long ultimoPaso) {
        comprobarSecreto(secreto);
        String limpio = limpiar(codigo);
        if (limpio == null) {
            return OptionalLong.empty();
        }
        byte[] introducido = limpio.getBytes(StandardCharsets.US_ASCII);
        long actual = pasoActual();
        long coincidente = -1;
        boolean encontrado = false;
        for (long paso = actual - VENTANA; paso <= actual + VENTANA; paso++) {
            if (paso < 0) {
                continue;
            }
            // Se calcula y compara siempre, aunque el paso ya no pueda aceptarse
            boolean igual = MessageDigest.isEqual(introducido, codigo(secreto, paso).getBytes(StandardCharsets.US_ASCII));
            if (igual && paso > ultimoPaso && !encontrado) {
                coincidente = paso;
                encontrado = true;
            }
        }
        return encontrado ? OptionalLong.of(coincidente) : OptionalLong.empty();
    }

    // Quita los espacios y exige exactamente seis digitos ASCII; null si no los son.
    private static String limpiar(String codigo) {
        if (codigo == null) {
            return null;
        }
        String sinEspacios = codigo.replace(" ", "");
        if (sinEspacios.length() != DIGITOS) {
            return null;
        }
        for (int i = 0; i < sinEspacios.length(); i++) {
            char c = sinEspacios.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        return sinEspacios;
    }

    private static void comprobarSecreto(byte[] secreto) {
        if (secreto == null || secreto.length < LONGITUD_MINIMA_SECRETO_BYTES) {
            throw new IllegalArgumentException("El secreto debe tener al menos 128 bits");
        }
    }

    private static byte[] hmac(byte[] secreto, long contador) {
        try {
            // Mac no es thread-safe: una instancia por llamada.
            // HMAC-SHA1 lo exige el RFC 6238 y es el unico que acepta Google Authenticator; no se usa SHA-1 como hash a secas
            Mac mac = Mac.getInstance(ALGORITMO_HMAC); // NOSONAR
            mac.init(new SecretKeySpec(secreto, ALGORITMO_HMAC));
            return mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(contador).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular el codigo TOTP");
        }
    }
}
