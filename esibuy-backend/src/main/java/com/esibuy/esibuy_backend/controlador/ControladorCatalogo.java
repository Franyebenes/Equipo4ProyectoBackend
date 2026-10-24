package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.ServicioCatalogo;

@RestController
@RequestMapping("/api/catalogo")
public class ControladorCatalogo {

    private static final String AUTORIDAD_PREMIUM = "ROLE_" + Rol.PREMIUM.name();

    private final ServicioCatalogo servicioCatalogo;

    public ControladorCatalogo(ServicioCatalogo servicioCatalogo) {
        this.servicioCatalogo = servicioCatalogo;
    }

    // Buscar productos: ?busqueda= nombre completo o parcial (opcional) y ?pagina= empezando en 0. Si el cliente es
    // premium, los precios llevan tambien su descuento.
    @GetMapping
    @PreAuthorize("hasRole('CLIENTE')")
    public ResponseEntity<PaginaDTO<ProductoClienteDTO>> buscar(
            @RequestParam(required = false) String busqueda,
            @RequestParam(defaultValue = "0") int pagina,
            Authentication autenticacion) {
        return ResponseEntity.ok(servicioCatalogo.buscar(busqueda, pagina, esPremium(autenticacion)));
    }

    private static boolean esPremium(Authentication autenticacion) {
        return autenticacion.getAuthorities().stream().anyMatch(a -> AUTORIDAD_PREMIUM.equals(a.getAuthority()));
    }
}
