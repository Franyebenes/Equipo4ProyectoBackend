package com.esibuy.esibuy_backend.controlador;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.PedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDetalleDTO;
import com.esibuy.esibuy_backend.dto.SolicitudCambioEstadoPedidoDTO;
import com.esibuy.esibuy_backend.servicio.ServicioPedidoVendedor;

/**
 * Pedidos recibidos por el vendedor. Solo para el rol VENDEDOR (regla de /api/vendedor/** en ConfiguracionSeguridad y
 * PreAuthorize en cada metodo). El vendedor es siempre el de la sesion (el principal es su id): nunca se recibe por
 * parametro, asi que nadie puede ver los pedidos de otro vendedor.
 */
@RestController
@RequestMapping("/api/vendedor/pedidos")
public class ControladorPedidoVendedor {

    private final ServicioPedidoVendedor servicioPedidoVendedor;

    public ControladorPedidoVendedor(ServicioPedidoVendedor servicioPedidoVendedor) {
        this.servicioPedidoVendedor = servicioPedidoVendedor;
    }

    // Ver pedidos recibidos: los pedidos de sus productos, los mas recientes primero (lista vacia si no tiene)
    @GetMapping
    @PreAuthorize("hasRole('VENDEDOR')")
    public ResponseEntity<List<PedidoRecibidoDTO>> listar(Authentication autenticacion) {
        return ResponseEntity.ok(servicioPedidoVendedor.listar(autenticacion.getName()));
    }

    // Ver pedidos recibidos: detalle de un pedido. Si no existe o es de otro vendedor, 404
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VENDEDOR')")
    public ResponseEntity<PedidoRecibidoDetalleDTO> detalle(@PathVariable String id, Authentication autenticacion) {
        return ResponseEntity.ok(servicioPedidoVendedor.detalle(autenticacion.getName(), id));
    }

    // Procesar estado del pedido: avanza el envio y devuelve el detalle actualizado. 404 si no es suyo, 409 si el
    // cambio no se permite (hacia atras o en un pedido ya entregado, finalizado o cancelado) y 400 sin estado
    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasRole('VENDEDOR')")
    public ResponseEntity<PedidoRecibidoDetalleDTO> cambiarEstado(@PathVariable String id,
                                                                  @RequestBody SolicitudCambioEstadoPedidoDTO solicitud,
                                                                  Authentication autenticacion) {
        return ResponseEntity.ok(servicioPedidoVendedor.cambiarEstado(autenticacion.getName(), id, solicitud.estado()));
    }
}
