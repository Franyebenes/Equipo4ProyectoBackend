package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.modelo.PagoSuscripcion;
import com.esibuy.esibuy_backend.modelo.Usuario;

/** Usuario ya Premium y justificante, para que el controlador refresque la sesion y responda. */
public record ResultadoPremium(Usuario usuario, PagoSuscripcion pago) {
}