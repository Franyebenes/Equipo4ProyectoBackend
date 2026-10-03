package com.esibuy.esibuy_backend.servicio;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import org.springframework.stereotype.Component;

import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;

/**
 * Comprueba que el dominio del email puede recibir correo (decision D6), consultando el DNS:
 *   Con registros MX: valido, salvo que sea un "null MX" ({@code 0 .}, que declara que el dominio no acepta correo.
 *   Sin MX: valido si el dominio tiene direccion A o AAAA, que segun RFC 5321 (5.1) actua como MX
 *       implicito
 *   Dominio inexistente: no valido.
 *   DNS que no responde: {@link ServicioNoDisponibleException}; el registro se aborta con un 503 en vez de rechazar un email que podria ser correcto.
 */
@Component
public class ValidadorDominioEmailMx implements ValidadorDominioEmail {

    //Consulta de un tipo de registro DNS. Lanza NameNotFoundException si el dominio no existe. 
    @FunctionalInterface
    interface ConsultaDns {
        List<String> registros(String dominio, String tipo) throws NamingException;
    }

    private static final String TIEMPO_ESPERA_MS = "2000";
    private static final String REINTENTOS = "1";

    private final ConsultaDns dns;

    public ValidadorDominioEmailMx() {
        this(ValidadorDominioEmailMx::consultarConJndi);
    }

    ValidadorDominioEmailMx(ConsultaDns dns) {
        this.dns = dns;
    }

    @Override
    public boolean tieneDominioValido(String email) {
        String dominio = dominioEnAscii(email);
        if (dominio == null) {
            return false;
        }
        try {
            List<String> mx = dns.registros(dominio, "MX");
            if (!mx.isEmpty()) {
                return !esNullMx(mx);
            }
            return !dns.registros(dominio, "A").isEmpty() || !dns.registros(dominio, "AAAA").isEmpty();
        } catch (NameNotFoundException e) {
            return false;
        } catch (NamingException e) {
            throw new ServicioNoDisponibleException(e);
        }
    }

    //Dominio tras la arroba, en ASCII (los dominios con tildes o ñ se consultan en su forma punycode).
    private static String dominioEnAscii(String email) {
        int arroba = email == null ? -1 : email.lastIndexOf('@');
        if (arroba < 0 || arroba == email.length() - 1) {
            return null;
        }
        try {
            return IDN.toASCII(email.substring(arroba + 1), IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    //Un registro MX tiene la forma "preferencia servidor"; el null MX es "0 .".
    private static boolean esNullMx(List<String> mx) {
        if (mx.size() != 1) {
            return false;
        }
        String[] partes = mx.get(0).trim().split("\\s+");
        return partes.length == 2 && ".".equals(partes[1]);
    }

    private static List<String> consultarConJndi(String dominio, String tipo) throws NamingException {
        // InitialDirContext solo acepta Hashtable
        Hashtable<String, String> entorno = new Hashtable<>(); // NOSONAR
        entorno.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        entorno.put("com.sun.jndi.dns.timeout.initial", TIEMPO_ESPERA_MS);
        entorno.put("com.sun.jndi.dns.timeout.retries", REINTENTOS);

        DirContext contexto = new InitialDirContext(entorno);
        try {
            Attribute atributo = contexto.getAttributes(dominio, new String[] {tipo}).get(tipo);
            List<String> registros = new ArrayList<>();
            if (atributo != null) {
                NamingEnumeration<?> valores = atributo.getAll();
                while (valores.hasMore()) {
                    registros.add(String.valueOf(valores.next()));
                }
            }
            return registros;
        } finally {
            contexto.close();
        }
    }
}
