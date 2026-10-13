package com.esibuy.esibuy_backend.controlador;

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.dto.SolicitudAltaAdministradorDTO;
import com.esibuy.esibuy_backend.dto.SolicitudModificacionUsuarioDTO;
import com.esibuy.esibuy_backend.dto.UsuarioDTO;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.ServicioGestionUsuarios;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/usuarios")
@PreAuthorize("hasRole('ADMIN')") // to strict access to admin users only
public class ControladorGestionUsuarios {

    private static final String RUTA_ID = "/{id}";

    private final ServicioGestionUsuarios servicioGestionUsuarios;

    public ControladorGestionUsuarios(ServicioGestionUsuarios servicioGestionUsuarios) {
        this.servicioGestionUsuarios = servicioGestionUsuarios;
    }


    @PostMapping(path = "/administradores", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaRegistroDTO> crearAdministrador(@RequestBody SolicitudAltaAdministradorDTO solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicioGestionUsuarios.crearAdministrador(solicitud));
    }

    @GetMapping
    public PaginaDTO<UsuarioDTO> listar(@RequestParam(defaultValue = "0") int pagina,
                                        @RequestParam(defaultValue = "20") int tamano,
                                        @RequestParam(required = false) Rol rol,
                                        @RequestParam(required = false) EstadoUsuario estado,
                                        @RequestParam(required = false) String busqueda) {
        return servicioGestionUsuarios.listar(pagina, tamano, rol, estado, busqueda);
    }

    @PutMapping(path = RUTA_ID, consumes = MediaType.APPLICATION_JSON_VALUE)
    public UsuarioDTO modificar(@PathVariable String id, @RequestBody SolicitudModificacionUsuarioDTO solicitud) {
        return servicioGestionUsuarios.modificar(id, solicitud);
    }

    @PatchMapping(RUTA_ID + "/bloquear")
    public UsuarioDTO bloquear(@PathVariable String id, Authentication autenticacion) {
        return servicioGestionUsuarios.bloquear(id, autenticacion.getName());
    }

    @PatchMapping(RUTA_ID + "/desbloquear")
    public UsuarioDTO desbloquear(@PathVariable String id, Authentication autenticacion) {
        return  servicioGestionUsuarios.desbloquear(id, autenticacion.getName());
    }

    @DeleteMapping(RUTA_ID)
    public ResponseEntity<Void> eliminar(@PathVariable String id, Authentication autenticacion) {
        servicioGestionUsuarios.eliminar(id, autenticacion.getName());
        return ResponseEntity.noContent().build();
    }
    

}