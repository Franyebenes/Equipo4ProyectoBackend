package com.esibuy.esibuy_backend.dto;

/**
 * Peticion de pago (HU-23): con que tarjeta guardada se paga, a que direccion se envia y de que vendedor son los
 * productos que se compran (cada compra es de un solo vendedor).
 */
public record PagarDTO(String idMetodoPago, DireccionEnvioDTO direccionEnvio, String idVendedor) {

    /** Sin vendedor: vale si en el carrito solo hay productos de uno. */
    public PagarDTO(String idMetodoPago, DireccionEnvioDTO direccionEnvio) {
        this(idMetodoPago, direccionEnvio, null);
    }
}