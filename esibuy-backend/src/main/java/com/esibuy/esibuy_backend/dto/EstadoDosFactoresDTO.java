package com.esibuy.esibuy_backend.dto;

/** Estado del segundo factor de la cuenta: si esta activo y cuantos codigos de recuperacion quedan sin usar. */
public record EstadoDosFactoresDTO(boolean activo, int codigosRecuperacionDisponibles) {
}
