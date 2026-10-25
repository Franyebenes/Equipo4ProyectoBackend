package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.PagarDTO;
import com.esibuy.esibuy_backend.dto.ResultadoPagoDTO;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.ServicioPago;

/**
 * Pago del carrito (HU-23). Solo clientes y premium; siempre el carrito y las tarjetas del cliente de la sesion.
 * Exige token CSRF.
 */
@RestController
@RequestMapping("/api/pago")
public class ControladorPago {

    private static final String AUTORIDAD_PREMIUM = "ROLE_" + Rol.PREMIUM.name();

    private final ServicioPago servicioPago;

    public ControladorPago(ServicioPago servicioPago) {
        this.servicioPago = servicioPago;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<ResultadoPagoDTO> pagar(@RequestBody PagarDTO datos, Authentication autenticacion) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicioPago.pagar(autenticacion.getName(), esPremium(autenticacion), datos));
    }

    private static boolean esPremium(Authentication autenticacion) {
        return autenticacion.getAuthorities().stream().anyMatch(a -> AUTORIDAD_PREMIUM.equals(a.getAuthority()));
    }
}