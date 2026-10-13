package com.esibuy.esibuy_backend.dto;

/**
 * Tipo de cuenta que elige el visitante en el formulario de registro. Decide que solicitud se lee del JSON
 * (ver {@link SolicitudRegistro}) y que rol se asigna:
 * <ul>
 *   <li>{@code CLIENTE} y {@code PREMIUM}: {@link SolicitudRegistroClienteDTO}.</li>
 *   <li>{@code VENDEDOR}: {@link SolicitudRegistroVendedorDTO}.</li>
 * </ul>
 */
public enum TipoCuenta {
    CLIENTE, PREMIUM, VENDEDOR
}
