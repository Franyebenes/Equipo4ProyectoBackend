package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.AnadirCarritoDTO;
import com.esibuy.esibuy_backend.dto.CarritoDTO;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.ServicioCarrito;

/**
 * Carrito del cliente (HU-22.x). Solo clientes y premium. El cliente es siempre el de la sesion (el principal es su
 * id): nunca se recibe por parametro, asi que nadie puede tocar el carrito de otro.
 */
@RestController
@RequestMapping("/api/carrito")
public class ControladorCarrito {

    private static final String AUTORIDAD_PREMIUM = "ROLE_" + Rol.PREMIUM.name();

    private final ServicioCarrito servicioCarrito;

    public ControladorCarrito(ServicioCarrito servicioCarrito) {
        this.servicioCarrito = servicioCarrito;
    }

    // HU-22.1 Ver carrito. Si es premium se le aplica su descuento
    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<CarritoDTO> verCarrito(Authentication autenticacion) {
        return ResponseEntity.ok(servicioCarrito.obtener(autenticacion.getName(), esPremium(autenticacion)));
    }

    // HU-22.2 Anadir al carrito. Sin cantidad se toma 0, que el servicio rechaza con un 400
    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<Void> anadir(@RequestBody AnadirCarritoDTO datos, Authentication autenticacion) {
        int cantidad = datos.cantidad() == null ? 0 : datos.cantidad();
        servicioCarrito.anadir(autenticacion.getName(), datos.idProducto(), cantidad);
        return ResponseEntity.noContent().build();
    }

    private static boolean esPremium(Authentication autenticacion) {
        return autenticacion.getAuthorities().stream().anyMatch(a -> AUTORIDAD_PREMIUM.equals(a.getAuthority()));
    }
}