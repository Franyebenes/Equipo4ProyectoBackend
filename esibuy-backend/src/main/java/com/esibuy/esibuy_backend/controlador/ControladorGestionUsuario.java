package com.esibuy.esibuy_backend.controlador;

@RestController
@RequestMapping("/api/admin/usuarios")
@PreAuthorize("hasRole('ADMIN')") // to strict access to admin users only
public class ControladorGestionUsuario {

    private final ServicioGestionUsuario servicioGestionUsuario;

    public ControladorGestionUsuario(ServicioGestionUsuario servicioGestionUsuario) {
        this.servicioGestionUsuario = servicioGestionUsuario;
    }


    @PostMapping(path = "/administradores", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UsuarioDTO> crearAdministrador(@RequestBody SolicitudAltaAdministradorDTO solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.crearAdministrador(solicitud));
    }

}