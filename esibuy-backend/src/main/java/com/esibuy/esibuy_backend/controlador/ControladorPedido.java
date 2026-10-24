package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.HistorialPedidosDTO;
import com.esibuy.esibuy_backend.dto.PedidoDTO;
import com.esibuy.esibuy_backend.dto.RealizarPedidoDTO;
import com.esibuy.esibuy_backend.dto.ValoracionDTO;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.ServicioPedido;
import com.esibuy.esibuy_backend.servicio.ServicioValoracion;

/**
 * Pedidos del cliente (HU-26, HU-27, HU-28 y HU-30). Solo clientes y premium. El cliente es siempre el de la sesion
 * (el principal es su id): nunca se recibe por parametro, asi que nadie puede ver ni tocar los pedidos de otro.
 * Todas las operaciones que modifican datos exigen token CSRF.
 */
@RestController
@RequestMapping("/api/pedidos")
public class ControladorPedido {

    private static final String AUTORIDAD_PREMIUM = "ROLE_" + Rol.PREMIUM.name();
    private static final String RUTA_ID = "/{id}";

    private final ServicioPedido servicioPedido;
    private final ServicioValoracion servicioValoracion;

    public ControladorPedido(ServicioPedido servicioPedido, ServicioValoracion servicioValoracion) {
        this.servicioPedido = servicioPedido;
        this.servicioValoracion = servicioValoracion;
    }

    // HU-26 Realizar pedido con el carrito actual. El pago es simulado: con la direccion de envio basta
    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<PedidoDTO> realizar(@RequestBody RealizarPedidoDTO datos, Authentication autenticacion) {
        PedidoDTO pedido = servicioPedido.realizar(autenticacion.getName(), esPremium(autenticacion),
                datos.direccionEnvio());
        return ResponseEntity.status(HttpStatus.CREATED).body(pedido);
    }

    // HU-27 Historial de compras: pedidos en proceso e historicos
    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<HistorialPedidosDTO> historial(Authentication autenticacion) {
        return ResponseEntity.ok(servicioPedido.historial(autenticacion.getName()));
    }

    // HU-28 Cancelar pedido: solo si esta pagado; devuelve el pedido ya cancelado
    @PostMapping(RUTA_ID + "/cancelar")
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<PedidoDTO> cancelar(@PathVariable String id, Authentication autenticacion) {
        return ResponseEntity.ok(servicioPedido.cancelar(autenticacion.getName(), id));
    }

    // HU-30 El cliente confirma que ha recibido el pedido; devuelve el pedido ya finalizado
    @PostMapping(RUTA_ID + "/recepcion")
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<PedidoDTO> confirmarRecepcion(@PathVariable String id, Authentication autenticacion) {
        return ResponseEntity.ok(servicioPedido.confirmarRecepcion(autenticacion.getName(), id));
    }

    // HU-30 Valorar un producto del pedido: {"puntos": 4}. Solo en pedidos finalizados y una sola vez
    @PostMapping(RUTA_ID + "/productos/{idProducto}/valoracion")
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<Void> valorar(@PathVariable String id, @PathVariable String idProducto,
                                        @RequestBody ValoracionDTO valoracion, Authentication autenticacion) {
        servicioValoracion.valorar(autenticacion.getName(), id, idProducto, valoracion.puntos());
        return ResponseEntity.noContent().build();
    }

    private static boolean esPremium(Authentication autenticacion) {
        return autenticacion.getAuthorities().stream().anyMatch(a -> AUTORIDAD_PREMIUM.equals(a.getAuthority()));
    }
}
