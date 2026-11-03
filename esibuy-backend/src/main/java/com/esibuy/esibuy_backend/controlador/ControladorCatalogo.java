package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.dto.ProductoDetalleDTO;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.FiltroCatalogoCliente;
import com.esibuy.esibuy_backend.servicio.ServicioCatalogo;

@RestController
@RequestMapping("/api/catalogo")
public class ControladorCatalogo {

    private static final String AUTORIDAD_PREMIUM = "ROLE_" + Rol.PREMIUM.name();

    private final ServicioCatalogo servicioCatalogo;

    public ControladorCatalogo(ServicioCatalogo servicioCatalogo) {
        this.servicioCatalogo = servicioCatalogo;
    }

    // HU-19 Buscar y HU-20 Filtrar productos. Todos los parametros son opcionales y se combinan: ?busqueda= (nombre
    // completo o parcial), ?idCategoria=, ?precioMinimo= y ?precioMaximo= (el precio que paga el cliente),
    // ?valoracionMinima= (1 a 5 estrellas), ?soloDisponibles=true (sin agotados) y ?pagina= empezando en 0. Si el
    // cliente es premium, los precios llevan tambien su descuento. Filtros sin sentido: 400 con el motivo.
    @GetMapping
    @PreAuthorize("hasRole('CLIENTE')")
    public ResponseEntity<PaginaDTO<ProductoClienteDTO>> buscar(
            @RequestParam(required = false) String busqueda,
            @RequestParam(required = false) String idCategoria,
            @RequestParam(required = false) Double precioMinimo,
            @RequestParam(required = false) Double precioMaximo,
            @RequestParam(required = false) Integer valoracionMinima,
            @RequestParam(defaultValue = "false") boolean soloDisponibles,
            @RequestParam(defaultValue = "0") int pagina,
            Authentication autenticacion) {
        FiltroCatalogoCliente filtro = new FiltroCatalogoCliente(busqueda, idCategoria, precioMinimo, precioMaximo,
                valoracionMinima, soloDisponibles);
        return ResponseEntity.ok(servicioCatalogo.buscar(filtro, pagina, esPremium(autenticacion)));
    }

    // HU-21 Ver detalle del producto: siempre con los datos actuales. Si no existe o esta inactivo, 404.
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLIENTE')")
    public ResponseEntity<ProductoDetalleDTO> verDetalle(@PathVariable String id, Authentication autenticacion) {
        return ResponseEntity.ok(servicioCatalogo.obtener(id, esPremium(autenticacion)));
    }

    private static boolean esPremium(Authentication autenticacion) {
        return autenticacion.getAuthorities().stream().anyMatch(a -> AUTORIDAD_PREMIUM.equals(a.getAuthority()));
    }
}