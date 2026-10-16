package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.excepcion.FalloEnvioCorreoException;

/** Puerto de salida del correo. La implementacion real usa SMTP; los tests de otras capas usan un doble. */
public interface EnviadorCorreo {

    /** @throws FalloEnvioCorreoException si el servidor no entrega el mensaje */
    void enviar(MensajeCorreo mensaje);
}
