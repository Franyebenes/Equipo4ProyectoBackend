package com.esibuy.esibuy_backend.servicio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Consulta el servicio Pwned Passwords (haveibeenpwned.com) con k-anonimato: solo se envian los 5 primeros
 * caracteres del SHA-1 de la contrasena y la comparacion del resto del hash se hace aqui. La contrasena ni su hash
 * completo salen nunca del servidor. Con Add-Padding todas las respuestas tienen un tamano parecido, asi que ni
 * siquiera el tamano revela nada.
 *
 * <p>Si el servicio falla lanza la excepcion de RestClient; decidir que hacer entonces le corresponde a
 * {@link DiccionarioContrasenasCompuesto}.
 */
@Component
public class ClientePwnedPasswords {

    private static final int LONGITUD_PREFIJO = 5;
    private static final String SEPARADOR_RECUENTO = ":";

    private final RestClient cliente;

    @Autowired
    public ClientePwnedPasswords(
            @Value("${esibuy.pwned-passwords.url:https://api.pwnedpasswords.com}") String urlBase,
            @Value("${esibuy.pwned-passwords.tiempo-espera:PT2S}") Duration tiempoEspera) {
        this(RestClient.builder().requestFactory(fabricaConTiempoEspera(tiempoEspera)), urlBase);
    }

    /** Para pruebas: permite enlazar el builder a un servidor simulado. */
    ClientePwnedPasswords(RestClient.Builder builder, String urlBase) {
        this.cliente = builder
                .baseUrl(urlBase)
                .defaultHeader(HttpHeaders.USER_AGENT, "ESIBuy-registro")
                .defaultHeader("Add-Padding", "true")
                .build();
    }

    /** @return true si la contrasena aparece al menos una vez en filtraciones publicas */
    public boolean estaFiltrada(String contrasena) {
        String hash = sha1EnMayusculas(contrasena);
        String prefijo = hash.substring(0, LONGITUD_PREFIJO);
        String sufijo = hash.substring(LONGITUD_PREFIJO);

        String respuesta = cliente.get().uri("/range/{prefijo}", prefijo).retrieve().body(String.class);
        return respuesta != null && respuesta.lines().anyMatch(linea -> coincide(linea, sufijo));
    }

    /** Cada linea es "SUFIJO:VECES". Las de relleno (Add-Padding) traen 0 veces y no cuentan. */
    private static boolean coincide(String linea, String sufijo) {
        String[] partes = linea.split(SEPARADOR_RECUENTO, 2);
        return partes.length == 2 && partes[0].equalsIgnoreCase(sufijo) && !"0".equals(partes[1].trim());
    }

    private static String sha1EnMayusculas(String contrasena) {
        try {
            // SHA-1 lo impone el protocolo de Pwned Passwords; no se usa para guardar contrasenas (eso es Argon2id)
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1"); // NOSONAR
            return HexFormat.of().withUpperCase().formatHex(sha1.digest(contrasena.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no ofrece SHA-1", e);
        }
    }

    private static SimpleClientHttpRequestFactory fabricaConTiempoEspera(Duration tiempoEspera) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(tiempoEspera);
        fabrica.setReadTimeout(tiempoEspera);
        return fabrica;
    }
}
