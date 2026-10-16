package com.esibuy.esibuy_backend.util;

import java.time.Duration;

import com.esibuy.esibuy_backend.servicio.ModoTls;
import com.esibuy.esibuy_backend.servicio.PropiedadesCorreo;

/**
 * Constructor de PropiedadesCorreo para los tests. Parte de una configuracion valida y cada test cambia solo lo que
 * quiere probar. {@link #remoto()} es un servidor SMTP externo con STARTTLS y credenciales; {@link #local(int)} es un
 * servidor de pruebas en 127.0.0.1 sin cifrado (el caso de Mailpit y GreenMail).
 */
public final class PropiedadesCorreoPrueba {

    public static final String CONTRASENA_SMTP = "CLAVE-SMTP-SECRETA-9X";

    private String host = "smtp.ejemplo.es";
    private int puerto = 587;
    private ModoTls tls = ModoTls.STARTTLS;
    private String usuario = "usuario-smtp";
    private String contrasena = CONTRASENA_SMTP;
    private String remitente = "no-reply@esibuy.es";
    private String urlBaseFrontend = "https://esibuy.es";
    private Duration tiempoConexion = Duration.ofSeconds(5);
    private Duration tiempoLectura = Duration.ofSeconds(10);

    private PropiedadesCorreoPrueba() {
    }

    public static PropiedadesCorreoPrueba remoto() {
        return new PropiedadesCorreoPrueba();
    }

    public static PropiedadesCorreoPrueba local(int puerto) {
        return new PropiedadesCorreoPrueba().host("127.0.0.1").puerto(puerto).tls(ModoTls.NINGUNO)
                .usuario(null).contrasena(null).urlBaseFrontend("http://localhost:43127")
                .tiempoConexion(Duration.ofSeconds(1)).tiempoLectura(Duration.ofSeconds(1));
    }

    public PropiedadesCorreoPrueba host(String valor) {
        this.host = valor;
        return this;
    }

    public PropiedadesCorreoPrueba puerto(int valor) {
        this.puerto = valor;
        return this;
    }

    public PropiedadesCorreoPrueba tls(ModoTls valor) {
        this.tls = valor;
        return this;
    }

    public PropiedadesCorreoPrueba usuario(String valor) {
        this.usuario = valor;
        return this;
    }

    public PropiedadesCorreoPrueba contrasena(String valor) {
        this.contrasena = valor;
        return this;
    }

    public PropiedadesCorreoPrueba remitente(String valor) {
        this.remitente = valor;
        return this;
    }

    public PropiedadesCorreoPrueba urlBaseFrontend(String valor) {
        this.urlBaseFrontend = valor;
        return this;
    }

    public PropiedadesCorreoPrueba tiempoConexion(Duration valor) {
        this.tiempoConexion = valor;
        return this;
    }

    public PropiedadesCorreoPrueba tiempoLectura(Duration valor) {
        this.tiempoLectura = valor;
        return this;
    }

    public PropiedadesCorreo construir() {
        return new PropiedadesCorreo(host, puerto, tls, usuario, contrasena, remitente, urlBaseFrontend,
                tiempoConexion, tiempoLectura);
    }
}
