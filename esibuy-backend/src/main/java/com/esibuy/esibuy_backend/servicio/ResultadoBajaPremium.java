package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.modelo.Usuario;

/** Usuario ya Estandar y cuantos productos solo premium se han quitado del carrito */

public record ResultadoBajaPremium(Usuario usuario, int productosQuitados) {
}
