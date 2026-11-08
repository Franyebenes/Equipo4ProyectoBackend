package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.SeguimientoPedidoDTO;
import com.esibuy.esibuy_backend.servicio.ServicioSeguimientoPedido;

/**
 * Estado del envio: seguimiento de un pedido del cliente. Solo para CLIENTE y PREMIUM (regla de /api/pedidos/** en
 * ConfiguracionSeguridad y PreAuthorize). El cliente es siempre el de la sesion, asi que nadie puede ver el
 * seguimiento de un pedido de otro.
 */
@RestController
@RequestMapping("/api/pedidos")
public class ControladorSeguimientoPedido {

    private final ServicioSeguimientoPedido servicioSeguimientoPedido;

    public ControladorSeguimientoPedido(ServicioSeguimientoPedido servicioSeguimientoPedido) {
        this.servicioSeguimientoPedido = servicioSeguimientoPedido;
    }

    // Estado del envio: estado actual, fecha estimada y pasos del envio. Si no es suyo o no existe, 404
    @GetMapping("/{id}/seguimiento")
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<SeguimientoPedidoDTO> seguimiento(@PathVariable String id, Authentication autenticacion) {
        return ResponseEntity.ok(servicioSeguimientoPedido.consultar(autenticacion.getName(), id));
    }
}
