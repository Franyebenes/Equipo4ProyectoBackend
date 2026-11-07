package com.esibuy.esibuy_backend.servicio;

/*
 * Resultado del primer paso del 2FA en el registro: el correo normalizado para el que se genero, el secreto cifrado (que el
 * controlador guarda en la sesion) y lo que se muestra a la persona (la URI del QR y el secreto en Base32). El toString no
 * incluye nada de eso.
 */
public record PreparacionRegistroDosFactores(String email, String secretoCifrado, String uri, String secreto) {

    @Override
    public String toString() {
        return "PreparacionRegistroDosFactores[email=[OCULTO], secretoCifrado=[OCULTO], uri=[OCULTO], secreto=[OCULTO]]";
    }
}
