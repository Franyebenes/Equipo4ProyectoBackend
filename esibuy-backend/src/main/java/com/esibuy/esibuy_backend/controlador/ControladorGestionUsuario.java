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

    @GetMapping
    public PaginaDTO<UsuarioDTO> listar(@RequestParam(defaultValue = "0") int pagina,
                                        @RequestParam(defaultValue = "20") int tamano,
                                        @RequestParam(required = false) Rol rol,
                                        @RequestParam(required = false) EstadoUsuario estado) {
        return servicioGestionUsuarioo, rol, estado);
    }

    @PutMapping(path = RUTA_ID, consumes = MediaType.APPLICATION_JSON_VALUE)
    public UsuarioDTO modificar(@PathVariable String id, @RequestBody SolicitudModificacionUsuarioDTO solicitud) {
        return servicioGestionUsuariotud);
    }

    @PatchMapping(RUTA_ID + "/bloquear")
    public UsuarioDTO bloquear(@PathVariable String id, Authentication autenticacion) {
        return servicioGestionUsuariocacion.bloquear(id, autenticacion.getName());
    }

    @PatchMapping(RUTA_ID + "/desbloquear")
    public UsuarioDTO desbloquear(@PathVariable String id, Authentication autenticacion) {
        return  servicioGestionUsuario.desbloquear(id, autenticacion.getName());
    }

    @DeleteMapping(RUTA_ID)
    public ResponseEntity<Void> eliminar(@PathVariable String id, Authentication autenticacion) {
        servicioGestionUsuario.eliminar(id, autenticacion.getName());
        return ResponseEntity.noContent().build();
    }
    

}