package com.esibuy.esibuy_backend.servicio;

/** Dice si una direccion de la libreta aparece en algun pedido del cliente que sigue en curso. */
public interface ComprobadorUsoDireccion {

    boolean estaEnPedidoEnCurso(String idUsuario, String idDireccion);
}